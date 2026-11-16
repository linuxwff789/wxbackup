package com.nous.wxnotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        sameYear -> "MM-dd HH:mm"
        else -> "yyyy-MM-dd HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

fun formatDay(ms: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ms))

fun formatFull(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun mimeOf(kind: String, name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    val byExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (!byExt.isNullOrBlank()) return byExt
    return when (kind) {
        "image" -> "image/*"
        "voice" -> "audio/*"
        "video" -> "video/*"
        else -> "*/*"
    }
}

fun openLocalFile(context: Context, path: String, kind: String, name: String) {
    val file = File(path)
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "文件不存在：$path", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(kind, name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Throwable) {
        android.widget.Toast.makeText(
            context,
            "打开失败：${e.javaClass.simpleName} ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

fun shareLocalFile(context: Context, file: File, mime: String = "*/*") {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享文件")) }
        .onFailure { android.widget.Toast.makeText(context, "无法分享：${it.message}", android.widget.Toast.LENGTH_SHORT).show() }
}

/** 按媒体类型分享笔记里的附件（图片/视频/语音/文件都能分享）。 */
fun shareMedia(context: Context, path: String, kind: String, fileName: String) {
    val f = File(path)
    if (path.isBlank() || !f.isFile) {
        android.widget.Toast.makeText(context, "文件还没下载完", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val mime = when (kind) {
        com.nous.wxshared.ForwardProtocol.MEDIA_IMAGE -> "image/*"
        com.nous.wxshared.ForwardProtocol.MEDIA_VIDEO -> "video/*"
        com.nous.wxshared.ForwardProtocol.MEDIA_VOICE -> "audio/*"
        else -> mimeFromName(fileName.ifBlank { f.name })
    }
    shareLocalFile(context, f, mime)
}

private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "zip" -> "application/zip"
    "txt" -> "text/plain"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "amr" -> "audio/amr"
    "mp4" -> "video/mp4"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "md" -> "text/markdown"
    else -> "*/*"
}
