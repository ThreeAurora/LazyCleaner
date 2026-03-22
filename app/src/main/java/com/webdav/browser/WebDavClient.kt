package com.webdav.browser

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*

data class DavItem(
    val name: String, val href: String, val isDir: Boolean,
    val size: Long = 0, val date: String = ""
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val isAudio get() = ext in setOf("mp3","flac","wav","aac","ogg","m4a","wma","opus")
    val isMedia get() = isImage || isVideo
    val isHidden get() = name.startsWith(".")
    val isTrash get() = name.startsWith(".webdav_trash")
    val fileIcon get() = when {
        isDir -> "📁"; isImage -> "🖼️"; isVideo -> "🎬"; isAudio -> "🎵"
        ext in setOf("pdf") -> "📕"
        ext in setOf("doc","docx","odt","rtf") -> "📝"
        ext in setOf("xls","xlsx","csv") -> "📊"
        ext in setOf("ppt","pptx") -> "📙"
        ext in setOf("zip","rar","7z","tar","gz","bz2") -> "📦"
        ext in setOf("apk") -> "📲"
        ext in setOf("txt","log","md","json","xml","yml","yaml","ini","cfg","conf") -> "📃"
        ext in setOf("exe","msi","bat","sh") -> "⚙️"
        else -> "📄"
    }
}

data class TrashEntry(
    val id: String, val fileName: String, val trashHref: String,
    val size: Long = 0, val date: String = ""
) {
    val ext get() = fileName.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val storedName get() = "${id}_${fileName}"
}

