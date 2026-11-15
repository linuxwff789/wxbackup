package com.nous.wxhook.xposed.forward

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.nous.wxshared.ForwardProtocol
import com.nous.wxshared.ForwardProtocol.Conversation
import com.nous.wxshared.ForwardProtocol.ForwardBatch
import com.nous.wxshared.ForwardProtocol.ForwardMessage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 原图自动补全。
 *
 * 微信收到图片时只缓存缩略图，原图要打开预览才会下载。
 * 保存到笔记时如果原图不存在，就自动打开一次预览触发下载，
 * 轮询到 image2/<md5>.jpg 后把大图重新发一份（同一 msgId），笔记端按去重键更新。
 */
object OriginalFetcher {

    private const val TAG = "[wxhook:Orig]"
    private const val MAX_BYTES = 600 * 1024L
    private const val POLL_INTERVAL = 400L
    private const val MAX_ATTEMPTS = 25

    private data class Pending(
        val md5: String,
        val msgId: Long,
        val msgSvrId: Long,
        val talker: String,
        val talkerTitle: String,
        val isSend: Boolean,
        val createTime: Long,
        val type: Int,
        val fileName: String
    )

    private val queue = ConcurrentHashMap<String, Pending>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        try {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        if (queue.isEmpty()) return
                        val name = act.javaClass.name
                        if (!name.contains("gallery", true) && !name.contains("ImageGallery", true)) return
                        XposedBridge.log("$TAG gallery resumed: $name")
                        poll(act)
                    }
                }
            )
            XposedBridge.log("$TAG installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG install failed: ${e.message}")
        }
    }

    fun schedule(
        ctx: Context?,
        view: View?,
        md5: String,
        msgId: Long,
        msgSvrId: Long,
        talker: String,
        talkerTitle: String,
        isSend: Boolean,
        createTime: Long,
        type: Int,
        fileName: String
    ) {
        if (md5.length < 8 || view == null || ctx == null) return
        if (queue.containsKey(md5)) return
        queue[md5] = Pending(md5, msgId, msgSvrId, talker, talkerTitle, isSend, createTime, type, fileName)
        XposedBridge.log("$TAG scheduled md5=$md5")
        main.postDelayed({
            val target = findClickable(view, 0)
            XposedBridge.log("$TAG open preview via ${target?.javaClass?.name}")
            runCatching { target?.performClick() }
        }, 900)
    }

    private fun findClickable(v: View?, depth: Int): View? {
        if (v == null || depth > 8) return null
        if (v.isClickable && v.width >= 180 && v.height >= 180) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findClickable(v.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun poll(act: Activity) {
        var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                attempts++
                val found = ArrayList<Pair<Pending, File>>()
                queue.values.forEach { p ->
                    findOriginal(p.md5)?.let { found.add(p to it) }
                }
                if (found.isNotEmpty()) {
                    found.forEach { (p, file) ->
                        publish(act, p, file)
                        queue.remove(p.md5)
                    }
                    if (queue.isEmpty()) {
                        XposedBridge.log("$TAG all originals fetched, closing gallery")
                        runCatching { act.finish() }
                        return
                    }
                }
                if (attempts < MAX_ATTEMPTS) {
                    main.postDelayed(this, POLL_INTERVAL)
                } else {
                    XposedBridge.log("$TAG timeout, pending=${queue.keys}")
                    queue.clear()
                    runCatching { act.finish() }
                }
            }
        }
        main.postDelayed(runnable, 500)
    }

    private fun publish(act: Activity, p: Pending, file: File) {
        try {
            val len = file.length()
            if (len <= 0) return
            if (len > MAX_BYTES) {
                val msg = ForwardMessage(
                    msgId = p.msgId, msgSvrId = p.msgSvrId,
                    type = if (p.type > 0) p.type else 3,
                    createTime = p.createTime, isSend = p.isSend,
                    text = "[图片]", mediaKind = ForwardProtocol.MEDIA_IMAGE,
                    fileName = p.fileName.ifBlank { file.name }, fileSize = len
                )
                val ok = ForwardSender.sendChunks(
                    act,
                    Conversation(p.talker, p.talkerTitle.ifBlank { p.talker }, p.talker.endsWith("@chatroom")),
                    msg,
                    file,
                    buildString { append(p.talker).append('|').append(msg.effectiveDedupeKey()) }
                )
                XposedBridge.log("$TAG published original via chunks md5=${p.md5} size=$len ok=$ok")
                return
            }
            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val msg = ForwardMessage(
                msgId = p.msgId,
                msgSvrId = p.msgSvrId,
                type = if (p.type > 0) p.type else 3,
                subType = 0,
                createTime = p.createTime,
                isSend = p.isSend,
                text = "[图片]",
                mediaKind = ForwardProtocol.MEDIA_IMAGE,
                mediaBase64 = b64,
                fileName = p.fileName.ifBlank { file.name },
                fileSize = len
            )
            val batch = ForwardBatch(
                pluginVersion = "1.0.0",
                conversation = Conversation(
                    talker = p.talker,
                    title = p.talkerTitle.ifBlank { p.talker },
                    isGroup = p.talker.endsWith("@chatroom")
                ),
                messages = listOf(msg)
            )
            val json = batch.encode()
            val intent = Intent(ForwardProtocol.ACTION_RECEIVE_BATCH).apply {
                setPackage(ForwardSender.TARGET_PKG)
                putExtra(ForwardProtocol.EXTRA_BATCH_JSON, json)
                putExtra(ForwardProtocol.EXTRA_SOURCE, ForwardProtocol.SOURCE_WECHAT)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            act.sendBroadcast(intent)
            XposedBridge.log("$TAG published original md5=${p.md5} size=$len")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG publish failed: ${e.message}")
        }
    }

    private fun findOriginal(md5: String): File? {
        if (md5.length < 4) return null
        val root = File("/data/data/com.tencent.mm/MicroMsg")
        val dirs = root.listFiles() ?: return null
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val base = File(dir, "image2/${md5.substring(0, 2)}/${md5.substring(2, 4)}")
            if (!base.isDirectory) return@forEach
            base.listFiles()?.firstOrNull { it.name.startsWith(md5) && it.length() > 0 }?.let { return it }
        }
        return null
    }
}
