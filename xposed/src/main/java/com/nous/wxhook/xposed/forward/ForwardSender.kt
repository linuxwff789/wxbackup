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

    fun forward(
        ctx: Context?,
        msgInfo: Any,
        view: android.view.View? = null,
        talkerTitle: String = "",
        autoFetchOriginal: Boolean = true
    ): Boolean {
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
        var mediaBase64 = ""
        var chunkSource: File? = null
        val type = parsed.type
        val candidates = listOf(ex.imgPath, ex.fileFullPath)
            .filter { it.isNotBlank() }
        val localMedia = (findImageByMd5(parsed.mediaMd5)
            ?: WxDownloader.findImgInfo2Original(ctx, ex.msgSvrId)?.let { resolveUnderMicroMsg(it) }
            ?: candidates.firstOrNull { File(it).isFile && !isThumbnailPath(it) }
            ?: findMediaFile(parsed.fileName.ifBlank { ex.fileName }, parsed.mediaKind)
            ?: candidates.firstOrNull { File(it).isFile })
            ?.let { WxDownloader.displayable(ctx, File(it)).absolutePath }
        XposedBridge.log("$TAG local=$localMedia imgPath=${ex.imgPath} md5=${parsed.mediaMd5}")
        if (localMedia == null && mediaKind == ForwardProtocol.MEDIA_IMAGE) {
            // 原图没下载时，直接抓聊天窗口里已显示的缩略图
            captureImageFromView(view)?.let { bytes ->
                mediaBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                XposedBridge.log("$TAG captured thumbnail from view: ${bytes.size} bytes")
            }
        }
        if (localMedia != null) {
            val file = File(localMedia)
            if (file.length() in 1..MAX_INLINE_BYTES) {
                try {
                    mediaBase64 = android.util.Base64.encodeToString(
                        file.readBytes(), android.util.Base64.NO_WRAP
                    )
                } catch (_: Throwable) {
                }
            }
            if (mediaBase64.isEmpty()) {
                if (file.length() > MAX_INLINE_BYTES) {
                    // 先走 ContentProvider 直传；失败再回退分片广播
                    val up = WxMediaBridge.upload(ctx, file)
                    if (up != null) mediaUrl = up else chunkSource = file
                } else {
                    MediaServer.uriFor(localMedia)?.let { mediaUrl = it }
                }
            }
            if (mediaKind == ForwardProtocol.MEDIA_NONE) mediaKind = guessKind(localMedia)
        }

        val createTime = if (ex.createTime > 10_000_000_000L) ex.createTime / 1000 else ex.createTime

        // 原图没落盘：先给缩略图，再自动打开预览触发微信下载原图，下载完自动更新
        if (autoFetchOriginal && localMedia == null && mediaKind == ForwardProtocol.MEDIA_FILE &&
            parsed.attachId.isNotBlank()
        ) {
            WxDownloader.downloadFile(
                ctx, ex.talker, parsed.attachId, parsed.fileName, parsed.fileSize, ex.msgId
            ) { file ->
                if (file != null) {
                    WxDownloader.publishUpdate(
                        ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                        createTime, type, parsed.fileName, file, ForwardProtocol.MEDIA_FILE
                    )
                }
            }
        }
        val thumbOnly = localMedia != null && isThumbnailPath(localMedia)
        XposedBridge.log("$TAG image thumbOnly=$thumbOnly local=$localMedia")
        if (false && mediaKind == ForwardProtocol.MEDIA_IMAGE && (localMedia == null || thumbOnly) &&
            parsed.mediaHint.isNotBlank()
        ) {
            XposedBridge.log("$TAG cdn branch hint=${parsed.mediaHint.take(24)} aesLen=${parsed.aesKey.length}")
            WxDownloader.fileLog(ctx, "cdn branch hint=${parsed.mediaHint.take(24)} aesLen=${parsed.aesKey.length} md5=${parsed.mediaMd5}")
            val md5 = parsed.mediaMd5.ifBlank { System.currentTimeMillis().toString() }
            val save = File(ctx?.filesDir ?: return false, "cdn_$md5.jpg").absolutePath
            WxDownloader.downloadCdn(ctx, "img_$md5", parsed.mediaHint, parsed.aesKey, 5, save) { f ->
                if (f != null) {
                    WxDownloader.publishUpdate(
                        ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                        createTime, type, parsed.fileName, f
                    )
                }
            }
        }
        WxDownloader.fileLog(
            ctx,
            "autoFetch? single id=${ex.msgId} svr=${ex.msgSvrId} kind=$mediaKind " +
                "local=${localMedia?.takeLast(48)} thumb=$thumbOnly md5=${parsed.mediaMd5.length} auto=$autoFetchOriginal"
        )
        if (autoFetchOriginal && (localMedia == null || thumbOnly) &&
            mediaKind == ForwardProtocol.MEDIA_IMAGE && ex.msgSvrId > 0
        ) {
            val fetch: ((File?) -> Unit) -> Boolean = { cb ->
                WxDownloader.downloadOriginal(ctx, ex.talker, ex.msgSvrId, ex.msgId, cb)
            }
            fetch { file ->
                if (file != null) {
                    WxDownloader.publishUpdate(
                        ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                        createTime, type, parsed.fileName, file
                    )
                } else if (view != null && mediaBase64.isNotEmpty()) {
                    // 无感下载失败才退回打开预览
                    OriginalFetcher.schedule(
                        ctx, view, parsed.mediaMd5, ex.msgId, ex.msgSvrId, ex.talker,
                        talkerTitle, ex.isSend, createTime, type, parsed.fileName
                    )
                }
            }
        }
        val fwd = ForwardMessage(
            msgId = ex.msgId,
            msgSvrId = ex.msgSvrId,
            type = type,
            subType = parsed.subType,
            createTime = createTime,
            isSend = ex.isSend,
            sender = sender,
            senderName = "",
            text = parsed.text,
            rawXml = content.take(4_000),
            mediaKind = mediaKind,
            localPath = mediaUrl,
            fileName = parsed.fileName,
            fileSize = parsed.fileSize,
            durationMs = parsed.durationMs,
            mediaBase64 = mediaBase64
        )

        val batch = ForwardBatch(
            pluginVersion = "1.0.0",
            conversation = Conversation(
                talker = ex.talker,
                title = talkerTitle.ifBlank { ex.talker },
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

        // 大媒体走分片广播（不受 Binder 1MB 和跨进程限制）
        chunkSource?.let { file ->
            sendChunks(
                ctx,
                batch.conversation,
                fwd,
                file,
                buildString { append(ex.talker).append('|').append(fwd.effectiveDedupeKey()) }
            )
        }

        // 媒体服务器保活
        MediaServer.ensureStarted()

        XposedBridge.log(
            "$TAG forwarded type=${ex.type} sub=${parsed.subType} msgId=${ex.msgId} " +
                "talker=${ex.talker} title=${talkerTitle.take(24)} media=$mediaKind " +
                "b64=${mediaBase64.length} url=${mediaUrl.take(60)} ok=$delivered"
        )
        toast(ctx, "已保存到笔记：${parsed.text.take(24)}")
        return true
    }

    /** Binder 单次 ~1MB，base64 后约 1.33 倍；500KB 原文安全 */
    private const val MAX_INLINE_BYTES = 100 * 1024L

    /**
     * 把大媒体切成 ~360KB 的分片广播，接收端落盘合并后更新消息。
     * 无需 root / 网络 / LocalSocket 权限。
     */
    fun sendChunks(
        ctx: Context?,
        conversation: Conversation,
        msg: ForwardMessage,
        file: File,
        dedupeKey: String
    ): Boolean {
        if (ctx == null || !file.isFile) return false
        return try {
            val len = file.length()
            if (len <= 0) return false
            val chunkSize = ForwardProtocol.CHUNK_SIZE.toLong()
            val total = ((len + chunkSize - 1) / chunkSize).toInt()
            val transferId = java.util.UUID.randomUUID().toString()
            XposedBridge.log("$TAG sending chunks: $len bytes / $total pieces -> $transferId")
            java.io.FileInputStream(file).use { input ->
                for (i in 0 until total) {
                    val start = i * chunkSize
                    val size = minOf(chunkSize, len - start).toInt()
                    if (size <= 0) break
                    val buf = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = input.read(buf, read, size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) break
                    val data = android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
                    val chunk = ForwardProtocol.MediaChunk(
                        transferId = transferId,
                        dedupeKey = dedupeKey,
                        msgSvrId = msg.msgSvrId,
                        msgId = msg.msgId,
                        talker = conversation.talker,
                        talkerTitle = conversation.title,
                        isGroup = conversation.isGroup,
                        type = msg.type,
                        subType = msg.subType,
                        createTime = msg.createTime,
                        isSend = msg.isSend,
                        sender = msg.sender,
                        senderName = msg.senderName,
                        text = msg.text,
                        rawXml = "",
                        mediaKind = msg.mediaKind,
                        fileName = msg.fileName.ifBlank { file.name },
                        fileSize = len,
                        durationMs = msg.durationMs,
                        index = i,
                        total = total,
                        data = data
                    )
                    val intent = Intent(ForwardProtocol.ACTION_RECEIVE_CHUNK).apply {
                        setPackage(TARGET_PKG)
                        putExtra(ForwardProtocol.EXTRA_CHUNK_JSON, chunk.encode())
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                    ctx.sendBroadcast(intent)
                }
            }
            true
        } catch (e: Throwable) {
            XposedBridge.log("$TAG sendChunks failed: ${e.message}")
            false
        }
    }

    /**
     * 多选批量：所有消息打包成一个批次（同一篇笔记），媒体按预算内嵌，超出走分片。
     */
    fun forwardList(ctx: Context?, list: List<Any>, talkerTitle: String): Int {
        if (ctx == null || list.isEmpty()) return 0
        val entries = ArrayList<Triple<String, ForwardMessage, File?>>()
        var inlineBudget = 600 * 1024

        for (msgInfo in list) {
            val ex = MessageExtractor.extract(msgInfo) ?: continue
            if (ex.talker.isBlank()) continue
            val isGroup = ex.talker.endsWith("@chatroom")
            var content = ex.content
            var sender = ""
            if (isGroup) {
                val m = Regex("^([A-Za-z0-9_-]{4,64}):\\s*").find(content)
                if (m != null) {
                    sender = m.groupValues[1]
                    content = content.substring(m.range.last + 1)
                }
            }
            val parsed = WxMessageParser.parse(ex.type, content)
            var mediaKind = parsed.mediaKind
            var mediaBase64 = ""
            var mediaUrl = ""
            var chunkFile: File? = null
            val localMedia = (findImageByMd5(parsed.mediaMd5)
                ?: WxDownloader.findImgInfo2Original(ctx, ex.msgSvrId)?.let { resolveUnderMicroMsg(it) }
                ?: listOf(ex.imgPath, ex.fileFullPath)
                    .filter { it.isNotBlank() }
                    .firstOrNull { File(it).isFile && !isThumbnailPath(it) }
                ?: findMediaFile(parsed.fileName.ifBlank { ex.fileName }, parsed.mediaKind)
                ?: listOf(ex.imgPath, ex.fileFullPath)
                    .filter { it.isNotBlank() }
                    .firstOrNull { File(it).isFile })
                ?.let { WxDownloader.displayable(ctx, File(it)).absolutePath }
            XposedBridge.log("$TAG local=$localMedia imgPath=${ex.imgPath} md5=${parsed.mediaMd5}")
            if (localMedia != null) {
                val file = File(localMedia)
                if (file.length() in 1..MAX_INLINE_BYTES && file.length() * 4 / 3 <= inlineBudget) {
                    try {
                        mediaBase64 = android.util.Base64.encodeToString(
                            file.readBytes(), android.util.Base64.NO_WRAP
                        )
                        inlineBudget -= mediaBase64.length
                    } catch (_: Throwable) {
                    }
                }
                if (mediaBase64.isEmpty()) {
                    if (file.length() > MAX_INLINE_BYTES) {
                        val up = WxMediaBridge.upload(ctx, file)
                        if (up != null) mediaUrl = up else chunkFile = file
                    } else {
                        MediaServer.uriFor(localMedia)?.let { mediaUrl = it }
                    }
                }
                if (mediaKind == ForwardProtocol.MEDIA_NONE) mediaKind = guessKind(localMedia)
            }
            val createTime = if (ex.createTime > 10_000_000_000L) ex.createTime / 1000 else ex.createTime
            if (localMedia == null && mediaKind == ForwardProtocol.MEDIA_FILE && parsed.attachId.isNotBlank()) {
                WxDownloader.downloadFile(
                    ctx, ex.talker, parsed.attachId, parsed.fileName, parsed.fileSize, ex.msgId
                ) { file ->
                    if (file != null) {
                        WxDownloader.publishUpdate(
                            ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                            createTime, parsed.type, parsed.fileName, file, ForwardProtocol.MEDIA_FILE
                        )
                    }
                }
            }
            val thumbOnly = localMedia != null && isThumbnailPath(localMedia)
            if (false && (localMedia == null || thumbOnly) && mediaKind == ForwardProtocol.MEDIA_IMAGE &&
                parsed.mediaHint.isNotBlank()
            ) {
                XposedBridge.log("$TAG cdn branch hint=${parsed.mediaHint.take(24)} aesLen=${parsed.aesKey.length}")
                WxDownloader.fileLog(ctx, "cdn branch hint=${parsed.mediaHint.take(24)} aesLen=${parsed.aesKey.length} md5=${parsed.mediaMd5}")
            WxDownloader.fileLog(ctx, "cdn branch hint=${parsed.mediaHint.take(24)} aesLen=${parsed.aesKey.length} md5=${parsed.mediaMd5}")
                val md5 = parsed.mediaMd5.ifBlank { System.currentTimeMillis().toString() }
                val dir = ctx?.filesDir ?: continue
                val save = File(dir, "cdn_$md5.jpg").absolutePath
                WxDownloader.downloadCdn(ctx, "img_$md5", parsed.mediaHint, parsed.aesKey, 5, save) { f ->
                    if (f != null) {
                        WxDownloader.publishUpdate(
                            ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                            createTime, parsed.type, parsed.fileName, f
                        )
                    }
                }
            }
            WxDownloader.fileLog(
                ctx,
                "autoFetch? list id=${ex.msgId} svr=${ex.msgSvrId} kind=$mediaKind " +
                    "local=${localMedia?.takeLast(48)} thumb=$thumbOnly md5=${parsed.mediaMd5.length}"
            )
            if ((localMedia == null || thumbOnly) && mediaKind == ForwardProtocol.MEDIA_IMAGE && ex.msgSvrId > 0) {
                // 统一走 ImgInfo2 + DownloadImageService（v61.j）：
                // NetSceneGetMsgImg 需要读 ImgInfo.imgId，字段名是 jadx 重命名的，实测读不到。
                val fetch: ((File?) -> Unit) -> Boolean = { cb ->
                    WxDownloader.downloadOriginal(ctx, ex.talker, ex.msgSvrId, ex.msgId, cb)
                }
                fetch { file ->
                    if (file != null) {
                        WxDownloader.publishUpdate(
                            ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                            createTime, parsed.type, parsed.fileName, file
                        )
                    } else if (parsed.mediaHint.isNotBlank() && parsed.aesKey.length >= 32) {
                        WxDownloader.downloadImageByCdn(ctx, parsed.mediaHint, parsed.aesKey, parsed.mediaMd5) { f2 ->
                            if (f2 != null) WxDownloader.publishUpdate(
                                ctx, ex.talker, talkerTitle, ex.isSend, ex.msgId, ex.msgSvrId,
                                createTime, parsed.type, parsed.fileName, f2
                            )
                        }
                    }
                }
            }
            val fwd = ForwardMessage(
                msgId = ex.msgId,
                msgSvrId = ex.msgSvrId,
                type = parsed.type,
                subType = parsed.subType,
                createTime = createTime,
                isSend = ex.isSend,
                sender = sender,
                senderName = "",
                text = parsed.text,
                rawXml = content.take(4_000),
                mediaKind = mediaKind,
                fileName = parsed.fileName,
                fileSize = parsed.fileSize,
                durationMs = parsed.durationMs,
                mediaBase64 = mediaBase64,
                localPath = mediaUrl
            )
            entries.add(Triple(ex.talker, fwd, chunkFile))
        }
        if (entries.isEmpty()) return 0

        val talker = entries.first().first
        val title = talkerTitle.ifBlank { talker }
        val conv = Conversation(talker, title, talker.endsWith("@chatroom"))
        val batch = ForwardBatch(
            pluginVersion = "1.0.2",
            conversation = conv,
            messages = entries.map { it.second }
        )
        try {
            val intent = Intent(ForwardProtocol.ACTION_RECEIVE_BATCH).apply {
                setPackage(TARGET_PKG)
                putExtra(ForwardProtocol.EXTRA_BATCH_JSON, batch.encode())
                putExtra(ForwardProtocol.EXTRA_SOURCE, ForwardProtocol.SOURCE_WECHAT)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx.sendBroadcast(intent)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG forwardList broadcast failed: ${e.message}")
        }
        entries.forEach { (t, msg, chunk) ->
            chunk?.let {
                sendChunks(
                    ctx,
                    Conversation(t, talkerTitle.ifBlank { t }, t.endsWith("@chatroom")),
                    msg,
                    it,
                    buildString { append(t).append('|').append(msg.effectiveDedupeKey()) }
                )
            }
        }
        WxDownloader.fileLog(
            ctx,
            "forwardList size=${entries.size} ids=${entries.map { it.second.msgId }} " +
                "svr=${entries.map { it.second.msgSvrId }} jsonLen=${batch.encode().length}"
        )
        XposedBridge.log("$TAG forwardList size=${entries.size} talker=$talker title=${title.take(24)}")
        return entries.size
    }

    /** 图片大图已下载时，路径是 image2/<md5[0:2]>/<md5[2:4]>/<md5>.jpg。 */
    /** 按“最近修改 + 大小接近”在 image2 里找原图（md5 对不上时兜底）。 */
    private fun findRecentOriginal(expectedSize: Long): String? {
        if (expectedSize <= 0) return null
        return try {
            val root = File("/data/data/com.tencent.mm/MicroMsg")
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            var best: File? = null
            root.listFiles()?.forEach { d ->
                val img2 = File(d, "image2")
                if (!img2.isDirectory) return@forEach
                img2.walkTopDown().maxDepth(3).forEach { f ->
                    if (!f.isFile || !f.name.endsWith(".jpg") || f.name.startsWith("th_")) return@forEach
                    if (f.lastModified() < cutoff) return@forEach
                    val diff = kotlin.math.abs(f.length() - expectedSize)
                    if (diff < 4096 || diff < expectedSize / 50) {
                        if (best == null || f.lastModified() > best!!.lastModified()) best = f
                    }
                }
            }
            best?.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** 从 th_<md5> 缩略图路径反推 md5。 */
    private fun md5FromThumbPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = File(path).name
        if (!name.startsWith("th_")) return null
        return name.removePrefix("th_").substringBefore('.').takeIf { it.length >= 32 }
    }

    private fun resolveUnderMicroMsg(rel: String): String? {
        if (rel.isBlank()) return null
        // ImgInfo2.bigImgPath 通常只是文件名（如 <md5>.jpg），需按 image2/xx/yy/ 拼
        val candidates = ArrayList<String>()
        candidates.add(rel)
        if (!rel.contains('/')) {
            val stem = rel.substringBefore('.').substringBefore('_')
            if (stem.length >= 4) {
                candidates.add("image2/${stem.take(2)}/${stem.drop(2).take(2)}/$rel")
            }
        }
        return try {
            val root = File("/data/data/com.tencent.mm/MicroMsg")
            root.listFiles()?.forEach { d ->
                candidates.forEach { c ->
                    val f = File(d, c)
                    if (f.isFile && f.length() > 0) return f.absolutePath
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isThumbnailPath(path: String): Boolean {
        val name = File(path).name
        return name.startsWith("th_") || path.contains("/th_")
    }

    private fun findImageByMd5(md5: String): String? {
        if (md5.length < 4) return null
        val root = File("/data/data/com.tencent.mm/MicroMsg")
        val dirs = root.listFiles() ?: return null
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val base = File(dir, "image2/${md5.substring(0, 2)}/${md5.substring(2, 4)}")
            if (!base.isDirectory) return@forEach
            listOf("jpg", "png", "gif", "webp", "heic").forEach { ext ->
                val f = File(base, "$md5.$ext")
                if (f.isFile) return f.absolutePath
            }
            base.listFiles()?.firstOrNull { it.name.startsWith(md5) }?.let { return it.absolutePath }
        }
        return null
    }

    /** 从消息 item view 里找图片控件并压成 JPEG（排除头像小图）。 */
    private fun captureImageFromView(view: android.view.View?): ByteArray? {
        val iv = findBigImageView(view, 0) ?: return null
        val d = iv.drawable ?: return null
        val bmp = try {
            when (d) {
                is android.graphics.drawable.BitmapDrawable -> d.bitmap
                else -> {
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: 200
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: 200
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also {
                        val c = android.graphics.Canvas(it)
                        d.setBounds(0, 0, w, h)
                        d.draw(c)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            var q = 88
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
            while (out.size() > MAX_INLINE_BYTES && q > 40) {
                out.reset()
                q -= 12
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun findBigImageView(v: android.view.View?, depth: Int): android.widget.ImageView? {
        if (v == null || depth > 7) return null
        if (v is android.widget.ImageView && v.drawable != null &&
            v.width >= 120 && v.height >= 120
        ) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                findBigImageView(v.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 本地路径拿不到时，按文件名在微信数据目录里兜底查找（下载过的文件/图片）。 */
    private fun findMediaFile(name: String, kind: String): String? {
        if (name.isBlank()) return null
        val roots = listOf(
            "/data/data/com.tencent.mm/MicroMsg/Download",
            "/data/data/com.tencent.mm/MicroMsg/attachment",
            "/data/data/com.tencent.mm/MicroMsg"
        )
        roots.forEach { root ->
            val f = File(root, name)
            if (f.isFile) return f.absolutePath
        }
        return null
    }

    private fun guessKind(path: String): String {
        // 微信缩略图 th_<md5> 没有扩展名，必须按图片处理
        if (File(path).name.startsWith("th_")) return ForwardProtocol.MEDIA_IMAGE
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
