package com.nous.wxhook.xposed.forward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XposedBridge
import java.io.File

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
                val mi = WxClasses.msgInfo(context, talker, msgId)
                if (mi == null) "msg not found" else MessageExtractor.dump(mi)
            }

            "sdcard_test" -> {
                // 测试微信能否通过 MediaStore 写公共 Download 目录（Android 10+ scoped storage）
                val name = "wxnotes_test_${System.currentTimeMillis()}.txt"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/wxnotes")
                }
                try {
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri == null) {
                        "insert failed"
                    } else {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(ByteArray(200 * 1024) { i -> (i % 251).toByte() })
                        }
                        "wrote uri=$uri"
                    }
                } catch (e: Throwable) {
                    "error ${e.javaClass.simpleName} ${e.message}"
                }
            }

            "inbox_test" -> {
                val f = File(context.cacheDir, "inbox_test.bin")
                f.writeBytes(ByteArray(200 * 1024) { (it % 251).toByte() })
                val up = WxMediaBridge.upload(context, f)
                "uploaded=$up size=${f.length()}"
            }

            "codec_test" -> {
                val sb = StringBuilder()
                val names = listOf(
                    "video/hevc",
                    "c2.android.hevc.decoder",
                    "OMX.google.hevc.decoder",
                    "c2.qti.hevc.decoder"
                )
                for (name in names) {
                    sb.append("--- $name\n")
                    var c: android.media.MediaCodec? = null
                    try {
                        c = if (name.contains('/')) {
                            android.media.MediaCodec.createDecoderByType(name)
                        } else {
                            android.media.MediaCodec.createByCodecName(name)
                        }
                        sb.append("created=${c.name}\n")
                        val fmt = android.media.MediaFormat.createVideoFormat("video/hevc", 952, 576)
                        fmt.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                        c.configure(fmt, null, null, 0)
                        sb.append("configure ok\n")
                        c.start()
                        sb.append("start ok\n")
                        val i = c.dequeueInputBuffer(1000)
                        sb.append("dequeueInput=$i\n")
                    } catch (e: Throwable) {
                        sb.append("ERR ${e.javaClass.simpleName} ${e.message}\n")
                    } finally {
                        try {
                            c?.stop()
                        } catch (_: Throwable) {
                        }
                        try {
                            c?.release()
                        } catch (_: Throwable) {
                        }
                    }
                }
                sb.toString()
            }

            "download_original" -> {
                val svrId = intent.getStringExtra("svrId")?.toLongOrNull() ?: 0L
                val talker = intent.getStringExtra("talker").orEmpty()
                if (svrId <= 0 || talker.isBlank()) {
                    "svrId/talker required"
                } else {
                    val started = WxDownloader.downloadOriginal(context, talker, svrId, 0L) { f ->
                        WxDownloader.fileLog(
                            context,
                            "download_original done svr=$svrId -> ${f?.absolutePath}(${f?.length()})"
                        )
                    }
                    "started=$started"
                }
            }

            "transcode" -> {
                val path = intent.getStringExtra("path").orEmpty()
                val src = File(path)
                if (path.isBlank() || !src.isFile) {
                    "path not found: $path"
                } else {
                    val probe = WxImageTranscoder.isWxContainer(src)
                    val out = WxImageTranscoder.transcode(context, src)
                    "wxContainer=$probe -> ${out?.absolutePath ?: "null"} (${out?.length() ?: 0}B)"
                }
            }

            "exec_sql" -> {
                val sql = intent.getStringExtra("sql").orEmpty()
                if (sql.isBlank()) "sql required" else "exec=${WeDb.execSql(context, sql)}"
            }

            "raw_sql" -> {
                val sql = intent.getStringExtra("sql").orEmpty()
                if (sql.isBlank()) {
                    "sql required"
                } else {
                    WeDb.query(context, sql).joinToString("\n") { it.toString() }
                }
            }

            "list_conv" -> {
                val limit = intent.getStringExtra("limit")?.toIntOrNull() ?: 30
                val rows = WeDb.query(
                    context,
                    "SELECT c.username, c.unReadCount, c.msgType, c.conversationTime, c.digest, " +
                        "r.nickname, r.conRemark FROM rconversation c " +
                        "LEFT JOIN rcontact r ON r.username = c.username " +
                        "ORDER BY c.conversationTime DESC LIMIT $limit"
                )
                rows.joinToString("\n") {
                    "${it["username"]} | ${it["nickname"]} | remark=${it["conRemark"]} | " +
                        "unread=${it["unReadCount"]} | ${it["digest"]}"
                }
            }

            "list_contacts" -> {
                val limit = intent.getStringExtra("limit")?.toIntOrNull() ?: 100
                val rows = WeDb.query(
                    context,
                    "SELECT username, nickname, conRemark, type FROM rcontact " +
                        "WHERE type != 4 ORDER BY nickname LIMIT $limit"
                )
                rows.joinToString("\n") { "${it["username"]} | ${it["nickname"]} | remark=${it["conRemark"]}" }
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

            "save_list" -> {
                val talker = intent.getStringExtra("talker").orEmpty()
                val title = intent.getStringExtra("title").orEmpty()
                val ids = intent.getStringExtra("msgIds").orEmpty()
                    .split(",").mapNotNull { it.trim().toLongOrNull() }
                if (talker.isBlank() || ids.isEmpty()) {
                    "talker/msgIds required"
                } else {
                    val list = ids.mapNotNull { id ->
                        WxClasses.msgInfo(context, talker, id)
                    }
                    val n = ForwardSender.forwardList(context, list, title)
                    "saved=$n requested=${ids.size} resolved=${list.size}"
                }
            }

            "save_msg" -> {
                val msgId = intent.getStringExtra("msgId")?.toLongOrNull() ?: 0L
                val svrId = intent.getStringExtra("svrId")?.toLongOrNull() ?: 0L
                val talker = intent.getStringExtra("talker").orEmpty()
                val title = intent.getStringExtra("title").orEmpty()
                val mi = WxClasses.msgInfo(context, talker, msgId)
                if (mi == null) {
                    "msg not found (msgId=$msgId talker=$talker)"
                } else {
                    val ok = ForwardSender.forward(context, mi, null, title)
                    "forwarded=$ok"
                }
            }

            "find_class" -> {
                val raw = intent.getStringExtra("strings").orEmpty()
                val keys = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (keys.isEmpty()) {
                    "strings required (a|b|c)"
                } else {
                    val list = WxClasses.findClasses(context, *keys.toTypedArray())
                    if (list.isEmpty()) "no class matched" else
                        list.take(15).joinToString("; ") { "${it.first}(${it.second}m)" }
                }
            }

            "dump_class" -> {
                val name = intent.getStringExtra("class").orEmpty()
                if (name.isBlank()) {
                    "class required"
                } else {
                    val cl = runCatching { Class.forName(name, false, loader) }.getOrNull()
                        ?: return "class not found: $name"
                    val sb = StringBuilder()
                    sb.append("class=").append(name).append("\n")
                    runCatching { cl.superclass?.name }.getOrNull()?.let { sb.append("extends ").append(it).append('\n') }
                    for (m in cl.declaredMethods.sortedBy { it.name }) {
                        sb.append(if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "S " else "  ")
                            .append(m.name).append('(')
                            .append(m.parameterTypes.joinToString(",") { it.name })
                            .append(") -> ").append(m.returnType.name).append('\n')
                    }
                    for (f in cl.declaredFields) {
                        sb.append(if (java.lang.reflect.Modifier.isStatic(f.modifiers)) "S " else "  ")
                            .append("field ").append(f.name).append(": ").append(f.type.name).append('\n')
                    }
                    val text = sb.toString()
                    WxDownloader.fileLog(context, "DUMP $name\n$text")
                    "dumped ${text.length} chars to log"
                }
            }

            "invoke" -> {
                val name = intent.getStringExtra("class").orEmpty()
                val method = intent.getStringExtra("method").orEmpty()
                val argsRaw = intent.getStringExtra("args").orEmpty()
                if (name.isBlank() || method.isBlank()) {
                    "class/method required"
                } else {
                    val cl = runCatching { Class.forName(name, false, loader) }.getOrNull()
                        ?: return "class not found: $name"
                    val args = if (argsRaw.isEmpty()) emptyArray() else argsRaw.split("|").toTypedArray()
                    val cands = cl.declaredMethods.filter { it.name == method && it.parameterCount == args.size }
                    if (cands.isEmpty()) return "no method $method/${args.size}"
                    var lastErr = ""
                    for (m in cands) {
                        m.isAccessible = true
                        val conv = args.mapIndexed { i, a ->
                            val t = m.parameterTypes[i]
                            when {
                                t == java.lang.Long.TYPE || t == java.lang.Long::class.java -> a.toLong()
                                t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java -> a.toInt()
                                t == java.lang.Boolean.TYPE -> a.toBoolean()
                                else -> a
                            }
                        }.toTypedArray()
                        val r = runCatching { m.invoke(null, *conv) }
                        if (r.isSuccess) return "ok -> ${r.getOrNull()}"
                        lastErr = r.exceptionOrNull()?.let { (it.cause ?: it).toString() }.orEmpty()
                    }
                    "failed: $lastErr"
                }
            }

            else -> "unknown cmd=$cmd"
        }
    }

    companion object {
        const val ACTION = "com.nous.wxhook.CMD"
    }
}
