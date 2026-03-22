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
    val isMedia get() = isImage || isVideo
}

/**
 * 回收站条目
 * id 和 fileName 从回收站文件名 "{id}_{fileName}" 解析得到
 * originalPath 只在需要时（还原）才从 .trashinfo 文件读取（懒加载）
 */
data class TrashEntry(
    val id: String,
    val fileName: String,
    val trashHref: String,       // 回收站中数据文件的完整路径
    val size: Long = 0,
    val date: String = "",
    val originalPath: String = "" // 懒加载，列出时可能为空
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

    // ===== 基础 WebDAV 操作 =====

    fun listDir(path: String): List<DavItem> {
        val url = buildUrl(path)
        val body = propfindBody()
        val req = Request.Builder().url(url).method("PROPFIND", body)
            .header("Depth", "1").build()
        val xml = client.newCall(req).execute().body?.string() ?: return emptyList()
        return parsePropfind(xml, path).filter { !it.name.startsWith(".webdav_trash") }
    }

    fun permanentDelete(path: String): Boolean {
        return client.newCall(Request.Builder().url(buildUrl(path)).delete().build())
            .execute().isSuccessful
    }

    fun move(fromPath: String, toPath: String): Boolean {
        val req = Request.Builder().url(buildUrl(fromPath))
            .method("MOVE", null)
            .header("Destination", buildEncodedUrl(toPath))
            .header("Overwrite", "F")
            .build()
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
        for (part in parts) {
            cur += "/$part"
            mkdir("$cur/")
        }
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

    fun fileUrl(path: String) = buildUrl(path)
    fun getClient() = client

    // ===== 回收站路径计算 =====

    private fun getDriveRoot(path: String): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) "/${parts[0]}" else ""
    }

    private fun trashDir(filePath: String): String {
        return "${getDriveRoot(filePath)}/.webdav_trash"
    }

    // ===== 移到回收站（O(1)，不读不写 JSON） =====

    fun moveToTrash(filePath: String): Boolean {
        val dir = trashDir(filePath)
        mkdir("$dir/")

        val fileName = filePath.trimEnd('/').substringAfterLast('/')
        val id = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        val storedName = "${id}_${fileName}"
        val trashFilePath = "$dir/$storedName"
        val infoPath = "$trashFilePath.trashinfo"

        // 1. 先写 trashinfo（只有一行：原始路径）
        if (!putText(infoPath, filePath)) return false

        // 2. 移动文件
        val success = move(filePath, trashFilePath)
        if (!success) {
            permanentDelete(infoPath) // 移动失败则清理
        }
        return success
    }

    // ===== 列出回收站（O(1)，一次 PROPFIND） =====

    fun listTrash(currentPath: String): List<TrashEntry> {
        val dir = trashDir(currentPath)
        val url = buildUrl("$dir/")
        val req = Request.Builder().url(url).method("PROPFIND", propfindBody())
            .header("Depth", "1").build()
        val resp = try { client.newCall(req).execute() } catch (_: Exception) { return emptyList() }
        if (!resp.isSuccessful) return emptyList()
        val xml = resp.body?.string() ?: return emptyList()

        val allItems = parsePropfind(xml, "$dir/")

        // 只保留数据文件（排除 .trashinfo 和文件夹）
        val dataFiles = allItems.filter { !it.name.endsWith(".trashinfo") && !it.isDir }

        return dataFiles.mapNotNull { item ->
            // 文件名格式: {8位id}_{原始文件名}
            val underscoreIdx = item.name.indexOf('_')
            if (underscoreIdx < 1) return@mapNotNull null

            val id = item.name.substring(0, underscoreIdx)
            val fileName = item.name.substring(underscoreIdx + 1)
            if (fileName.isBlank()) return@mapNotNull null

            TrashEntry(
                id = id,
                fileName = fileName,
                trashHref = item.href,
                size = item.size,
                date = item.date
                // originalPath 暂不加载，等用户点还原时再读
            )
        }
    }

    // ===== 读取原始路径（只在还原时调用） =====

    private fun readOriginalPath(entry: TrashEntry): String? {
        val infoPath = "${entry.trashHref}.trashinfo"
        return getText(infoPath)?.trim()
    }

    // ===== 从回收站还原（O(1)） =====

    fun restoreFromTrash(entry: TrashEntry): Pair<Boolean, String> {
        // 1. 读取原始路径
        val originalPath = readOriginalPath(entry)
            ?: return Pair(false, "找不到原始路径信息（.trashinfo 丢失）")

        if (originalPath.isBlank()) return Pair(false, "原始路径为空")

        // 2. 确保原始目录存在
        val parentDir = originalPath.substringBeforeLast('/')
        if (parentDir.isNotBlank() && parentDir != originalPath) {
            mkdirs(parentDir)
        }

        // 3. 处理重名
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
                if (counter > 9999) return Pair(false, "重名文件过多")
            }
        }

        // 4. 移回原位
        val success = move(entry.trashHref, targetPath)
        if (!success) return Pair(false, "移动失败")

        // 5. 删除 .trashinfo
        permanentDelete("${entry.trashHref}.trashinfo")

        val renamed = if (targetPath != originalPath) {
            "（已重命名为 ${targetPath.substringAfterLast('/')}）"
        } else ""
        return Pair(true, "已还原到 ${parentDir}/ $renamed")
    }

    // ===== 永久删除回收站单个文件（O(1)） =====

    fun permanentDeleteTrashEntry(entry: TrashEntry): Boolean {
        val a = permanentDelete(entry.trashHref)
        val b = permanentDelete("${entry.trashHref}.trashinfo")
        return a
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

    private fun propfindBody() = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
        <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
        </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())

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
