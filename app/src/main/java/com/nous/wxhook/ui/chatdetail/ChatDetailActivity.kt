package com.nous.wxhook.ui.chatdetail

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.db.MessageParser
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.ui.M3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun su(cmd: String): String = RootGateways.runQuiet(cmd)

data class ChatMessage(
    val msgSvrId: Long,
    val type: Int,
    val content: String?,
    val createTime: Long,
    val isSend: Boolean,
    val imgPath: String?
)

class ChatDetailActivity : AppCompatActivity() {

    private val nickCache = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private var talker = ""
    private var nickname = ""
    private var dbPath = "/sdcard/Download/EnMicroMsg.db"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talker = intent.getStringExtra("talker") ?: ""
        nickname = intent.getStringExtra("nickname") ?: talker
        dbPath = intent.getStringExtra("dbPath") ?: "/sdcard/Download/EnMicroMsg.db"

        supportActionBar?.title = nickname
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowCustomEnabled(true)

        // Search button in action bar
        val searchBtn = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = "🔍"
            textSize = 16f
            setOnClickListener { showSearchDialog() }
        }
        supportActionBar?.customView = searchBtn

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatDetailActivity)
            setPadding(M3.dp(this@ChatDetailActivity, 0), M3.dp(this@ChatDetailActivity, 8), 0, 0)
        }
        setContentView(recyclerView)
        loadMessages()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showSearchDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "在 ${nickname} 中搜索..."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("搜索")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val kw = input.text?.toString()?.trim() ?: ""
                if (kw.isNotEmpty()) searchInConversation(talker, kw) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(this, "未找到", Toast.LENGTH_SHORT).show()
                    } else {
                        recyclerView.adapter = MessageAdapter(
                            results, this@ChatDetailActivity, nickCache
                        )
                        supportActionBar?.subtitle = "搜索: $kw (${results.size}条)"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadMessages() {
        Thread {
            try {
                val key = "e9cd2ae"
                if (!File(dbPath).exists()) {
                    handler.post { setContentView(M3.body(this@ChatDetailActivity, "数据库不存在").apply {
                        gravity = Gravity.CENTER
                    }) }
                    return@Thread
                }
                if (talker.isEmpty()) {
                    handler.post { setContentView(M3.body(this@ChatDetailActivity, "未指定会话").apply {
                        gravity = Gravity.CENTER
                    }) }
                    return@Thread
                }

                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cd_${tag}.sql")
                val cntFile = File(cacheDir, "cd_cnt_${tag}.sql")
                val safeTalker = talker.replace("'", "''")

                sqlFile.writeText(
                    "PRAGMA key='$key';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT msgSvrId,type,replace(replace(content,char(10),' '),'|','/')," +
                    "createTime,isSend,imgPath " +
                    "FROM message WHERE talker='$safeTalker' ORDER BY createTime DESC LIMIT 500;"
                )
                cntFile.writeText(
                    "PRAGMA key='$key';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT count(*) FROM message WHERE talker='$safeTalker';"
                )

                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:" +
                         "/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val out = su("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                val lines = out.lines()
                val total = su("$sc '$dbPath' < '${cntFile.absolutePath}'")
                    .lines().lastOrNull { it.all { c -> c.isDigit() } }
                    ?.toLongOrNull() ?: 0L

                sqlFile.delete()
                cntFile.delete()

                val msgs = mutableListOf<ChatMessage>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 6 && !p[0].startsWith("ok")) {
                        msgs.add(
                            ChatMessage(
                                p[0].toLongOrNull() ?: 0L,
                                p[1].toIntOrNull() ?: 0,
                                p[2],
                                p[3].toLongOrNull() ?: 0L,
                                p[4] == "1",
                                p.getOrNull(5)
                            )
                        )
                    }
                }

                handler.post {
                    if (msgs.isEmpty()) {
                        setContentView(M3.body(this@ChatDetailActivity, "没有消息").apply {
                            gravity = Gravity.CENTER
                        })
                    } else {
                        supportActionBar?.subtitle = "共 $total 条"
                        recyclerView.adapter = MessageAdapter(msgs, this@ChatDetailActivity, nickCache)
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    setContentView(M3.body(this@ChatDetailActivity, "查询失败: ${e.message}").apply {
                        gravity = Gravity.CENTER
                    })
                }
            }
        }.start()
    }

    private fun searchInConversation(talker: String, keyword: String, callback: (List<ChatMessage>) -> Unit) {
        Thread {
            try {
                val key = "e9cd2ae"
                val dbPath = "/sdcard/Download/EnMicroMsg.db"
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "sr_${tag}.sql")
                val safeKw = keyword.replace("'", "''")
                val safeTalker = talker.replace("'", "''")
                sqlFile.writeText(
                    "PRAGMA key='$key';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT msgSvrId,type,replace(replace(content,char(10),' '),'|','/')," +
                    "createTime,isSend,imgPath " +
                    "FROM message WHERE talker='$safeTalker' AND content LIKE '%$safeKw%' " +
                    "ORDER BY createTime DESC LIMIT 200;"
                )
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:" +
                         "/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val p = su("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                sqlFile.delete()
                val msgs = mutableListOf<ChatMessage>()
                for (line in p.lines()) {
                    val pt = line.split("|")
                    if (pt.size >= 6 && !pt[0].startsWith("ok")) {
                        msgs.add(
                            ChatMessage(
                                pt[0].toLongOrNull() ?: 0L,
                                pt[1].toIntOrNull() ?: 0,
                                pt[2],
                                pt[3].toLongOrNull() ?: 0L,
                                pt[4] == "1",
                                pt.getOrNull(5)
                            )
                        )
                    }
                }
                handler.post { callback(msgs) }
            } catch (_: Exception) {
                handler.post { callback(emptyList()) }
            }
        }.start()
    }
}

