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

    /** LSPosed 日志会轮转丢失，关键路径同时写文件。 */
    @JvmStatic
    fun fileLog(ctx: Context?, msg: String) {
        try {
            val dir = ctx?.filesDir ?: return
            java.io.File(dir, "wxhook_dl.log").appendText(
                "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n"
            )
        } catch (_: Throwable) {
        }
    }
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

            // v61.b0 的字段是 jadx 重命名：imgId 运行时叫 a（renamed from: a）
            val imgId = fieldLong(info, "a").takeIf { it > 0 } ?: fieldLong(info, "f479445a")
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

    /** ImgInfo2 里 bigImgPath 可能只是文件名，需要按 image2/xx/yy/ 拼。 */
    private fun resolveImage2(rel: String): File? {
        if (rel.isBlank()) return null
        val candidates = ArrayList<String>()
        candidates.add(rel)
        if (!rel.contains('/')) {
            val stem = rel.substringBefore('.').substringBefore('_')
            if (stem.length >= 4) {
                candidates.add("image2/${stem.take(2)}/${stem.drop(2).take(2)}/$rel")
            }
        }
        return try {
            File("/data/data/com.tencent.mm/MicroMsg").listFiles()?.forEach { d ->
                candidates.forEach { c ->
                    val f = File(d, c)
                    if (f.isFile && f.length() > 0) return f
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 不依赖消息 XML 里的 md5：直接让微信下载原图（ImgInfo2 + v61.j），
     * 然后轮询 ImgInfo2 直到原图路径变成真实文件。
     *
     * 实测很多图片消息的 XML 没有 md5 属性，用 md5 做条件会完全跳过下载。
     */
    fun downloadOriginal(
        ctx: Context?,
        talker: String,
        msgSvrId: Long,
        msgId: Long,
        onDone: (File?) -> Unit
    ): Boolean {
        if (ctx == null || msgSvrId <= 0) return false
        val key = "orig|$msgSvrId"
        if (downloading.putIfAbsent(key, true) != null) return false
        val queued = try {
            downloadViaImageService(ctx, msgSvrId, talker)
        } catch (e: Throwable) {
            fileLog(ctx, "downloadOriginal enqueue failed: ${e.message}")
            false
        }
        Thread({
            var f: File? = null
            val started = System.currentTimeMillis()
            val deadline = started + 90_000
            var retried = false
            while (System.currentTimeMillis() < deadline) {
                val rel = findImgInfo2Original(ctx, msgSvrId)
                if (rel != null) {
                    val cand = resolveImage2(rel)
                    if (cand != null) {
                        f = cand
                        break
                    }
                }
                // 微信下载队列慢时 30s 内可能没完成，再触发一次
                if (!retried && System.currentTimeMillis() - started > 30_000) {
                    retried = true
                    runCatching { downloadViaImageService(ctx, msgSvrId, talker) }
                    fileLog(ctx, "downloadOriginal retry svr=$msgSvrId")
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            downloading.remove(key)
            fileLog(
                ctx,
                "downloadOriginal svr=$msgSvrId msgId=$msgId queued=$queued -> " +
                    (f?.let { "${it.absolutePath}(${it.length()})" } ?: "null")
            )
            onDone(f)
        }, "wxnotes-orig-$msgSvrId").apply { isDaemon = true }.start()
        return queued
    }

    /**
     * 照 WeKit：查 ImgInfo2 拿到原图行 localId，调用 v61.j（DownloadImageService）
     * 的 b(long,MsgIdTalker,int,Object,int,w,int,boolean) 触发微信原生下载。
     */
    fun downloadViaImageService(ctx: Context?, msgSvrId: Long, talker: String): Boolean {
        if (ctx == null || msgSvrId <= 0) return false
        return try {
            val loader = ctx.classLoader
            val storage = Class.forName("v61.b1", false, loader).getMethod("Bj").invoke(null)!!
            val x4 = storage.javaClass.getMethod(
                "X4",
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
            fun query(sel: String, arg: String): Map<String, String>? {
                val c = x4.invoke(storage, "ImgInfo2", null, sel, arrayOf(arg), null, null, null)
                    as? android.database.Cursor ?: return null
                c.use {
                    if (!it.moveToFirst()) return null
                    val m = HashMap<String, String>()
                    for (name in listOf("id", "msgTalker", "bigImgPath", "hevcPath", "midImgPath", "reserved1")) {
                        val idx = it.getColumnIndex(name)
                        if (idx >= 0) m[name] = if (it.isNull(idx)) "" else it.getString(idx)
                    }
                    return m
                }
            }
            val base = query("msgSvrId=?", msgSvrId.toString()) ?: return false
            val hd = base["reserved1"]?.toLongOrNull() ?: 0L
            val target = if (hd > 0) query("id=?", hd.toString()) ?: base else base
            val localId = target["id"]?.toLongOrNull() ?: return false
            val talkerStr = target["msgTalker"].orEmpty().ifBlank { talker }

            val jCls = Class.forName("v61.j", false, loader)
            val svc = jCls.getDeclaredConstructor().newInstance()
            val mtCls = Class.forName("com.tencent.mm.plugin.msg.MsgIdTalker", false, loader)
            val mt = mtCls.getConstructor(java.lang.Long.TYPE, String::class.java)
                .newInstance(localId, talkerStr)
            val b = jCls.methods.firstOrNull { it.name == "b" && it.parameterCount == 8 }
                ?: throw NoSuchMethodException("v61.j.b")
            val wCls = b.parameterTypes[5]
            val listener = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(wCls)) { proxy, m, args ->
                when (m.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "wxnotes-download"
                    else -> if (m.returnType == java.lang.Boolean.TYPE) false else null
                }
            }
            val code = b.invoke(svc, localId, mt, 0, null, 0, listener, -1, false) as? Int ?: -1
            fileLog(ctx, "imageService code=$code localId=$localId hd=$hd talker=$talkerStr")
            code >= 0
        } catch (e: Throwable) {
            fileLog(ctx, "imageService failed: ${e.message}")
            false
        }
    }

    /**
     * 微信 8.0.79 的图片信息在 ImgInfo2 表（WeKit 同款）：
     * bigImgPath/hevcPath/midImgPath 是文件路径，reserved1 指向原图行 id。
     */
    fun findImgInfo2Original(ctx: Context?, msgSvrId: Long): String? {
        if (ctx == null || msgSvrId <= 0) return null
        return try {
            val loader = ctx.classLoader
            val storage = Class.forName("v61.b1", false, loader).getMethod("Bj").invoke(null)!!
            val x4 = storage.javaClass.getMethod(
                "X4",
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
            fun query(sel: String, arg: String): Map<String, String>? {
                val c = x4.invoke(storage, "ImgInfo2", null, sel, arrayOf(arg), null, null, null)
                    as? android.database.Cursor ?: return null
                c.use {
                    if (!it.moveToFirst()) return null
                    val m = HashMap<String, String>()
                    for (name in listOf("id", "msgTalker", "bigImgPath", "hevcPath", "midImgPath", "reserved1")) {
                        val idx = it.getColumnIndex(name)
                        if (idx >= 0) m[name] = if (it.isNull(idx)) "" else it.getString(idx)
                    }
                    return m
                }
            }
            val base = query("msgSvrId=?", msgSvrId.toString())
            fileLog(ctx, "ImgInfo2 svr=$msgSvrId row=$base")
            val hd = base?.get("reserved1")?.toLongOrNull() ?: 0L
            val original = if (hd > 0) query("id=?", hd.toString())?.also {
                fileLog(ctx, "ImgInfo2 hd=$hd row=$it")
            } else null

            fun pick(row: Map<String, String>?): String? =
                listOf("bigImgPath", "hevcPath", "midImgPath")
                    .mapNotNull { row?.get(it) }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("SERVERID://") }

            // 优先原图行；原图行还没下载时回退基础行（已下载的大图）
            pick(original) ?: pick(base)
        } catch (e: Throwable) {
            fileLog(ctx, "ImgInfo2 query failed: ${e.message}")
            null
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
            // Service 不能直接 new（会触发 Android 生命周期/闪退），用 Unsafe 只分配内存
            val unsafeCls = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val svc = unsafeCls.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, svcCls)
            fileLog(ctx, "cdn service allocated=$svc")
            val aCls = Class.forName("w12.a", false, loader)
            val binder = aCls.getConstructor(svcCls).newInstance(svc)
            val infoCls = Class.forName(
                "com.tencent.mm.plugin.cdndownloader.ipc.CDNTaskInfo", false, loader
            )
            val info = infoCls.getConstructor(String::class.java).newInstance(fileKey)
            // jadx 显示为 f92137f 等，运行时真实字段名是混淆后的 f/g/h/m
            de.robv.android.xposed.XposedHelpers.setObjectField(info, "f", fileId)
            de.robv.android.xposed.XposedHelpers.setObjectField(info, "g", savePath)
            de.robv.android.xposed.XposedHelpers.setObjectField(info, "h", aesKey)
            de.robv.android.xposed.XposedHelpers.setIntField(info, "m", fileType)
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
    @Volatile
    private var autoDownloadClsName: String? = null

    /**
     * 微信内部"自动下载文件"的 Runnable（com.tencent.mm.pluginsdk.model.app 下，
     * 类里有 "MicroMsg.AppMessageExtension" + "autoDownloadFile2 %s %s" 两个常量）。
     * 构造参数是 MsgInfo，run() 会解析 appmsg XML、创建 appattach 行并向 CDN 发起下载任务 ——
     * 这正是点击文件气泡"下载"走的逻辑，全程无 UI。
     */
    private fun findAutoDownloadClass(ctx: Context): String? {
        autoDownloadClsName?.let { return it }
        return try {
            val bridge = org.luckypray.dexkit.DexKitBridge.create(ctx.applicationInfo.sourceDir)
                ?: return null
            try {
                val m = bridge.findMethod {
                    matcher {
                        usingEqStrings("MicroMsg.AppMessageExtension", "autoDownloadFile2 %s %s")
                    }
                }.firstOrNull() ?: return null
                val name = m.declaredClass?.name ?: return null
                autoDownloadClsName = name
                fileLog(ctx, "autoDownload class=$name")
                name
            } finally {
                runCatching { bridge.close() }
            }
        } catch (e: Throwable) {
            fileLog(ctx, "findAutoDownloadClass failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 无感下载：直接跑微信内部的 autoDownloadFile。成功返回 true（不代表文件已下完，见 onDone）。 */
    private fun silentDownloadFile(
        ctx: Context,
        talker: String,
        msgId: Long,
        fileName: String,
        expectedSize: Long,
        onDone: (File?) -> Unit
    ): Boolean {
        val key = "file|$talker|$msgId"
        if (downloading.putIfAbsent(key, true) != null) return true // 已在下载
        return try {
            val clsName = findAutoDownloadClass(ctx) ?: run {
                downloading.remove(key)
                return false
            }
            val msgCls = Class.forName("com.tencent.mm.storage.e9", false, ctx.classLoader)
            val msgInfo = Class.forName("oy0.k0", false, ctx.classLoader)
                .getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                .invoke(null, talker, msgId)
            if (msgInfo == null) {
                downloading.remove(key)
                fileLog(ctx, "silent download: msgInfo null msgId=$msgId")
                return false
            }
            val cls = Class.forName(clsName, false, ctx.classLoader)
            // 优先选参数类型是 MsgInfo 的构造器
            val ctor = cls.declaredConstructors
                .filter { it.parameterCount == 1 }
                .maxByOrNull { if (msgCls.isAssignableFrom(it.parameterTypes[0])) 1 else 0 }
                ?: run { downloading.remove(key); return false }
            ctor.isAccessible = true
            val runnable = ctor.newInstance(msgInfo)
            (runnable as? Runnable)?.run()
            XposedBridge.log("$TAG silent download triggered msgId=$msgId class=$clsName name=$fileName")
            Thread({
                val f = waitForAttachment(fileName, expectedSize, 120_000)
                downloading.remove(key)
                XposedBridge.log("$TAG silent download result: ${f?.absolutePath} (${f?.length()})")
                onDone(f)
            }, "wxnotes-silentdl").apply { isDaemon = true }.start()
            true
        } catch (e: Throwable) {
            downloading.remove(key)
            XposedBridge.log("$TAG silent download failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

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
            // 首选：复用微信内部的 autoDownloadFile Runnable（无 UI，直接建 appattach 行 + CDN 下载）
            if (silentDownloadFile(ctx, talker, msgId, fileName, expectedSize, onDone)) {
                return true
            }
            // 兜底：微信真正触发文件下载的入口是 AppAttachNewDownloadUI：它内部依次做
            //   CreateAppAttachInfo（按 msgId 从 XML 建 appattach 记录）
            //   -> CheckBigFileDownload（拿 signature）
            //   -> NetSceneDownloadAppAttach（CDN 下载）
            // 之前调的 com.tencent.mm.storage.f9.Ud(long, e9) 只是 UPDATE message 表，不会下载。
            val intent = android.content.Intent().apply {
                setClassName("com.tencent.mm", "com.tencent.mm.ui.chatting.AppAttachNewDownloadUI")
                putExtra("app_msg_id", msgId)
                putExtra("msg_talker", talker)
                putExtra("msg_type", 49)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            XposedBridge.log("$TAG open AppAttachNewDownloadUI msgId=$msgId attachId=${attachId.take(40)} name=$fileName")
            Thread({
                val f = waitForAttachment(fileName, expectedSize, 120_000)
                downloading.remove(key)
                XposedBridge.log("$TAG file download result: ${f?.absolutePath} (${f?.length()})")
                onDone(f)
            }, "wxnotes-filedl").apply { isDaemon = true }.start()
            true
        } catch (e: Throwable) {
            downloading.remove(key)
            XposedBridge.log("$TAG downloadFile failed: ${e.javaClass.simpleName} ${e.message}")
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
    /**
     * 微信 8.x 的 image2/<md5>.jpg 可能是私有 HEVC 容器（文件头 "wxgf"），
     * Android/Coil 均无法解码。此时退回同目录的高清缩略图 th_<stem>hd（标准 JPEG）。
     */
    fun displayable(ctx: Context?, file: File): File {
        return try {
            if (!WxImageTranscoder.isWxContainer(file)) return file
            // 优先真转码成 JPEG；失败再退回同目录高清缩略图
            WxImageTranscoder.transcode(ctx, file)?.let { return it }
            val dir = file.parentFile ?: return file
            val stem = file.name.substringBefore('.')
            listOf("th_${stem}hd", "th_$stem")
                .map { File(dir, it) }
                .firstOrNull { it.isFile && it.length() > 0 } ?: file
        } catch (_: Throwable) {
            file
        }
    }

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
            // 微信大图可能是私有 HEVC 封装（wxfa 头），照原样发送到笔记端无法显示
            val src = displayable(ctx, file)
            val msgs = ArrayList<Pair<ForwardMessage, File>>()
            val len = src.length()
            val msg = ForwardMessage(
                msgId = msgId,
                msgSvrId = msgSvrId,
                type = if (type > 0) type else 3,
                createTime = createTime,
                isSend = isSend,
                text = when (mediaKind) {
                    ForwardProtocol.MEDIA_FILE -> "[文件]"
                    ForwardProtocol.MEDIA_VOICE -> "[语音]"
                    ForwardProtocol.MEDIA_VIDEO -> "[视频]"
                    else -> "[图片]"
                },
                mediaKind = mediaKind,
                fileName = fileName.ifBlank { src.name },
                fileSize = len
            )
            if (len <= 100 * 1024) {
                val b64 = android.util.Base64.encodeToString(src.readBytes(), android.util.Base64.NO_WRAP)
                msgs.add(msg.copy(mediaBase64 = b64) to src)
            } else {
                val up = WxMediaBridge.upload(ctx, src)
                if (up != null) {
                    msgs.add(msg.copy(localPath = up, fileSize = len) to src)
                } else {
                    msgs.add(msg to src)
                }
            }
            val batch = ForwardBatch(
                pluginVersion = "1.0.3",
                conversation = Conversation(talker, talkerTitle.ifBlank { talker }, talker.endsWith("@chatroom")),
                messages = msgs.map { it.first },
                update = true
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
                if (m.mediaBase64.isEmpty() && m.localPath.isBlank()) {
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