class WebDavClient(private val baseUrl: String, user: String, pass: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .apply {
            if (user.isNotBlank()) authenticator { _, resp ->
                resp.request.newBuilder()
                    .header("Authorization", Credentials.basic(user, pass)).build()
            }
        }.build()

    fun listDir(path: String): List<DavItem> {
        val url = buildUrl(path)
        val req = Request.Builder().url(url).method("PROPFIND", propfindBody())
            .header("Depth", "1").build()
        val xml = client.newCall(req).execute().body?.string() ?: return emptyList()
        return parsePropfind(xml, path)
    }

    fun permanentDelete(path: String): Boolean {
        return client.newCall(Request.Builder().url(buildUrl(path)).delete().build())
            .execute().isSuccessful
    }

    fun move(fromPath: String, toPath: String): Boolean {
        val req = Request.Builder().url(buildUrl(fromPath))
            .method("MOVE", null)
            .header("Destination", buildEncodedUrl(toPath))
            .header("Overwrite", "F").build()
        return client.newCall(req).execute().isSuccessful
    }

    fun mkdir(path: String): Boolean {
        val resp = client.newCall(
            Request.Builder().url(buildUrl(path)).method("MKCOL", null).build()
        ).execute()
        return resp.isSuccessful || resp.code == 405 || resp.code == 301
    }

    fun mkdirs(path: String) {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        var cur = ""
        for (part in parts) { cur += "/$part"; mkdir("$cur/") }
    }

    fun putText(path: String, content: String): Boolean {
        val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
        return client.newCall(Request.Builder().url(buildUrl(path)).put(body).build())
            .execute().isSuccessful
    }

    fun getText(path: String): String? {
        val resp = client.newCall(Request.Builder().url(buildUrl(path)).get().build()).execute()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    fun exists(path: String): Boolean {
        return try {
            client.newCall(Request.Builder().url(buildUrl(path)).head().build())
                .execute().isSuccessful
        } catch (_: Exception) { false }
    }

    fun fileUrl(path: String) = buildEncodedUrl(path)
    fun getClient() = client

    // ===== 回收站 =====

    private fun getDriveRoot(path: String): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) "/${parts[0]}" else ""
    }

    private fun trashDir(filePath: String) = "${getDriveRoot(filePath)}/.webdav_trash"

    fun moveToTrash(filePath: String): Boolean {
        val dir = trashDir(filePath); mkdir("$dir/")
        val fileName = filePath.trimEnd('/').substringAfterLast('/')
        val id = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        val trashFilePath = "$dir/${id}_${fileName}"
        if (!putText("$trashFilePath.trashinfo", filePath)) return false
        val success = move(filePath, trashFilePath)
        if (!success) permanentDelete("$trashFilePath.trashinfo")
        return success
    }

    fun listTrash(currentPath: String): List<TrashEntry> {
        val dir = trashDir(currentPath)
        val url = buildUrl("$dir/")
        val req = Request.Builder().url(url).method("PROPFIND", propfindBody())
            .header("Depth", "1").build()
        val resp = try { client.newCall(req).execute() } catch (_: Exception) { return emptyList() }
        if (!resp.isSuccessful) return emptyList()
        val xml = resp.body?.string() ?: return emptyList()
        return parsePropfind(xml, "$dir/")
            .filter { !it.name.endsWith(".trashinfo") && !it.isDir }
            .mapNotNull { item ->
                val idx = item.name.indexOf('_')
                if (idx < 1) return@mapNotNull null
                val id = item.name.substring(0, idx)
                val fn = item.name.substring(idx + 1)
                if (fn.isBlank()) return@mapNotNull null
                TrashEntry(id, fn, item.href, item.size, item.date)
            }
    }

    fun restoreFromTrash(entry: TrashEntry): Pair<Boolean, String> {
        val originalPath = getText("${entry.trashHref}.trashinfo")?.trim()
            ?: return Pair(false, "找不到原始路径信息")
        if (originalPath.isBlank()) return Pair(false, "原始路径为空")
        val parentDir = originalPath.substringBeforeLast('/')
        if (parentDir.isNotBlank() && parentDir != originalPath) mkdirs(parentDir)
        var targetPath = originalPath
        if (exists(targetPath)) {
            val dirPart = originalPath.substringBeforeLast('/')
            val fullName = originalPath.substringAfterLast('/')
            val dotIdx = fullName.lastIndexOf('.')
            val baseName = if (dotIdx > 0) fullName.substring(0, dotIdx) else fullName
            val ext = if (dotIdx > 0) fullName.substring(dotIdx) else ""
            var c = 1
            while (exists(targetPath)) {
                targetPath = "$dirPart/${baseName}_${c}${ext}"; c++
                if (c > 9999) return Pair(false, "重名文件过多")
            }
        }
        val success = move(entry.trashHref, targetPath)
        if (!success) return Pair(false, "移动失败")
        permanentDelete("${entry.trashHref}.trashinfo")
        val renamed = if (targetPath != originalPath) "（已重命名为 ${targetPath.substringAfterLast('/')}）" else ""
        return Pair(true, "已还原 $renamed")
    }

    fun permanentDeleteTrashEntry(entry: TrashEntry): Boolean {
        val a = permanentDelete(entry.trashHref)
        permanentDelete("${entry.trashHref}.trashinfo")
        return a
    }

    fun emptyTrash(currentPath: String): Boolean {
        return permanentDelete("${trashDir(currentPath)}/")
    }

    // ===== 内部 =====

    private fun buildUrl(path: String) = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    private fun buildEncodedUrl(path: String): String {
        val segments = path.trimStart('/').split('/').map {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return baseUrl.trimEnd('/') + "/" + segments.joinToString("/")
    }
    private fun propfindBody() = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
        <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
        </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())

    private fun parsePropfind(xml: String, curPath: String): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val p = factory.newPullParser(); p.setInput(StringReader(xml))
        var href = ""; var sz = 0L; var dt = ""; var isCol = false; var inR = false; var tag = ""
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name.lowercase()
                    if (tag == "response") { inR = true; href = ""; sz = 0; dt = ""; isCol = false }
                    if (tag == "collection") isCol = true
                }
                XmlPullParser.TEXT -> {
                    val text = p.text?.trim() ?: ""
                    if (inR && text.isNotEmpty()) when (tag) {
                        "href" -> href = text
                        "getcontentlength" -> sz = text.toLongOrNull() ?: 0
                        "getlastmodified" -> dt = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.lowercase() == "response" && inR) {
                        inR = false
                        val dec = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                        val isSelf = dec.trimEnd('/') == curPath.trimEnd('/') ||
                            dec.trimEnd('/') == ("/" + curPath.trim('/')).trimEnd('/') ||
                            dec.trimEnd('/').isEmpty() && curPath.trim('/').isEmpty()
                        if (!isSelf && href.isNotEmpty()) {
                            val name = dec.trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) items.add(DavItem(name, dec, isCol, sz, dt))
                        }
                    }; tag = ""
                }
            }; p.next()
        }; return items
    }
}
