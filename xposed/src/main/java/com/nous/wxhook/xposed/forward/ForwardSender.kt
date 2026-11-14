package com.nous.wxhook.xposed.forward

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nous.wxshared.ForwardProtocol
import com.nous.wxshared.ForwardProtocol.Conversation
import com.nous.wxshared.ForwardProtocol.ForwardBatch
import com.nous.wxshared.ForwardProtocol.ForwardMessage
import com.nous.wxshared.WxMessageParser
import de.robv.android.xposed.XposedBridge
import java.io.File

/** 从微信内部 MsgInfo 对象提取字段并组装转发批次。 */
object ForwardSender {

    private const val TAG = "[wxhook:Fwd]"
    const val TARGET_PKG = "com.nous.wxnotes"

    fun forward(ctx: Context?, msgInfo: Any): Boolean {
        val ex = MessageExtractor.extract(msgInfo) ?: run {
            XposedBridge.log("$TAG extract failed: ${msgInfo.javaClass.name}")
            return false
        }
        if (ex.talker.isBlank()) {
            XposedBridge.log("$TAG talker empty, skip")
            return false
        }

        val isGroup = ex.talker.endsWith("@chatroom")
        var content = ex.content
        var sender = ""
        if (isGroup) {
            // 群消息 content 可能以 "wxid_xxx:\n" 开头
            val m = Regex("^([A-Za-z0-9_-]{4,64}):\\s*").find(content)
            if (m != null) {
                sender = m.groupValues[1]
                content = content.substring(m.range.last + 1)
            }
        }

        val parsed = WxMessageParser.parse(ex.type, content)
        var mediaKind = parsed.mediaKind
        var mediaUrl = ""
        val localMedia = ex.imgPath
        if (localMedia.isNotBlank() && File(localMedia).isFile) {
            MediaServer.urlFor(localMedia)?.let { mediaUrl = it }
            if (mediaKind == ForwardProtocol.MEDIA_NONE) mediaKind = guessKind(localMedia)
        }

        val createTime = if (ex.createTime > 10_000_000_000L) ex.createTime / 1000 else ex.createTime
        val fwd = ForwardMessage(
            msgId = ex.msgId,
            msgSvrId = ex.msgSvrId,
            type = ex.type,
            subType = parsed.subType,
            createTime = createTime,
            isSend = ex.isSend,
            sender = sender,
            senderName = "",
            text = parsed.text,
            rawXml = content.take(200_000),
            mediaKind = mediaKind,
            localPath = mediaUrl,
            fileName = parsed.fileName,
            fileSize = parsed.fileSize,
            durationMs = parsed.durationMs
        )

        val batch = ForwardBatch(
            pluginVersion = "1.0.0",
            conversation = Conversation(
                talker = ex.talker,
                title = ex.talker,
                isGroup = isGroup
            ),
            messages = listOf(fwd)
        )
        val json = batch.encode()

        var delivered = false
        try {
            val intent = Intent(ForwardProtocol.ACTION_RECEIVE_BATCH).apply {
                setPackage(TARGET_PKG)
                putExtra(ForwardProtocol.EXTRA_BATCH_JSON, json)
                putExtra(ForwardProtocol.EXTRA_SOURCE, ForwardProtocol.SOURCE_WECHAT)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx?.sendBroadcast(intent)
            delivered = ctx != null
        } catch (e: Throwable) {
            XposedBridge.log("$TAG sendBroadcast failed: ${e.message}")
        }

        // outbox：供第三方 App 或失败重试读取（没有存储权限时忽略）
        try {
            val dir = File(ForwardProtocol.DEFAULT_OUTBOX_DIR)
            if (dir.exists() || dir.mkdirs()) {
                File(dir, "${batch.batchId}.json").writeText(json)
            }
        } catch (_: Throwable) {
        }

        // 媒体服务器保活
        MediaServer.ensureStarted()

        XposedBridge.log(
            "$TAG forwarded type=${ex.type} sub=${parsed.subType} msgId=${ex.msgId} " +
                "talker=${ex.talker} media=$mediaKind url=${mediaUrl.take(80)} ok=$delivered"
        )
        toast(ctx, "已保存到笔记：${parsed.text.take(24)}")
        return true
    }

    private fun guessKind(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> ForwardProtocol.MEDIA_IMAGE
            "amr", "silk", "mp3", "m4a", "aac", "ogg", "wav" -> ForwardProtocol.MEDIA_VOICE
            "mp4", "mov", "mkv", "3gp", "avi" -> ForwardProtocol.MEDIA_VIDEO
            else -> ForwardProtocol.MEDIA_FILE
        }
    }

    private fun toast(ctx: Context?, text: String) {
        if (ctx == null) return
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
        }
    }
}
