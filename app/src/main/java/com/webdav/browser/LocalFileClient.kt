package com.webdav.browser

import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class LocalFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val isMedia get() = isImage || isVideo
    val isHidden get() = name.startsWith(".")
    val fileIcon get() = when {
        isDir -> "📁"; isImage -> "🖼️"; isVideo -> "🎬"
        ext in setOf("mp3","flac","wav","aac","ogg","m4a") -> "🎵"
        ext in setOf("pdf") -> "📕"
        ext in setOf("doc","docx","odt","rtf") -> "📝"
        ext in setOf("xls","xlsx","csv") -> "📊"
        ext in setOf("zip","rar","7z","tar","gz") -> "📦"
        ext in setOf("apk") -> "📲"
        ext in setOf("txt","log","md","json","xml") -> "📃"
        else -> "📄"
    }
}

class LocalFileClient {

    fun getDefaultRoot(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun listDir(path: String, showHidden: Boolean = false): List<LocalFile> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.isHidden }
            .map { f ->
                LocalFile(
                    name = f.name,
                    path = f.absolutePath,
                    isDir = f.isDirectory,
                    size = if (f.isFile) f.length() else 0,
                    lastModified = f.lastModified()
                )
            }
            .sortedWith(compareBy<LocalFile> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    fun delete(path: String): Boolean {
        val f = File(path)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    fun rename(path: String, newName: String): Boolean {
        val f = File(path)
        val target = File(f.parent, newName)
        if (target.exists()) return false
        return f.renameTo(target)
    }

    fun mkdir(path: String): Boolean {
        return File(path).mkdirs()
    }

    fun copyLocal(from: String, toDir: String): Boolean {
        val src = File(from)
        val dest = File(toDir, src.name)
        return try {
            if (src.isDirectory) src.copyRecursively(dest, overwrite = false)
            else src.copyTo(dest, overwrite = false)
            true
        } catch (_: Exception) { false }
    }

    fun moveLocal(from: String, toDir: String): Boolean {
        return if (copyLocal(from, toDir)) {
            File(from).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        } else false
    }

    /** 读取本地文件为字节数组（用于上传到 WebDAV） */
    fun readBytes(path: String): ByteArray? {
        return try { File(path).readBytes() } catch (_: Exception) { null }
    }

    /** 写入字节数组到本地文件（用于从 WebDAV 下载） */
    fun writeBytes(path: String, data: ByteArray): Boolean {
        return try {
            File(path).parentFile?.mkdirs()
            FileOutputStream(path).use { it.write(data) }
            true
        } catch (_: Exception) { false }
    }
}
