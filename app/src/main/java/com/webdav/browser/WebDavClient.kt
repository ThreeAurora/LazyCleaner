package com.webdav.browser

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder

data class DavItem(
    val name: String, val href: String, val isDir: Boolean,
    val size: Long = 0, val date: String = ""
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val isMedia get() = isImage || isVideo
}

class WebDavClient(private val baseUrl: String, user: String, pass: String) {
    private val client = OkHttpClient.Builder().apply {
        if (user.isNotBlank()) authenticator { _, resp ->
            resp.request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
        }
    }.build()

    fun listDir(path: String): List<DavItem> {
        val url = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
            <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
            </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").build()
        val xml = client.newCall(req).execute().body?.string() ?: return emptyList()
        return parse(xml, path)
    }

    fun delete(path: String): Boolean {
        val url = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        return client.newCall(Request.Builder().url(url).delete().build()).execute().isSuccessful
    }

    fun fileUrl(path: String) = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    fun getClient() = client

    private fun parse(xml: String, curPath: String): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true   // ← 关键修复！正确处理 D: 前缀
        val p = factory.newPullParser()
        p.setInput(StringReader(xml))

        var href = ""; var sz = 0L; var dt = ""; var isCol = false; var inR = false; var tag = ""

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    // 用 localName，自动去掉 D: 前缀
                    tag = p.name.lowercase()
                    if (tag == "response") {
                        inR = true; href = ""; sz = 0; dt = ""; isCol = false
                    }
                    if (tag == "collection") isCol = true
                }
                XmlPullParser.TEXT -> {
                    val text = p.text?.trim() ?: ""
                    if (inR && text.isNotEmpty()) {
                        when (tag) {
                            "href" -> href = text
                            "getcontentlength" -> sz = text.toLongOrNull() ?: 0
                            "getlastmodified" -> dt = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.lowercase() == "response" && inR) {
                        inR = false
                        val dec = try {
                            URLDecoder.decode(href, "UTF-8")
                        } catch (_: Exception) { href }

                        // 跳过当前目录自身
                        val normCur = "/" + curPath.trim('/') 
                        val normHref = dec.trimEnd('/')
                        val normCurTrimmed = normCur.trimEnd('/')

                        if (normHref != normCurTrimmed && dec.trimEnd('/') != "/" .trimEnd('/') || dec.trim('/').contains('/').not() && dec.trim('/').isNotEmpty() && curPath.trim('/').isEmpty()) {
                            // 简化判断：只要 href 不等于当前路径就是子项
                        }
                        
                        // 更可靠的判断方式
                        val isSelf = dec.trimEnd('/') == curPath.trimEnd('/') ||
                                     dec.trimEnd('/') == ("/" + curPath.trim('/')).trimEnd('/') ||
                                     dec.trimEnd('/').isEmpty() && curPath.trim('/').isEmpty()

                        if (!isSelf && href.isNotEmpty()) {
                            val name = dec.trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) {
                                items.add(DavItem(name, dec, isCol, sz, dt))
                            }
                        }
                    }
                    tag = ""
                }
            }
            p.next()
        }
        return items
    }
}