// ═══════════════════════════════════════════
// MD3 Message Adapter — Bubbles with style
// ═══════════════════════════════════════════

class MessageAdapter(
    private val items: List<ChatMessage>,
    private val activity: ChatDetailActivity,
    private val nickCache: ConcurrentHashMap<String, String>
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val handler = Handler(Looper.getMainLooper())
    private val cacheDir = activity.cacheDir

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = MaterialCardView(
            parent.context,
            null,
            com.google.android.material.R.attr.materialCardViewElevatedStyle
        ).apply {
            val lp = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(
                M3.dp(parent.context, 16),
                M3.dp(parent.context, 4),
                M3.dp(parent.context, 16),
                M3.dp(parent.context, 4)
            )
            layoutParams = lp
            radius = M3.dp(parent.context, 12).toFloat()
            setContentPadding(M3.dp(parent.context, 16), M3.dp(parent.context, 12),
                M3.dp(parent.context, 16), M3.dp(parent.context, 12))
        }
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = items[pos]
        val ctx = holder.card.context
        holder.card.removeAllViews()
        holder.card.tag = null

        val vert = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Top row: direction + type + time ──
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val parsed = MessageParser.parse(msg.type and 0xFF, msg.content, 0)
        val dir = if (msg.isSend) "→ 我" else "←"

        top.addView(
            MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
                text = "$dir ${typeTag(msg, parsed)}"
                setTextColor(if (msg.isSend) M3.colorPrimary(ctx) else M3.onSurfaceVariant(ctx))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        top.addView(
            MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
                text = if (msg.createTime > 0L)
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.createTime))
                else ""
                setTextColor(M3.onSurfaceVariant(ctx))
            }
        )
        vert.addView(top)

        // ── Content area ──
        when (msg.type and 0xFF) {
            3 -> addImage(vert, ctx, msg)
            34 -> addVoice(vert, ctx, msg, parsed)
            43 -> addVideo(vert, ctx, msg)
            else -> addTextContent(vert, ctx, msg, parsed)
        }

        holder.card.addView(vert)

        // Style sent vs received messages with M3 colors
        if (msg.isSend) {
            holder.card.setCardBackgroundColor(M3.colorPrimaryContainer(ctx))
            holder.card.strokeColor = android.graphics.Color.TRANSPARENT
            holder.card.strokeWidth = 0
        } else {
            holder.card.setCardBackgroundColor(M3.colorSurface(ctx))
            holder.card.strokeColor = M3.onSurfaceVariant(ctx)
            holder.card.strokeWidth = 1
            holder.card.strokeColor = (M3.onSurfaceVariant(ctx) and 0x00FFFFFF) or (0x18 shl 24)
        }
    }

    // ── Image message ──
    private fun addImage(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
            text = "⏳ 加载图片..."
            setTextColor(M3.onSurfaceVariant(ctx))
            setPadding(0, M3.dp(ctx, 8), 0, 0)
        }
        vert.addView(tv)

        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, M3.dp(ctx, 300)
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        vert.addView(iv)

        Thread {
            try {
                val md5 = msg.imgPath?.substringAfter("th_")?.substringBefore("|")?.take(32) ?: ""
                if (md5.length < 32) {
                    handler.post { tv.text = "⚠️ 无效图片路径"; tv.visibility = View.VISIBLE }
                    return@Thread
                }

                val wpid = su("pidof com.tencent.mm")
                if (wpid.isBlank()) {
                    handler.post { tv.text = "⚠️ 需微信运行中才能加载图片"; tv.visibility = View.VISIBLE }
                    return@Thread
                }

                val base = "/proc/${wpid}/root/data/data/com.tencent.mm/MicroMsg/" +
                           "6d1f34a5edc49e8b6d238141b2d004f3"
                val imgDir = "$base/image2/${md5.substring(0, 2)}/${md5.substring(2, 4)}"

                var localPath: String? = null
                for ((name, isEnc) in listOf("th_${md5}hd" to false, "th_$md5" to false, "$md5.jpg" to true)) {
                    val full = "$imgDir/$name"
                    if (su("test -f '$full' && echo 1").contains("1")) {
                        val cacheName = if (isEnc) "dec_$md5.jpg" else name
                        val cacheFile = File(cacheDir, cacheName)
                        if (isEnc) {
                            localPath = decryptWxgf(full, cacheFile)
                        } else {
                            if (su("cp '$full' '${cacheFile.absolutePath}' && chmod 644 '${cacheFile.absolutePath}' && echo ok").contains("ok"))
                                localPath = cacheFile.absolutePath
                        }
                        if (localPath != null) break
                    }
                }

                if (localPath == null) {
                    handler.post { tv.text = "⚠️ 图片已丢失"; tv.visibility = View.VISIBLE }
                    return@Thread
                }

                val bm = BitmapFactory.decodeFile(localPath)
                handler.post {
                    if (bm != null) {
                        tv.visibility = View.GONE
                        iv.setImageBitmap(bm)
                        iv.visibility = View.VISIBLE
                        iv.setOnClickListener {
                            com.nous.wxhook.ui.viewer.ImagePopup(ctx).show(localPath)
                        }
                    } else {
                        tv.text = "⚠️ 图片解码失败（文件损坏）"
                        tv.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                handler.post { tv.text = "⚠️ 图片异常: ${(e.message ?: "").take(50)}"; tv.visibility = View.VISIBLE }
            }
        }.start()
    }

    // ── Voice message ──
    private fun addVoice(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, parsed: MessageParser.ParsedMessage) {
        val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
            text = "🎵 [语音] ${parsed.mediaPath?.let { "(${it}ms)" } ?: ""}"
            setTextColor(M3.colorPrimary(ctx))
            setPadding(0, M3.dp(ctx, 8), 0, 0)
        }
        vert.addView(tv)

        val loadingTv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
            text = "⏳ 检查文件..."
            setTextColor(M3.onSurfaceVariant(ctx))
        }
        vert.addView(loadingTv)

        Thread {
            try {
                val md5 = msg.imgPath?.substringAfter("th_")?.substringBefore("|")?.take(32) ?: ""
                var voiceLocal: String? = null
                if (md5.length >= 32) {
                    val wpid2 = su("pidof com.tencent.mm")
                    if (wpid2.isNotBlank()) {
                        val base2 = "/proc/${wpid2}/root/data/data/com.tencent.mm/MicroMsg/" +
                                    "6d1f34a5edc49e8b6d238141b2d004f3"
                        val vPath = "$base2/voice2/${md5.substring(0, 2)}/msg_$md5.amr"
                        if (su("test -f '$vPath' && echo 1").contains("1")) {
                            val local = File(cacheDir, "v_$md5.amr")
                            if (su("cp '$vPath' '${local.absolutePath}' && echo ok").contains("ok"))
                                voiceLocal = local.absolutePath
                        }
                    }
                }
                val finalVoice = voiceLocal
                handler.post {
                    if (finalVoice != null) {
                        loadingTv.text = "👆 点击播放"
                        tv.setOnClickListener { openFile(ctx, finalVoice) }
                    } else {
                        loadingTv.text = "⚠️ 语音文件已丢失"
                    }
                }
            } catch (_: Exception) {
                handler.post { loadingTv.text = "⚠️ 检查异常" }
            }
        }.start()
    }

    // ── Video message ──
    private fun addVideo(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
            text = "🎬 [视频]"
            setTextColor(M3.colorPrimary(ctx))
            setPadding(0, M3.dp(ctx, 8), 0, 0)
        }
        vert.addView(tv)

        val loadingTv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
            text = "⏳ 检查文件..."
            setTextColor(M3.onSurfaceVariant(ctx))
        }
        vert.addView(loadingTv)

        Thread {
            try {
                val md5 = msg.imgPath?.substringAfter("th_")?.substringBefore("|")?.take(32) ?: ""
                var videoLocal: String? = null
                if (md5.length >= 32) {
                    val wpid2 = su("pidof com.tencent.mm")
                    if (wpid2.isNotBlank()) {
                        val base2 = "/proc/${wpid2}/root/data/data/com.tencent.mm/MicroMsg/" +
                                    "6d1f34a5edc49e8b6d238141b2d004f3"
                        val vPath = "$base2/video/$md5"
                        if (su("test -f '$vPath' && echo 1").contains("1")) {
                            val local = File(cacheDir, "vid_$md5")
                            if (su("cp '$vPath' '${local.absolutePath}' && echo ok").contains("ok"))
                                videoLocal = local.absolutePath
                        }
                    }
                }
                val finalVideo = videoLocal
                handler.post {
                    if (finalVideo != null) {
                        loadingTv.text = "▶️ 点击播放"
                        tv.setOnClickListener { openFile(ctx, finalVideo) }
                    } else {
                        loadingTv.text = "⚠️ 视频文件已丢失"
                    }
                }
            } catch (_: Exception) {
                handler.post { loadingTv.text = "⚠️ 检查异常" }
            }
        }.start()
    }

    // ── Text content ──
    private fun addTextContent(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, parsed: MessageParser.ParsedMessage) {
        val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
            setPadding(0, M3.dp(ctx, 8), 0, 0)
        }

        when (msg.type and 0xFF) {
            1 -> {
                val raw = parsed.content ?: "(空)"
                if (raw.contains(": ") && raw.length > 30 && raw.indexOf(": ") < 50) {
                    val idx = raw.indexOf(": ")
                    val sender = raw.substring(0, idx)
                    val msgText = raw.substring(idx + 2).trim()
                    val nick = nickCache.getOrPut(sender) { fetchNickName(sender) }
                    tv.text = "[$nick]\n$msgText"
                } else {
                    tv.text = raw
                }
            }
            47 -> tv.text = "[表情] ${parsed.content?.take(100) ?: ""}"
            48 -> { tv.text = "[位置] ${parsed.content?.take(100) ?: ""}"; tv.setTextColor(M3.colorPrimary(ctx)) }
            42 -> { tv.text = "[名片] ${parsed.content?.take(100) ?: ""}"; tv.setTextColor(M3.colorPrimary(ctx)) }
            49 -> {
                when (parsed.subType) {
                    MessageParser.APP_LINK -> {
                        tv.text = buildString {
                            parsed.title?.let { appendLine(it) }
                            parsed.url?.take(80)?.let { appendLine(it) }
                        }
                        tv.setTextColor(M3.colorPrimary(ctx))
                        parsed.url?.takeUnless { it.isBlank() }?.let { url ->
                            tv.setOnClickListener {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: Exception) { Toast.makeText(ctx, "无法打开链接", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    MessageParser.APP_FILE -> {
                        vert.addView(tv)
                        addFile(vert, ctx, msg, parsed.title ?: parsed.fileName)
                        return
                    }
                    MessageParser.APP_MINI_PROGRAM -> tv.text = "[小程序] ${parsed.title?.take(50) ?: ""}"
                    MessageParser.APP_TRANSFER -> tv.text = "💰 转账: ${parsed.typeDesc}"
                    MessageParser.APP_RED_PACKET -> tv.text = "🧧 红包: ${parsed.title?.take(100) ?: ""}"
                    MessageParser.APP_MERGE_FORWARD -> tv.text = "[合并转发]"
                    else -> tv.text = parsed.content?.take(500) ?: "(空)"
                }
            }
            10000 -> { tv.text = parsed.content?.take(500) ?: ""; tv.setTextColor(M3.onSurfaceVariant(ctx)); tv.textSize = 12f }
            10002 -> { tv.text = "[对方撤回了一条消息]"; tv.setTextColor(M3.onSurfaceVariant(ctx)) }
            50 -> tv.text = "[动画表情]"
            else -> tv.text = msg.content?.take(300)?.ifEmpty { "(类型${msg.type})" } ?: "(空)"
        }
        if (tv.text.isNotBlank()) vert.addView(tv)
    }

    // ── File attachment ──
    private fun addFile(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, fileName: String?) {
        val fileCard = MaterialCardView(
            ctx, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, M3.dp(ctx, 8), 0, 0) }
            radius = M3.dp(ctx, 8).toFloat()
            setContentPadding(M3.dp(ctx, 12), M3.dp(ctx, 8), M3.dp(ctx, 12), M3.dp(ctx, 8))
        }
        fileCard.addView(
            MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
                text = "📎 ${fileName?.take(50) ?: "未知文件"}"
                setTextColor(M3.colorPrimary(ctx))
            }
        )

        val infoTv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
            text = "⏳ 查询文件信息..."
            setTextColor(M3.onSurfaceVariant(ctx))
        }
        fileCard.addView(infoTv)
        vert.addView(fileCard)

        Thread {
            try {
                val key = "e9cd2ae"
                val dbPath = "/sdcard/Download/EnMicroMsg.db"
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "fi_${tag}.sql")
                sqlFile.writeText(
                    "PRAGMA key='$key';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT fileName,filePath,fileSize,status " +
                    "FROM appattach WHERE msgInfoId=${msg.msgSvrId} LIMIT 1;"
                )
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:" +
                         "/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val p = su("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                sqlFile.delete()
                val infoLine = p.lines().lastOrNull { it.isNotBlank() && !it.startsWith("ok") }
                handler.post {
                    if (infoLine != null) {
                        val parts = infoLine.split("|")
                        val fName = parts.getOrNull(0)?.take(50) ?: fileName?.take(50) ?: "文件"
                        val filePath = parts.getOrNull(1) ?: ""
                        val fileSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        val status = parts.getOrNull(3)?.toIntOrNull() ?: 0
                        val sizeStr = if (fileSize > 0) {
                            when {
                                fileSize > 1024 * 1024 -> "%.1f MB".format(fileSize.toFloat() / (1024 * 1024))
                                fileSize > 1024 -> "${fileSize / 1024} KB"
                                else -> "$fileSize B"
                            }
                        } else ""
                        val info = when {
                            status == 199 -> "⚠️ 未下载（需在微信中打开）"
                            filePath.isNotEmpty() -> "💾 已下载"
                            else -> "🔒 SFS 加密存储"
                        }
                        infoTv.text = buildString {
                            if (sizeStr.isNotEmpty()) append("$sizeStr · ")
                            append(info)
                        }
                        if (filePath.isNotEmpty()) {
                            fileCard.setOnClickListener { openFile(ctx, filePath) }
                        }
                    } else {
                        infoTv.text = "⚠️ 文件未下载（需在微信中打开）"
                    }
                }
            } catch (_: Exception) {
                handler.post { infoTv.text = "⚠️ 查询失败" }
            }
        }.start()
    }

    // ── Utilities ──
    private fun fetchNickName(wxid: String): String {
        return nickCache.getOrPut(wxid) {
            val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:" +
                     "/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
            val d = "/sdcard/Download/EnMicroMsg.db"
            try {
                val f = File(cacheDir, "nn_${wxid.hashCode()}.sql")
                f.writeText(
                    "PRAGMA key='e9cd2ae';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT nickname FROM rcontact WHERE username='$wxid' LIMIT 1;"
                )
                val p = su("$sc '$d' < '${f.absolutePath}'")
                p.lines().lastOrNull { it.isNotBlank() && !it.startsWith("ok") }?.trim() ?: wxid
            } catch (_: Exception) { wxid }
        }
    }

    private fun openFile(ctx: android.content.Context, localPath: String) {
        val file = File(localPath)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.provider", file
            )
            val mime = when {
                localPath.endsWith(".mp4") || localPath.endsWith(".avi") -> "video/*"
                localPath.endsWith(".amr") || localPath.endsWith(".mp3") -> "audio/*"
                localPath.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)")) -> "image/*"
                else -> "*/*"
            }
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(ctx, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decryptWxgf(srcPath: String, dstFile: File): String? {
        return try {
            val tmpFile = File(cacheDir, "tmp_enc_${dstFile.name}")
            if (!su("cp '$srcPath' '${tmpFile.absolutePath}' && chmod 644 '${tmpFile.absolutePath}' && echo ok").contains("ok")) return null
            val encBytes = tmpFile.readBytes()
            tmpFile.delete()
            if (encBytes.size < 20) return null
            if (encBytes[0] != 'w'.code.toByte() || encBytes[1] != 'x'.code.toByte()) return null

            val templates = listOf(
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1),
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 2, 0, 0, 1),
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 1, 1, 0, 0),
                byteArrayOf(-1, -40, -1, -33, 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1),
                byteArrayOf(-1, -40, -1, -31, 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1),
            )

            for (jpegHdr in templates) {
                val key = ByteArray(16) { i ->
                    ((encBytes[4 + i].toInt() and 0xFF) xor (jpegHdr[i].toInt() and 0xFF)).toByte()
                }
                val check0 = (encBytes[4].toInt() and 0xFF) xor (key[0].toInt() and 0xFF)
                val check1 = (encBytes[5].toInt() and 0xFF) xor (key[1].toInt() and 0xFF)
                if (check0 != 0xFF || check1 != 0xD8) continue

                val dec = ByteArray(encBytes.size - 4) { i ->
                    ((encBytes[4 + i].toInt() and 0xFF) xor (key[i % 16].toInt() and 0xFF)).toByte()
                }
                val eoi = dec.indexOfSlice(byteArrayOf(-1, -39))
                if (eoi < 0) continue
                dstFile.writeBytes(dec.copyOf(eoi + 2))
                return dstFile.absolutePath
            }
            null
        } catch (e: Exception) { null }
    }

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
        for (i in 0..size - slice.size) {
            var match = true
            for (j in slice.indices) { if (this[i + j] != slice[j]) { match = false; break } }
            if (match) return i
        }
        return -1
    }

    private fun typeTag(msg: ChatMessage, parsed: MessageParser.ParsedMessage): String = when (msg.type and 0xFF) {
        1 -> "📝 文本"
        3 -> "🖼 图片"
        34 -> "🎵 语音"
        43 -> "🎬 视频"
        47 -> "😊 表情"
        48 -> "📍 位置"
        42 -> "👤 名片"
        49 -> when (parsed.subType) {
            MessageParser.APP_LINK -> "🔗 链接"
            MessageParser.APP_FILE -> "📎 文件"
            MessageParser.APP_MINI_PROGRAM -> "🧩 小程序"
            MessageParser.APP_MUSIC -> "🎵 音乐"
            MessageParser.APP_MERGE_FORWARD -> "💬 合并转发"
            MessageParser.APP_TRANSFER -> "💰 转账"
            MessageParser.APP_RED_PACKET -> "🧧 红包"
            else -> "📦 ${parsed.typeDesc}"
        }
        10000 -> "ℹ️ 系统"
        10002 -> "↩️ 撤回"
        else -> "❓ 类型${msg.type}"
    }

    override fun getItemCount() = items.size
}
