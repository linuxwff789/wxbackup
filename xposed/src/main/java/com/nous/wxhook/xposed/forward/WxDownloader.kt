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
            if ((info == null || fieldLong(info, "f479445a") <= 0) && msgSvrId > 0) {
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

            runCatching {
                val ziCls = Class.forName("oy0.k0", false, loader)
                val zi = ziCls.getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                zi.isAccessible = true
                val mi = zi.invoke(null, talker, msgId)
                if (mi != null) {
                    val gid = mi.javaClass.getMethod("getMsgId").invoke(mi)
                    val gsvr = mi.javaClass.getMethod("getMsgSvrId").invoke(mi)
                    val gtalk = mi.javaClass.getMethod("getTalker").invoke(mi)
                    XposedBridge.log("$TAG MsgInfo id=$gid svr=$gsvr talker=$gtalk")
                } else {
                    XposedBridge.log("$TAG MsgInfo null (Zi)")
                }
            }.onFailure { XposedBridge.log("$TAG Zi failed: ${it.message}") }

            val imgId = fieldLong(info, "f479445a")
            XposedBridge.log("$TAG ImgInfo=${info.javaClass.name} imgId=$imgId talker=$talker msgId=$msgId svr=$msgSvrId")
            if (imgId <= 0) {
                XposedBridge.log("$TAG imgId invalid, abort")
                downloading.remove(key)
                onDone(null)
                return false
            }
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
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            XposedBridge.log("$TAG enqueue failed: " + sw.toString().take(1500))
            false
        }
    }

    /** 不依赖 ImgInfo：直接走消息里的 cdnbigimgurl + aeskey 下载原图。 */
    fun downloadImageByCdn(
        ctx: Context?,
        url: String,
        aesKey: String,
        md5: String,
        onDone: (File?) -> Unit
    ) {
        if (ctx == null || url.isBlank()) {
            onDone(null)
            return
        }
        Thread({
            var f: File? = null
            try {
                val full = if (url.startsWith("http")) url else "https://$url"
                XposedBridge.log("$TAG cdn fetch ${full.take(90)} key=${aesKey.take(8)}…")
                val conn = java.net.URL(full).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 40000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MicroMessenger/8.0.79")
                conn.setRequestProperty("Accept", "*/*")
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes() } ?: ByteArray(0)
                if (raw.isEmpty()) {
                    XposedBridge.log("$TAG cdn empty http=$code")
                } else {
                    val data = if (aesKey.length >= 32) aesDecrypt(raw, aesKey) else raw
                    val ext = when {
                        data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "jpg"
                        data.size > 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "png"
                        data.size > 3 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() -> "gif"
                        data.size > 12 && data.toString(Charsets.ISO_8859_1).startsWith("RIFF") -> "webp"
                        else -> "jpg"
                    }
                    val out = File(ctx.cacheDir, "wxdl_${md5.ifBlank { System.currentTimeMillis().toString() }}.$ext")
                    out.writeBytes(data)
                    f = out
                    XposedBridge.log("$TAG cdn saved ${out.name} raw=${raw.size} dec=${data.size} http=$code")
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG cdn failed: ${e.message}")
            }
            onDone(f)
        }, "wxnotes-cdn").apply { isDaemon = true }.start()
    }

    private fun aesDecrypt(data: ByteArray, hexKey: String): ByteArray {
        val key = ByteArray(hexKey.length / 2) { i ->
            ((Character.digit(hexKey[i * 2], 16) shl 4) or Character.digit(hexKey[i * 2 + 1], 16)).toByte()
        }
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES")
        )
        return cipher.doFinal(data)
    }

    /**
     * 用微信自己的 CDN 引擎下载并解密媒体（FkWeChat 同款路径）：
     * CDNTaskInfo -> w12.a.A2() -> s12.d.g().c(task) -> mars cdn
     */
    fun downloadCdn(
        ctx: Context?,
        fileKey: String,
        fileId: String,
        aesKey: String,
        fileType: Int,
        savePath: String,
        onDone: (File?) -> Unit
    ): Boolean {
        if (ctx == null || fileId.isBlank()) {
            onDone(null)
            return false
        }
        return try {
            val loader = ctx.classLoader
            val svcCls = Class.forName(
                "com.tencent.mm.plugin.cdndownloader.service.CDNDownloadService", false, loader
            )
            val svc = svcCls.getDeclaredConstructor().newInstance()
            val aCls = Class.forName("w12.a", false, loader)
            val binder = aCls.getConstructor(svcCls).newInstance(svc)
            val infoCls = Class.forName(
                "com.tencent.mm.plugin.cdndownloader.ipc.CDNTaskInfo", false, loader
            )
            val info = infoCls.getConstructor(String::class.java).newInstance(fileKey)
            de.robv.android.xposed.XposedHelpers.setObjectField(info, "f92137f", fileId)
            de.robv.android.xposed.XposedHelpers.setObjectField(info, "f92138g", savePath)
            de.robv.android.xposed.XposedHelpers.setObjectField(info, "f92139h", aesKey)
            de.robv.android.xposed.XposedHelpers.setIntField(info, "f92141m", fileType)
            val code = de.robv.android.xposed.XposedHelpers.callMethod(binder, "A2", info) as? Int ?: -1
            XposedBridge.log("$TAG cdn task enqueued code=$code key=$fileKey file=${fileId.take(48)} type=$fileType")
            Thread({
                val f = waitPath(savePath, 60_000)
                XposedBridge.log("$TAG cdn task result ${f?.absolutePath} (${f?.length()})")
                onDone(f)
            }, "wxnotes-cdn-$fileKey").apply { isDaemon = true }.start()
            code >= 0
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            XposedBridge.log("$TAG cdn task failed: " + sw.toString().take(1200))
            onDone(null)
            false
        }
    }

    private fun waitPath(path: String, timeoutMs: Long): File? {
        val f = File(path)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (f.isFile && f.length() > 0) return f
            Thread.sleep(300)
        }
        return if (f.isFile && f.length() > 0) f else null
    }

    /** 文件/附件：调微信 AppAttach 服务触发下载，轮询 attachment 目录。 */
    fun downloadFile(
        ctx: Context?,
        talker: String,
        attachId: String,
        fileName: String,
        expectedSize: Long,
        msgId: Long,
        onDone: (File?) -> Unit
    ): Boolean {
        if (ctx == null || attachId.isBlank()) return false
        val key = "file|$talker|$attachId"
        if (downloading.putIfAbsent(key, true) != null) return false
        return try {
            val loader = ctx.classLoader
            val c4 = Class.forName("mp3.c4", false, loader)
            val inst = Class.forName("pq0.i1", false, loader)
                .getMethod("v", Class::class.java).invoke(null, c4)
            val jj = inst!!.javaClass.getMethod("Jj").invoke(inst)
            val ud = jj!!.javaClass.methods.firstOrNull {
                it.name == "Ud" && it.parameterCount == 2
            } ?: throw NoSuchMethodException("Ud")
            val p2 = ud.parameterTypes[1]
            val msgInfo = if (p2.name == "com.tencent.mm.storage.e9" && msgId > 0) {
                Class.forName("oy0.k0", false, loader)
                    .getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                    .invoke(null, talker, msgId)
            } else if (p2 == String::class.java) {
                attachId
            } else null
            XposedBridge.log("$TAG Ud param2=${p2.name} msgInfo=${msgInfo != null}")
            ud.invoke(jj, talker, msgInfo)
            XposedBridge.log("$TAG enqueued file download attachId=${attachId.take(40)} name=$fileName")
            Thread({
                val f = waitForAttachment(fileName, expectedSize, 60_000)
                downloading.remove(key)
                XposedBridge.log("$TAG file download result: ${f?.absolutePath} (${f?.length()})")
                onDone(f)
            }, "wxnotes-filedl").apply { isDaemon = true }.start()
            true
        } catch (e: Throwable) {
            downloading.remove(key)
            XposedBridge.log("$TAG file enqueue failed: ${e.message}")
            false
        }
    }

    private fun waitForAttachment(name: String, expectedSize: Long, timeoutMs: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findAttachment(name, expectedSize)?.let { return it }
            Thread.sleep(500)
        }
        return findAttachment(name, expectedSize)
    }

    private fun findAttachment(name: String, expectedSize: Long): File? {
        val baseName = name.substringBeforeLast('.', name).ifBlank { name }
        val root = File("/data/data/com.tencent.mm/MicroMsg")
        val candidates = ArrayList<File>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val att = File(dir, "attachment")
            att.listFiles()?.forEach { f ->
                if (!f.isFile || f.length() <= 0) return@forEach
                val nameHit = baseName.length >= 3 && f.name.contains(baseName, ignoreCase = true)
                val sizeHit = expectedSize > 0 && kotlin.math.abs(f.length() - expectedSize) < 2048
                if (nameHit || sizeHit) candidates.add(f)
            }
        }
        return candidates.maxByOrNull { it.lastModified() }
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
        file: File,
        mediaKind: String = ForwardProtocol.MEDIA_IMAGE
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
                mediaKind = mediaKind,
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
