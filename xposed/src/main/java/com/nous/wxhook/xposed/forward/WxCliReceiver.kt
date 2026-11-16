package com.nous.wxhook.xposed.forward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XposedBridge

/**
 * 统一 CLI 调试入口（不依赖 UI 点击）：
 *
 *   su -c 'am broadcast -a com.nous.wxhook.CMD -p com.tencent.mm \
 *       --es cmd download_image --es svrId 123 --es talker xxx@chatroom'
 *
 * 支持:
 *   download_image  svrId talker               触发微信 DownloadImageService 下载原图
 *   download_file   attachId fileName msgId    触发微信 AppAttach 下载
 *   save_msg        msgId talker [title]       把指定消息保存到笔记
 *   dump_msg        msgId talker               输出消息字段（调试）
 *   show_img2       svrId                      输出 ImgInfo2 行
 *   log                                         输出最近日志行数
 *
 * 结果追加写到 /data/data/com.tencent.mm/files/wxhook_cli.log
 */
class WxCliReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val t0 = System.currentTimeMillis()
        val result = try {
            dispatch(context, cmd, intent)
        } catch (e: Throwable) {
            "EXCEPTION: ${e.message}"
        }
        WxDownloader.fileLog(
            context,
            "CLI cmd=$cmd ok=${!result.startsWith("EXCEPTION")} cost=${System.currentTimeMillis() - t0}ms result=$result"
        )
    }

    private fun dispatch(context: Context, cmd: String, intent: Intent): String {
        val loader = context.classLoader
        return when (cmd) {
            "download_image" -> {
                val svrId = intent.getStringExtra("svrId")?.toLongOrNull() ?: 0L
                val talker = intent.getStringExtra("talker").orEmpty()
                val ok = WxDownloader.downloadViaImageService(context, svrId, talker)
                val path = WxDownloader.findImgInfo2Original(context, svrId)
                "started=$ok path=$path"
            }

            "download_file" -> {
                val attachId = intent.getStringExtra("attachId").orEmpty()
                val name = intent.getStringExtra("fileName").orEmpty()
                val msgId = intent.getStringExtra("msgId")?.toLongOrNull() ?: 0L
                val talker = intent.getStringExtra("talker").orEmpty()
                val size = intent.getStringExtra("fileSize")?.toLongOrNull() ?: 0L
                val ok = WxDownloader.downloadFile(context, talker, attachId, name, size, msgId) { f ->
                    WxDownloader.fileLog(context, "CLI download_file result=${f?.absolutePath} (${f?.length()})")
                }
                "started=$ok"
            }

            "show_img2" -> {
                val svrId = intent.getStringExtra("svrId")?.toLongOrNull() ?: 0L
                WxDownloader.findImgInfo2Original(context, svrId) ?: "no row / not downloaded"
            }

            "dump_msg" -> {
                val msgId = intent.getStringExtra("msgId")?.toLongOrNull() ?: 0L
                val talker = intent.getStringExtra("talker").orEmpty()
                val mi = Class.forName("oy0.k0", false, loader)
                    .getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                    .invoke(null, talker, msgId)
                if (mi == null) "msg not found" else MessageExtractor.dump(mi)
            }

            "list_conv" -> {
                val limit = intent.getStringExtra("limit")?.toIntOrNull() ?: 30
                val rows = WeDb.query(
                    context,
                    "SELECT username, displayName, unReadCount, msgType, createTime FROM rconversation " +
                        "ORDER BY createTime DESC LIMIT $limit"
                )
                rows.joinToString("\n") { "${it["username"]} | ${it["displayName"]} | unread=${it["unReadCount"]}" }
            }

            "list_contacts" -> {
                val limit = intent.getStringExtra("limit")?.toIntOrNull() ?: 100
                val rows = WeDb.query(
                    context,
                    "SELECT username, nickname, remark, type FROM rcontact " +
                        "WHERE type != 4 ORDER BY nickname LIMIT $limit"
                )
                rows.joinToString("\n") { "${it["username"]} | ${it["nickname"]} | remark=${it["remark"]}" }
            }

            "get_messages" -> {
                val talker = intent.getStringExtra("talker").orEmpty()
                val limit = intent.getStringExtra("limit")?.toIntOrNull() ?: 20
                if (talker.isBlank()) {
                    "talker required"
                } else {
                    val rows = WeDb.query(
                        context,
                        "SELECT msgId, msgSvrId, type, createTime, isSend, substr(content,1,160) AS c " +
                            "FROM message WHERE talker=? ORDER BY createTime DESC LIMIT $limit",
                        arrayOf(talker)
                    )
                    rows.joinToString("\n") {
                        "id=${it["msgId"]} svr=${it["msgSvrId"]} type=${it["type"]} " +
                            "time=${it["createTime"]} send=${it["isSend"]} ${it["c"]}"
                    }
                }
            }

            "save_msg" -> {
                val msgId = intent.getStringExtra("msgId")?.toLongOrNull() ?: 0L
                val svrId = intent.getStringExtra("svrId")?.toLongOrNull() ?: 0L
                val talker = intent.getStringExtra("talker").orEmpty()
                val title = intent.getStringExtra("title").orEmpty()
                val mi = Class.forName("oy0.k0", false, loader)
                    .getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                    .invoke(null, talker, msgId)
                if (mi == null) {
                    "msg not found (msgId=$msgId talker=$talker)"
                } else {
                    val ok = ForwardSender.forward(context, mi, null, title)
                    "forwarded=$ok"
                }
            }

            else -> "unknown cmd=$cmd"
        }
    }

    companion object {
        const val ACTION = "com.nous.wxhook.CMD"
    }
}
