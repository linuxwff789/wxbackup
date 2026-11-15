package com.nous.wxhook.xposed.forward

import android.content.Context
import android.content.Intent
import com.nous.wxshared.ForwardProtocol
import com.nous.wxshared.ForwardProtocol.Conversation
import com.nous.wxshared.ForwardProtocol.ForwardBatch
import com.nous.wxshared.ForwardProtocol.ForwardMessage
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 无感下载微信图片原图。
 *
 * 走微信自己的 NetSceneGetMsgImg（v61.t0）+ NetSceneQueue（l51.i9.e()），
 * 直接入队，不启动任何 Activity，进度/完成通过 v0.Q 回调。
 * 下载完成后文件落在 image2/<md5[0:2]>/<md5[2:4]>/<md5>.jpg。
 */
object WxDownloader {

    private const val TAG = "[wxhook:DL]"
    private val downloading = ConcurrentHashMap<String, Boolean>()

    /**
     * @param talker 会话 id（群是 xxx@chatroom）
     * @param md5    图片 md5（来自消息 XML）
     * @param onDone 下载完成后回调（后台线程），文件不存在则回调 null
     */
    fun downloadImage(
        ctx: Context?,
        talker: String,
        msgId: Long,
        msgSvrId: Long,
        md5: String,
        onDone: (File?) -> Unit
    ): Boolean {
        if (ctx == null || talker.isBlank()) return false
        val key = "$talker|$msgId|$md5"
        if (downloading.putIfAbsent(key, true) != null) return false
        return try {
            val loader = ctx.classLoader
            val b1 = Class.forName("v61.b1", false, loader)
            val storage = b1.getMethod("Bj").invoke(null)

            var info: Any? = null
            if (msgId > 0) {
                info = runCatching {
                    storage!!.javaClass.getMethod("W2", String::class.java, java.lang.Long.TYPE)
                        .invoke(storage, talker, msgId)
                }.getOrNull()
            }
            if (info == null && msgSvrId > 0) {
                info = runCatching {
                    storage!!.javaClass.getMethod("k3", String::class.java, java.lang.Long.TYPE)
                        .invoke(storage, talker, msgSvrId)
                }.getOrNull()
            }
            if (info == null) {
                XposedBridge.log("$TAG no ImgInfo talker=$talker msgId=$msgId svr=$msgSvrId")
                downloading.remove(key)
                return false
            }

            val imgId = fieldLong(info, "f479445a")
            val v0 = Class.forName("com.tencent.mm.modelbase.v0", false, loader)
            val progress = Proxy.newProxyInstance(loader, arrayOf(v0)) { _, method, args ->
                if (method.name == "Q" && args != null && args.size >= 2) {
                    val total = (args[0] as? Number)?.toLong() ?: 0L
                    val current = (args[1] as? Number)?.toLong() ?: 0L
                    if (total > 0 && current >= total) {
                        XposedBridge.log("$TAG download progress done imgId=$imgId")
                    }
                }
                null
            }

            val t0 = Class.forName("v61.t0", false, loader)
            val ctor = t0.getConstructor(
                java.lang.Long.TYPE, java.lang.Long.TYPE, String::class.java, java.lang.Integer.TYPE, v0
            )
            // compressType=0：原图
            val task = ctor.newInstance(imgId, msgId, talker, 0, progress)
            val queue = Class.forName("l51.i9", false, loader).getMethod("e").invoke(null)!!
            val n1 = Class.forName("com.tencent.mm.modelbase.n1", false, loader)
            queue.javaClass.getMethod("g", n1).invoke(queue, task)
            XposedBridge.log("$TAG enqueued img download imgId=$imgId msgId=$msgId md5=$md5")

            Thread({
                val f = waitForFile(md5, 40_000)
                downloading.remove(key)
                if (f != null) {
                    XposedBridge.log("$TAG downloaded ${f.absolutePath} (${f.length()})")
                } else {
                    XposedBridge.log("$TAG download timeout md5=$md5")
                }
                onDone(f)
            }, "wxnotes-dl-$md5").apply { isDaemon = true }.start()
            true
        } catch (e: Throwable) {
            downloading.remove(key)
            XposedBridge.log("$TAG enqueue failed: ${e.message}")
            false
        }
    }

    private fun fieldLong(obj: Any, name: String): Long {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.getLong(obj)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return -1L
    }

    private fun waitForFile(md5: String, timeoutMs: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findImage(md5)?.let { return it }
            Thread.sleep(350)
        }
        return findImage(md5)
    }

    private fun findImage(md5: String): File? {
        if (md5.length < 4) return null
        val root = File("/data/data/com.tencent.mm/MicroMsg")
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val base = File(dir, "image2/${md5.substring(0, 2)}/${md5.substring(2, 4)}")
            if (!base.isDirectory) return@forEach
            listOf("jpg", "png", "gif", "webp", "heic").forEach { ext ->
                val f = File(base, "$md5.$ext")
                if (f.isFile && f.length() > 0) return f
            }
            base.listFiles()?.firstOrNull { it.name.startsWith(md5) && it.length() > 0 }
                ?.let { return it }
        }
        return null
    }

    /** 下载完成后把大图作为同一消息的更新发回笔记端。 */
    fun publishUpdate(
        ctx: Context?,
        talker: String,
        talkerTitle: String,
        isSend: Boolean,
        msgId: Long,
        msgSvrId: Long,
        createTime: Long,
        type: Int,
        fileName: String,
        file: File
    ) {
        if (ctx == null) return
        try {
            val msgs = ArrayList<Pair<ForwardMessage, File>>()
            val len = file.length()
            val msg = ForwardMessage(
                msgId = msgId,
                msgSvrId = msgSvrId,
                type = if (type > 0) type else 3,
                createTime = createTime,
                isSend = isSend,
                text = "[图片]",
                mediaKind = ForwardProtocol.MEDIA_IMAGE,
                fileName = fileName.ifBlank { file.name },
                fileSize = len
            )
            if (len <= 600 * 1024) {
                val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                msgs.add(msg.copy(mediaBase64 = b64) to file)
            } else {
                msgs.add(msg to file)
            }
            val batch = ForwardBatch(
                pluginVersion = "1.0.3",
                conversation = Conversation(talker, talkerTitle.ifBlank { talker }, talker.endsWith("@chatroom")),
                messages = msgs.map { it.first }
            )
            ctx.sendBroadcast(
                Intent(ForwardProtocol.ACTION_RECEIVE_BATCH).apply {
                    setPackage(ForwardSender.TARGET_PKG)
                    putExtra(ForwardProtocol.EXTRA_BATCH_JSON, batch.encode())
                    putExtra(ForwardProtocol.EXTRA_SOURCE, ForwardProtocol.SOURCE_WECHAT)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
            )
            msgs.forEach { (m, f) ->
                if (m.mediaBase64.isEmpty()) {
                    ForwardSender.sendChunks(
                        ctx,
                        Conversation(talker, talkerTitle.ifBlank { talker }, talker.endsWith("@chatroom")),
                        m,
                        f,
                        buildString { append(talker).append('|').append(m.effectiveDedupeKey()) }
                    )
                }
            }
            XposedBridge.log("$TAG published update msgId=$msgId size=$len")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG publishUpdate failed: ${e.message}")
        }
    }
}
