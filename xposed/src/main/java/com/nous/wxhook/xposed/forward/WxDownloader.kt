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
                val mi = WxClasses.msgInfo(ctx, talker, msgId)
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
                    if (f.isFile && f.length() > 0) {
                        // 8.0.80 的 vfs 引用：真实文件在 .ref/d/<uuid>
                        return WxVfs.resolve(f)
                    }
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
    /**
     * 8.0.79 的原图下载：dn5.n（MsgHistoryGalleryMediaHelper）的 suspend 方法 b(msgInfo, z, cont)。
     * z=true 表示下载 Big（原图）：内部 genDownloadParams -> 已存在直接返回 a(path)，
     * 否则发起 CDN 下载，完成后通过 Continuation.resumeWith 返回 a(path)。
     * （旧的 v61.b1 / v61.j 在 8.0.79 已被改名，反射直接 ClassNotFound。）
     */
    private fun downloadImageViaHelper(
        ctx: Context,
        talker: String,
        msgId: Long,
        onDone: (File?) -> Unit
    ): Boolean {
        return try {
            val loader = ctx.classLoader
            // 微信的下载 API 只认真实 f9(MsgInfo)；SQL 替身参数类型不符会被拒
            val msgInfo = WxClasses.realMsgInfo(ctx, talker, msgId) ?: run {
                XposedBridge.log("$TAG image helper: realMsgInfo null msgId=$msgId (adapter 里没有这条)")
                return false
            }
            val helperName = WxClasses.mediaHelperName(ctx) ?: run {
                XposedBridge.log("$TAG image helper: class not found (MsgHistoryGalleryMediaHelper)")
                return false
            }
            val helperCls = Class.forName(helperName, false, loader)
            // 先找静态单例字段（8.0.80 的 xl5.n 是 `static xl5.n a`）；
            // 类型判断放宽成「值本身就是 helperCls 实例」，避免声明类型写成父类/接口时漏掉。
            var helper: Any? = null
            runCatching {
                helperCls.declaredFields.forEach { f ->
                    if (helper == null && java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                        !f.type.isPrimitive
                    ) {
                        val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull()
                        if (v != null && helperCls.isInstance(v)) helper = v
                    }
                }
            }
            if (helper == null) {
                runCatching {
                    val ctor = helperCls.declaredConstructors.firstOrNull()
                    ctor?.isAccessible = true
                    helper = ctor?.let {
                        // 有参构造就传 null 试一次（部分单例构造参数只是 context）
                        it.newInstance(*Array(it.parameterCount) { _ -> null })
                    }
                }
            }
            val h = helper ?: run {
                XposedBridge.log("$TAG image helper: cannot obtain instance of ${helperCls.name}")
                return false
            }

            val b = helperCls.methods.firstOrNull { it.name == "b" && it.parameterCount == 3 } ?: return false
            val contIface = Class.forName("kotlin.coroutines.Continuation", false, loader)
            // 必须返回微信自己的 EmptyCoroutineContext 实例（8.0.80 是 ua6.l），
            // 否则 ContinuationImpl 转换时会抛 "Couldn't convert ... to ua6.l"
            val emptyCtx: Any? = WxClasses.emptyCoroutineContext(ctx)
                ?: kotlin.coroutines.EmptyCoroutineContext
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val cont = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(contIface)) { _, m, args ->
                when (m.name) {
                    "getContext" -> emptyCtx
                    "resumeWith" -> {
                        if (done.compareAndSet(false, true)) {
                            val path = extractImagePath(args?.firstOrNull())
                            XposedBridge.log("$TAG image helper resumed path=$path")
                            onDone(path?.let { File(it) }?.takeIf { it.isFile })
                        }
                        null
                    }
                    else -> null
                }
            }
            // z=true -> 原图（Big）
            val ret = runCatching { b.invoke(h, msgInfo, true, cont) }.getOrElse { e ->
                XposedBridge.log("$TAG image helper invoke failed: ${e.javaClass.simpleName} ${e.cause?.message ?: e.message}")
                return false
            }
            if (ret != null && done.compareAndSet(false, true)) {
                val path = extractImagePath(ret)
                XposedBridge.log("$TAG image helper returned path=$path")
                onDone(path?.let { File(it) }?.takeIf { it.isFile })
            }
            XposedBridge.log("$TAG image helper invoked msgId=$msgId")
            true
        } catch (e: Throwable) {
            XposedBridge.log("$TAG image helper failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 从 kotlin.Result<a> 或 a 里取出图片路径。 */
    private fun extractImagePath(o: Any?): String? {
        var cur = o ?: return null
        if (cur.javaClass.name == "kotlin.Result") {
            cur = runCatching {
                cur.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(cur)
            }.getOrNull() ?: return null
        }
        var k: Class<*>? = cur.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 3) {
            for (f in k.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(cur)
                    if (v is String && v.startsWith("/")) return v
                }
            }
            k = k.superclass
            depth++
        }
        return null
    }

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
        val directPath = java.util.concurrent.atomic.AtomicReference<String>()
        val latch = java.util.concurrent.CountDownLatch(1)
        // 必须在后台线程：realMsgInfo 要遍历 adapter 并反射，主线程做会卡 UI
        val queued = true
        Thread({
            try {
                val viaHelper = downloadImageViaHelper(ctx, talker, msgId) { f ->
                    if (f != null) directPath.set(f.absolutePath)
                    latch.countDown()
                }
                if (!viaHelper) {
                    fileLog(ctx, "helper unavailable, fallback ImageService")
                    downloadViaImageService(ctx, msgSvrId, talker)
                }
            } catch (e: Throwable) {
                fileLog(ctx, "downloadOriginal enqueue failed: ${e.message}")
            }
        }, "wxhook-orig-enqueue").start()
        Thread({
            var f: File? = null
            // 先等 helper 的 resumeWith（通常 1-3 秒；已存在则同步就给了）
            runCatching { latch.await(15, java.util.concurrent.TimeUnit.SECONDS) }
            directPath.get()?.let { p -> f = File(p).takeIf { it.isFile } }
            val started = System.currentTimeMillis()
            val deadline = started + 90_000
            var retried = false
            while (f == null && System.currentTimeMillis() < deadline) {
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
                    runCatching { downloadImageViaHelper(ctx, talker, msgId) { null } }
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
            // 8.0.80 起 ImgInfo2Storage 的混淆类变了（v61.b1 -> g61.k0）且实例拿不到，
            // 但表结构没变，直接 SQL 查最稳。只查确认存在的列，避免 no such column。
            val cols = "id,msgTalker,bigImgPath,midImgPath,reserved1"
            fun query(sql: String, arg: String): Map<String, Any?>? =
                WeDb.query(ctx, sql, arrayOf(arg)).firstOrNull()

            val base = query("SELECT $cols FROM ImgInfo2 WHERE msgSvrId=? LIMIT 1", msgSvrId.toString())
            fileLog(ctx, "ImgInfo2 svr=$msgSvrId row=$base")
            val hd = (base?.get("reserved1") as? Number)?.toLong() ?: 0L
            val original = if (hd > 0) {
                query("SELECT $cols FROM ImgInfo2 WHERE id=? LIMIT 1", hd.toString())
                    ?.also { fileLog(ctx, "ImgInfo2 hd=$hd row=$it") }
            } else null

            // 关键：ImgInfo2 里「原图行」可能只有记录没有文件（微信清理过/还没下载完），
            // 所以必须校验文件真实存在，否则轮询会一直等一个永远不出现的路径。
            fun pickExisting(row: Map<String, Any?>?): String? =
                listOf("bigImgPath", "midImgPath")
                    .mapNotNull { row?.get(it)?.toString() }
                    .firstOrNull { rel ->
                        rel.isNotBlank() && !rel.startsWith("SERVERID://") &&
                            resolveImage2(rel) != null
                    }

            // 优先原图行（且文件存在）；否则回退基础行
            pickExisting(original) ?: pickExisting(base)
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
    /** 取微信内部 NetSceneQueue（l51.i9.e()），用于提交自建的网络场景。 */
    private fun sceneQueue(ctx: Context): Any? = runCatching {
        Class.forName("l51.i9", false, ctx.classLoader).getMethod("e").invoke(null)
    }.getOrNull()

    private fun submitScene(queue: Any, scene: Any): Boolean = runCatching {
        val m = queue.javaClass.methods.firstOrNull { it.name == "g" && it.parameterCount == 1 }
            ?: return false
        m.invoke(queue, scene)
        true
    }.getOrDefault(false)

    /**
     * 走微信的 CheckBigFileDownload（CGI /cgi-bin/micromsg-bin/checkbigfiledownload，type 728）
     * 换 signature。没有它 CDN 只给部分数据就挂住（实测卡在 57KB）。
     * 结果由微信自己写回 appattach 表，这里轮询等待。
     */
    private fun runCheckBigFileDownload(ctx: Context, msgId: Long, talker: String): Boolean {
        return try {
            val loader = ctx.classLoader
            // AppAttachInfo = ((e)((vu.q0)((wu.q0) kj5.n0.c(wu.q0.class)).yj())).t1(msgId, talker)
            val wuIface = Class.forName("wu.q0", false, loader)
            val wuInst = Class.forName("kj5.n0", false, loader)
                .getMethod("c", Class::class.java).invoke(null, wuIface) ?: return false
            val vuInst = wuInst.javaClass.getMethod("yj").invoke(wuInst) ?: return false
            val t1 = vuInst.javaClass.methods.firstOrNull { it.name == "t1" && it.parameterCount == 2 }
                ?: return false
            val info = t1.invoke(vuInst, msgId, talker) ?: return false

            val infoCls = Class.forName("com.tencent.mm.pluginsdk.model.app.d", false, loader)
            val i0Cls = Class.forName("e24.i0", false, loader)
            val ctor = i0Cls.constructors.firstOrNull {
                it.parameterCount == 6 && it.parameterTypes[0] == infoCls
            } ?: return false
            // (info, aesKey, fileMd5, fileName, fileExt, extra)
            val scene = ctor.newInstance(info, "", "", "", "", "")
            val queue = sceneQueue(ctx) ?: return false
            val ok = submitScene(queue, scene)
            XposedBridge.log("$TAG CheckBigFileDownload submitted=$ok msgId=$msgId")
            ok
        } catch (e: Throwable) {
            XposedBridge.log("$TAG CheckBigFileDownload failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 轮询 appattach.signature（微信在 onGYNetEnd 里写回）。 */
    private fun waitForSignature(ctx: Context, msgId: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val sig = WeDb.query(ctx, "SELECT signature FROM appattach WHERE msgInfoId=$msgId LIMIT 1")
                .firstOrNull()?.get("signature") as? String
            if (!sig.isNullOrBlank()) return true
            Thread.sleep(500)
        }
        return false
    }

    /** 提交 NetSceneDownloadAppAttach（e24.l0）。 */
    private fun submitDownloadScene(ctx: Context, msgId: Long, talker: String, mediaId: String): Boolean {
        return try {
            val loader = ctx.classLoader
            val l0Cls = Class.forName("e24.l0", false, loader)
            val v0Cls = Class.forName("com.tencent.mm.modelbase.v0", false, loader)
            val ctor = l0Cls.getConstructor(
                java.lang.Long.TYPE, String::class.java, String::class.java, v0Cls
            )
            val scene = ctor.newInstance(msgId, talker, mediaId, null)
            val queue = sceneQueue(ctx) ?: return false
            val ok = submitScene(queue, scene)
            XposedBridge.log("$TAG DownloadAppAttach submitted=$ok msgId=$msgId mediaId=${mediaId.take(24)}")
            ok
        } catch (e: Throwable) {
            XposedBridge.log("$TAG DownloadAppAttach failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 无感下载：建 appattach 行 -> CheckBigFileDownload 换 signature -> NetSceneDownloadAppAttach。 */
    private fun silentDownloadFile(
        ctx: Context,
        talker: String,
        msgId: Long,
        fileName: String,
        expectedSize: Long,
        onDone: (File?) -> Unit
    ): Boolean {
        val key = "file|$talker|$msgId"
        if (downloading.putIfAbsent(key, true) != null) return true
        return try {
            val loader = ctx.classLoader
            val msgCls = Class.forName("com.tencent.mm.storage.e9", false, loader)
            val msgInfo = WxClasses.msgInfo(ctx, talker, msgId)
            if (msgInfo == null) {
                downloading.remove(key)
                fileLog(ctx, "silent download: msgInfo null msgId=$msgId")
                return false
            }
            val msgIdArg = msgId
            Thread({
                var mediaId = ""
                try {
                    // 1) 建/取 appattach 行：a0.o(msgInfo, false) -> Pair(created, mediaId)
                    val a0 = Class.forName("com.tencent.mm.pluginsdk.model.app.a0", false, loader)
                    val pair = runCatching {
                        a0.getMethod("o", msgCls, java.lang.Boolean.TYPE).invoke(null, msgInfo, false)
                    }.getOrNull() as? android.util.Pair<*, *>
                    mediaId = (pair?.second as? String).orEmpty()
                    XposedBridge.log("$TAG tryInitAttach created=${pair?.first} mediaId=${mediaId.take(24)}")

                    // 2) 换 signature（缺了它 CDN 会卡在部分字节）
                    if (runCheckBigFileDownload(ctx, msgIdArg, talker)) {
                        val got = waitForSignature(ctx, msgIdArg, 20_000)
                        XposedBridge.log("$TAG signature ready=$got msgId=$msgIdArg")
                    }

                    // 3) 真正发起 CDN 下载
                    submitDownloadScene(ctx, msgIdArg, talker, mediaId)
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG silent download prep failed: ${e.javaClass.simpleName} ${e.message}")
                }

                var f = waitForAttachment(fileName, expectedSize, 90_000)
                if (f == null) {
                    // 仍然没下来：拉起微信自己的下载页兜底（它会补做同样的流程）
                    XposedBridge.log("$TAG silent download stalled, fallback to AppAttachNewDownloadUI")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching {
                            ctx.startActivity(android.content.Intent().apply {
                                setClassName("com.tencent.mm", "com.tencent.mm.ui.chatting.AppAttachNewDownloadUI")
                                putExtra("app_msg_id", msgIdArg)
                                putExtra("msg_talker", talker)
                                putExtra("msg_type", 49)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }
                    f = waitForAttachment(fileName, expectedSize, 90_000)
                }
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
