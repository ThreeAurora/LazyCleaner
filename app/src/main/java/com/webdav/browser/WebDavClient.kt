package com.webdav.browser

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

data class DavItem(
    val name: String, val href: String, val isDir: Boolean,
    val size: Long = 0, val date: String = ""
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val isMedia get() = isImage || isVideo
}

data class TrashEntry(
    val id: String,
    val fileName: String,
    val originalPath: String,
    val deletedAt: String,
    val size: Long
) {
    val ext get() = fileName.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    /** 回收站中实际存储的文件名 */
    val storedName get() = "${id}_${fileName}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("originalPath", originalPath)
        put("deletedAt", deletedAt)
        put("size", size)
    }

    companion object {
        fun fromJson(j: JSONObject) = TrashEntry(
            id = j.optString("id", ""),
            fileName = j.optString("fileName", ""),
            originalPath = j.optString("originalPath", ""),
            deletedAt = j.optString("deletedAt", ""),
            size = j.optLong("size", 0)
        )
    }
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

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ===== 基础 WebDAV 操作 =====

    fun listDir(path: String): List<DavItem> {
        val url = buildUrl(path)
        val body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
            <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
            </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body)
            .header("Depth", "1").build()
        val xml = client.newCall(req).execute().body?.string() ?: return emptyList()
        return parsePropfind(xml, path).filter { !it.name.startsWith(".webdav_trash") }
    }

    fun permanentDelete(path: String): Boolean {
        val url = buildUrl(path)
        return client.newCall(Request.Builder().url(url).delete().build()).execute().isSuccessful
    }

    fun move(fromPath: String, toPath: String): Boolean {
        val fromUrl = buildUrl(fromPath)
        val destUrl = buildEncodedUrl(toPath)
        val req = Request.Builder().url(fromUrl)
            .method("MOVE", null)
            .header("Destination", destUrl)
            .header("Overwrite", "F")
            .build()
        return client.newCall(req).execute().isSuccessful
    }

    fun mkdir(path: String): Boolean {
        val url = buildUrl(path)
        val resp = client.newCall(
            Request.Builder().url(url).method("MKCOL", null).build()
        ).execute()
        return resp.isSuccessful || resp.code == 405 || resp.code == 301
    }

    fun mkdirs(path: String) {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        var cur = ""
        for (part in parts) {
            cur += "/$part"
            mkdir("$cur/")
        }
    }

    fun putText(path: String, content: String): Boolean {
        val url = buildUrl(path)
        val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
        return client.newCall(Request.Builder().url(url).put(body).build()).execute().isSuccessful
    }

    fun getText(path: String): String? {
        val url = buildUrl(path)
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    fun exists(path: String): Boolean {
        val url = buildUrl(path)
        return try {
            client.newCall(Request.Builder().url(url).head().build()).execute().isSuccessful
        } catch (_: Exception) { false }
    }

    fun fileUrl(path: String) = buildUrl(path)
    fun getClient() = client

    // ===== 回收站路径 =====

    private fun getDriveRoot(path: String): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) "/${parts[0]}" else ""
    }

    private fun trashDir(filePath: String): String {
        return "${getDriveRoot(filePath)}/.webdav_trash"
    }

    private fun manifestPath(filePath: String): String {
        return "${trashDir(filePath)}/manifest.json"
    }

    // ===== manifest.json 读写 =====

    private fun readManifest(filePath: String): MutableList<TrashEntry> {
        val json = getText(manifestPath(filePath)) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<TrashEntry>()
            for (i in 0 until arr.length()) {
                list.add(TrashEntry.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (_: Exception) { mutableListOf() }
    }

    private fun writeManifest(filePath: String, entries: List<TrashEntry>): Boolean {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        return putText(manifestPath(filePath), arr.toString(2))
    }

    // ===== 移到回收站 =====

    fun moveToTrash(filePath: String): Boolean {
        val dir = trashDir(filePath)
        mkdir("$dir/")

        val fileName = filePath.trimEnd('/').substringAfterLast('/')
        val id = UUID.randomUUID().toString().substring(0, 8)
        val storedName = "${id}_${fileName}"
        val trashFilePath = "$dir/$storedName"

        // 先移动文件
        val success = move(filePath, trashFilePath)
        if (!success) return false

        // 再更新清单
        val entries = readManifest(filePath)
        entries.add(
            TrashEntry(
                id = id,
                fileName = fileName,
                originalPath = filePath,
                deletedAt = dateFormat.format(Date()),
                size = 0  // 大小在列表页显示
            )
        )
        writeManifest(filePath, entries)
        return true
    }

    // ===== 从回收站还原 =====

    fun restoreFromTrash(entry: TrashEntry, anyFileInSameDrive: String): Pair<Boolean, String> {
        val dir = trashDir(anyFileInSameDrive)
        val trashFilePath = "$dir/${entry.storedName}"
        val originalPath = entry.originalPath

        if (originalPath.isBlank() || originalPath == "未知") {
            return Pair(false, "原始路径信息丢失，无法还原")
        }

        // 确保原始目录存在
        val parentDir = originalPath.substringBeforeLast('/')
        if (parentDir.isNotBlank() && parentDir != originalPath) {
            mkdirs(parentDir)
        }

        // 处理重名：检查目标是否存在，存在则加 _1 _2 ...
        var targetPath = originalPath
        if (exists(targetPath)) {
            val dirPart = originalPath.substringBeforeLast('/')
            val fullName = originalPath.substringAfterLast('/')
            val dotIdx = fullName.lastIndexOf('.')
            val baseName = if (dotIdx > 0) fullName.substring(0, dotIdx) else fullName
            val ext = if (dotIdx > 0) fullName.substring(dotIdx) else ""
            var counter = 1
            while (exists(targetPath)) {
                targetPath = "$dirPart/${baseName}_${counter}${ext}"
                counter++
                if (counter > 9999) return Pair(false, "重名文件过多，无法还原")
            }
        }

        // 移回原位
        val success = move(trashFilePath, targetPath)
        if (!success) return Pair(false, "移动失败")

        // 从清单中移除
        val entries = readManifest(anyFileInSameDrive)
        entries.removeAll { it.id == entry.id }
        writeManifest(anyFileInSameDrive, entries)

        val renamed = if (targetPath != originalPath) "（重名已改为 ${targetPath.substringAfterLast('/')}）" else ""
        return Pair(true, "已还原到 ${originalPath.substringBeforeLast('/')}/ $renamed")
    }

    // ===== 永久删除回收站中的单个文件 =====

    fun permanentDeleteTrashEntry(entry: TrashEntry, anyFileInSameDrive: String): Boolean {
        val dir = trashDir(anyFileInSameDrive)
        val trashFilePath = "$dir/${entry.storedName}"
        val success = permanentDelete(trashFilePath)
        if (success) {
            val entries = readManifest(anyFileInSameDrive)
            entries.removeAll { it.id == entry.id }
            writeManifest(anyFileInSameDrive, entries)
        }
        return success
    }

    // ===== 列出回收站 =====

    fun listTrash(currentPath: String): List<TrashEntry> {
        return readManifest(currentPath)
    }

    // ===== 清空回收站 =====

    fun emptyTrash(currentPath: String): Boolean {
        val dir = trashDir(currentPath)
        return permanentDelete("$dir/")
    }

    // ===== URL 构建 =====

    private fun buildUrl(path: String): String {
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun buildEncodedUrl(path: String): String {
        val segments = path.trimStart('/').split('/').map { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return baseUrl.trimEnd('/') + "/" + segments.joinToString("/")
    }

    // ===== XML 解析 =====

    private fun parsePropfind(xml: String, curPath: String): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val p = factory.newPullParser()
        p.setInput(StringReader(xml))
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
