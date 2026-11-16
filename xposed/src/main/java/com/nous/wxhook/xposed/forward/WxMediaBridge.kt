package com.nous.wxhook.xposed.forward

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 大媒体直传：写进笔记 App 的 MediaInboxProvider（content://com.nous.wxnotes.inbox/<name>）。
 *
 * 广播受 Binder 1MB 限制，分片在笔记进程被冻结时容易整包丢失；
 * ContentProvider 是同步文件流写入，无大小限制、不占广播带宽。
 * 失败（provider 不存在 / 权限被拒）时返回 null，调用方回退到分片广播。
 */
object WxMediaBridge {
    private const val AUTHORITY = "com.nous.wxnotes.inbox"

    fun upload(ctx: Context?, file: File?): String? {
        if (ctx == null || file == null || !file.isFile || file.length() <= 0) return null
        return try {
            val safeName = file.name.replace('/', '_').replace('\\', '_')
            val name = "${System.currentTimeMillis()}_$safeName"
            val uri = Uri.parse("content://$AUTHORITY/$name")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: return null
            WxDownloader.fileLog(ctx, "inbox upload ok ${file.name} ${file.length()}B -> $name")
            uri.toString()
        } catch (e: Throwable) {
            WxDownloader.fileLog(
                ctx,
                "inbox upload failed ${file.name}: ${e.javaClass.simpleName} ${e.message}"
            )
            null
        }
    }
}
