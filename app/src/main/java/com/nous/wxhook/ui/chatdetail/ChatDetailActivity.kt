package com.nous.wxhook.ui.chatdetail

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nous.wxhook.rootbridge.RootCommandRunner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.nous.wxhook.db.MessageParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun su(cmd: String): String = RootCommandRunner.runSuQuiet(cmd)

data class ChatMessage(
    val msgSvrId: Long, val type: Int,
    val content: String?, val createTime: Long,
    val isSend: Boolean, val imgPath: String?
)

class ChatDetailActivity : AppCompatActivity() {

    internal fun su(cmd: String): String = RootCommandRunner.runSuQuiet(cmd)

    /** Look up nickname from rcontact by wxid */
    private val nickCache = ConcurrentHashMap<String, String>()
    private fun getNickName(wxid: String): String {
        return nickCache.getOrPut(wxid) {
            val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
            val d = "/sdcard/Download/EnMicroMsg.db"
            try {
                val f = File(cacheDir, "nn_${wxid.hashCode()}.sql")
                f.writeText("PRAGMA key='e9cd2ae';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT nickname FROM rcontact WHERE username='$wxid' LIMIT 1;")
                val p = su("$sc '$d' < '${f.absolutePath}'")
                p.lines().lastOrNull { it.isNotBlank() && !it.startsWith("ok") }?.trim() ?: wxid
            } catch (_: Exception) { wxid }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private var talker = ""
    private var nickname = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talker = intent.getStringExtra("talker") ?: ""
        nickname = intent.getStringExtra("nickname") ?: talker
        Log.i("wxhook:ChatDtl","onCreate talker=$talker")
        recyclerView = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ChatDetailActivity) }
        setContentView(recyclerView)
        supportActionBar?.title = nickname
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Search button
        supportActionBar?.setDisplayShowCustomEnabled(true)
        val searchBtn = android.widget.Button(this).apply {
            text = "🔍"; textSize = 16f
            setOnClickListener { showSearchDialog() }
        }
        supportActionBar?.customView = searchBtn
        loadMessages()
    }

    private fun showSearchDialog() {
        val input = android.widget.EditText(this).apply { hint = "在 ${nickname} 中搜索..." }
        android.app.AlertDialog.Builder(this)
            .setTitle("搜索")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) searchInConversation(talker, kw) { results ->
                    if (results.isEmpty()) android.widget.Toast.makeText(this, "未找到", Toast.LENGTH_SHORT).show()
                    else {
                        recyclerView.adapter = MessageAdapter(results, ::fileExists, ::resolveWxPath, ::copyToCache, ::execCmd, cacheDir, nickCache, isGroup = talker.contains("@chatroom"))
                        supportActionBar?.subtitle = "搜索: $kw (${results.size}条)"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun execCmd(cmd: String): String = try {
        su(cmd)
    } catch (_: Exception) { "" }

    private val fileCache = ConcurrentHashMap<String, Boolean>()
    private fun fileExists(wxPath: String): Boolean =
        fileCache.getOrPut(wxPath) { execCmd("test -f '$wxPath' && echo 1").contains("1") }

    private fun resolveWxPath(md5OrPath: String?, fileType: Int): String? {
        if (md5OrPath.isNullOrBlank()) return null
        val md5 = md5OrPath.substringAfter("th_").substringBefore("|").take(32)
        if (md5.length < 32) return null
        val wpid = execCmd("pidof com.tencent.mm")
        if (wpid.isBlank()) return null
        val base = "/proc/${wpid}/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
        return when (fileType) {
            3 -> "$base/image2/${md5.substring(0,2)}/${md5.substring(2,4)}/th_$md5"
            34 -> "$base/voice2/${md5.substring(0,2)}/msg_$md5.amr"
            43 -> "$base/video/$md5"
            else -> "$base/attachment/$md5"
        }
    }

    private fun copyToCache(wxPath: String): String? {
        val name = wxPath.substringAfterLast("/")
        val local = File(cacheDir, name)
        if (local.exists()) return local.absolutePath
        return if (execCmd("cp '$wxPath' '${local.absolutePath}' && echo ok").contains("ok")) local.absolutePath
        else null
    }

    private fun loadMessages() {
        Thread {
            try {
                val key = "e9cd2ae"; val dbPath = "/sdcard/Download/EnMicroMsg.db"
                if (!File(dbPath).exists()) { handler.post { setContentView(TextView(this).apply { text = "数据库不存在"; textSize = 18f }) }; return@Thread }
                if (talker.isEmpty()) { handler.post { setContentView(TextView(this).apply { text = "未指定会话"; textSize = 18f }) }; return@Thread }
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cd_${tag}.sql")
                sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT msgSvrId,type,replace(replace(content,char(10),' '),'|','/'),createTime,isSend,imgPath FROM message WHERE talker='$talker' ORDER BY createTime DESC;")
                val cntFile = File(cacheDir, "cd_cnt_${tag}.sql")
                cntFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT count(*) FROM message WHERE talker='$talker';")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val out = su("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                val lines = out.lines()
                val total = su("$sc '$dbPath' < '${cntFile.absolutePath}'").lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLongOrNull() ?: 0L
                val msgs = mutableListOf<ChatMessage>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 6 && !p[0].startsWith("ok"))
                        msgs.add(ChatMessage(p[0].toLongOrNull()?:0L, p[1].toIntOrNull()?:0, p[2], p[3].toLongOrNull()?:0L, p[4]=="1", p.getOrNull(5)))
                }
                Log.i("wxhook:ChatDtl","exit=0 lines=${lines.size} total=$total parsed=${msgs.size}")
                handler.post {
                    if (msgs.isEmpty()) setContentView(TextView(this).apply { text = "没有消息"; textSize = 18f })
                    else { supportActionBar?.subtitle = "共 $total 条"; recyclerView.adapter = MessageAdapter(msgs, ::fileExists, ::resolveWxPath, ::copyToCache, ::execCmd, cacheDir, nickCache, talker.contains("@chatroom")) }
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatDtl","failed",e)
                handler.post { setContentView(TextView(this).apply { text = "查询失败: ${e.message}"; textSize = 14f }) }
            }
        }.start()
    }

    /** Per-conversation search via sqlcipher */
    private fun searchInConversation(talker: String, keyword: String, callback: (List<ChatMessage>) -> Unit) {
        Thread {
            try {
                val key = "e9cd2ae"; val dbPath = "/sdcard/Download/EnMicroMsg.db"
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "sr_${tag}.sql")
                val safeKw = keyword.replace("'", "''")
                sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT msgSvrId,type,replace(replace(content,char(10),' '),'|','/'),createTime,isSend,imgPath FROM message WHERE talker='$talker' AND content LIKE '%$safeKw%' ORDER BY createTime DESC LIMIT 200;")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val p = su("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                val msgs = mutableListOf<ChatMessage>()
                for (line in p.lines()) { val pt = line.split("|"); if (pt.size >= 6 && !pt[0].startsWith("ok")) msgs.add(ChatMessage(pt[0].toLongOrNull()?:0L, pt[1].toIntOrNull()?:0, pt[2], pt[3].toLongOrNull()?:0L, pt[4]=="1", pt.getOrNull(5))) }
                handler.post { callback(msgs) }
            } catch (_: Exception) { handler.post { callback(emptyList()) } }
        }.start()
    }
}

// ── Adapter ──

class MessageAdapter(
    private val items: List<ChatMessage>,
    private val fileExists: (String) -> Boolean,
    private val resolveWxPath: (String?, Int) -> String?,
    private val copyToCache: (String) -> String?,
    private val execCmd: (String) -> String,
    private val cacheDir: File,
    private val nickCache: MutableMap<String, String>,
    private val isGroup: Boolean
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val handler = Handler(Looper.getMainLooper())

    /** Fetch nickname from rcontact via sqlcipher */
    private fun fetchNickName(wxid: String): String {
        val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
        val d = "/sdcard/Download/EnMicroMsg.db"
        return try {
            val f = File(cacheDir, "nn_${wxid.hashCode()}.sql")
            f.writeText("PRAGMA key='e9cd2ae';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT nickname FROM rcontact WHERE username='$wxid' LIMIT 1;")
            val p = su("$sc '$d' < '${f.absolutePath}'")
            p.lines().lastOrNull { it.isNotBlank() && !it.startsWith("ok") }?.trim() ?: wxid
        } catch (_: Exception) { wxid }
    }

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = MaterialCardView(parent.context).apply {
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(12, 6, 12, 6); layoutParams = lp
            radius = 8f; cardElevation = 1f; setContentPadding(36, 16, 36, 16)
        }
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = items[pos]; val ctx = holder.card.context
        holder.card.removeAllViews(); holder.card.tag = null

        val vert = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Top row
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dir = if (msg.isSend) "→" else "←"
        val parsed = MessageParser.parse(msg.type and 0xFF, msg.content, 0)
        top.addView(TextView(ctx).apply {
            text = "$dir ${typeTag(msg, parsed)}"; textSize = 12f; setTextColor(0xFF6200EE.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(ctx).apply {
            text = if (msg.createTime > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.createTime)) else ""
            textSize = 11f; setTextColor(0x8A000000.toInt()); gravity = Gravity.END
        })
        vert.addView(top)

        when (msg.type and 0xFF) {
            3 -> addImage(vert, ctx, msg)       // image → show thumbnail
            34 -> addVoice(vert, ctx, msg)       // voice
            43 -> addVideo(vert, ctx, msg)       // video → playable
            else -> addTextContent(vert, ctx, msg, parsed)
        }

        holder.card.addView(vert)
    }

    // ── Image ──
    private fun addImage(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val tv = TextView(ctx).apply { text = "  ⏳ 加载图片..."; textSize = 13f; setTextColor(0x8A000000.toInt()); setPadding(0, 8, 0, 0) }
        vert.addView(tv)
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
            scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE
        }
        vert.addView(iv)

        Thread {
            try {
                val md5 = msg.imgPath?.substringAfter("th_")?.substringBefore("|")?.take(32) ?: ""
                if (md5.length < 32) { handlerPost(tv, "  ⚠️ 无效图片路径"); return@Thread }

                val wpid = execCmd("pidof com.tencent.mm")
                if (wpid.isBlank()) { handlerPost(tv, "  ⚠️ 需微信运行中才能加载图片"); return@Thread }

                val base = "/proc/${wpid}/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
                val imgDir = "$base/image2/${md5.substring(0,2)}/${md5.substring(2,4)}"

                // Priority: th_hd > th_ > decrypted .jpg
                var localPath: String? = null
                for ((name, isEnc) in listOf("th_${md5}hd" to false, "th_$md5" to false, "$md5.jpg" to true)) {
                    val full = "$imgDir/$name"
                    if (execCmd("test -f '$full' && echo 1").contains("1")) {
                        val cacheName = if (isEnc) "dec_$md5.jpg" else name
                        val cacheFile = File(cacheDir, cacheName)
                        if (isEnc) {
                            localPath = decryptWxgf(full, cacheFile)
                        } else {
                            if (execCmd("cp '$full' '${cacheFile.absolutePath}' && chmod 644 '${cacheFile.absolutePath}' && echo ok").contains("ok"))
                                localPath = cacheFile.absolutePath
                        }
                        if (localPath != null) break
                    }
                }

                if (localPath == null) {
                    handlerPost(tv, "  ⚠️ 图片已丢失")
                    return@Thread
                }

                val bm = BitmapFactory.decodeFile(localPath)
                android.util.Log.i("wxhook:Img","decodeFile=$localPath size=${File(localPath).length()} bm=${bm != null}")
                handler.post {
                    if (bm != null) {
                        tv.visibility = View.GONE
                        iv.setImageBitmap(bm); iv.visibility = View.VISIBLE
                        iv.setOnClickListener { com.nous.wxhook.ui.viewer.ImagePopup(ctx).show(localPath) }
                    } else {
                        tv.text = "  ⚠️ 图片解码失败（文件损坏）"; tv.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                handlerPost(tv, "  ⚠️ 图片加载异常: ${(e.message?:"").take(50)}")
            }
        }.start()
    }

    /** Decrypt wxgf (encrypted WeChat image) to JPEG */
    private fun decryptWxgf(srcPath: String, dstFile: File): String? {
        return try {
            val tmpFile = File(cacheDir, "tmp_enc_${dstFile.name}")
            if (!execCmd("cp '$srcPath' '${tmpFile.absolutePath}' && chmod 644 '${tmpFile.absolutePath}' && echo ok").contains("ok")) return null
            val encBytes = tmpFile.readBytes(); tmpFile.delete()
            if (encBytes.size < 20) return null
            if (encBytes[0] != 'w'.code.toByte() || encBytes[1] != 'x'.code.toByte()) return null

            // Try multiple JPEG header templates
            val templates = listOf(
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1),  // JFIF
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 2, 0, 0, 1),  // JFIF v1.2
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 1, 1, 0, 0),  // JFIF v1.1
                byteArrayOf(-1, -40, -1, -33, 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1),  // EXIF (FFE1)
                byteArrayOf(-1, -40, -1, -31, 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1),  // EXIF alt
            )

            for (jpegHdr in templates) {
                val key = ByteArray(16) { i -> ((encBytes[4 + i].toInt() and 0xFF) xor (jpegHdr[i].toInt() and 0xFF)).toByte() }
                val check0 = (encBytes[4].toInt() and 0xFF) xor (key[0].toInt() and 0xFF)
                val check1 = (encBytes[5].toInt() and 0xFF) xor (key[1].toInt() and 0xFF)
                if (check0 != 0xFF || check1 != 0xD8) continue

                val dec = ByteArray(encBytes.size - 4) { i ->
                    ((encBytes[4 + i].toInt() and 0xFF) xor (key[i % 16].toInt() and 0xFF)).toByte()
                }
                val eoi = dec.indexOfSlice(byteArrayOf(-1, -39))
                if (eoi < 0) continue
                val final = dec.copyOf(eoi + 2)
                dstFile.writeBytes(final)
                return dstFile.absolutePath
            }
            null  // no template worked
        } catch (e: Exception) { null }
    }

    private fun handlerPost(tv: TextView, msg: String) {
        handler.post { tv.text = msg; tv.visibility = View.VISIBLE }
    }

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
        for (i in 0..size - slice.size) {
            var match = true
            for (j in slice.indices) { if (this[i + j] != slice[j]) { match = false; break } }
            if (match) return i
        }
        return -1
    }

    private fun addVideo(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val tv = TextView(ctx).apply {
            text = "  🎬 [视频]  ⏳ 检查文件..."; textSize = 14f
            setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
        }
        vert.addView(tv)
        Thread {
            try {
                val md5 = msg.imgPath?.substringAfter("th_")?.substringBefore("|")?.take(32) ?: ""
                var videoLocal: String? = null
                if (md5.length >= 32) {
                    val wpid2 = execCmd("pidof com.tencent.mm")
                    if (wpid2.isNotBlank()) {
                        val base2 = "/proc/${wpid2}/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
                        val vPath = "$base2/video/$md5"
                        if (execCmd("test -f '$vPath' && echo 1").contains("1")) videoLocal = copyToCache(vPath)
                    }
                }
                handler.post {
                    if (videoLocal != null) {
                        tv.text = "  ▶️ [视频]  ${md5.take(16)}…\n  👆 点击播放"
                        tv.setOnClickListener { openFile(ctx, videoLocal!!) }
                    } else { tv.text = "  🎬 [视频]\n  ⚠️ 视频文件已丢失" }
                }
            } catch (_: Exception) { handler.post { tv.text = "  🎬 [视频]\n  ⚠️ 检查异常" } }
        }.start()
    }

    private fun addVoice(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val parsed = MessageParser.parse(msg.type and 0xFF, msg.content, 0)
        val tv = TextView(ctx).apply {
            text = "  🎵 [语音] ${parsed.mediaPath?.let { "(${it}ms)" } ?: ""}\n  ⏳ 检查文件..."
            textSize = 14f; setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
        }
        vert.addView(tv)
        Thread {
            try {
                val md5 = msg.imgPath?.substringAfter("th_")?.substringBefore("|")?.take(32) ?: ""
                var voiceLocal: String? = null
                if (md5.length >= 32) {
                    val wpid2 = execCmd("pidof com.tencent.mm")
                    if (wpid2.isNotBlank()) {
                        val base2 = "/proc/${wpid2}/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
                        val vPath = "$base2/voice2/${md5.substring(0,2)}/msg_$md5.amr"
                        if (execCmd("test -f '$vPath' && echo 1").contains("1")) voiceLocal = copyToCache(vPath)
                    }
                }
                handler.post {
                    if (voiceLocal != null) {
                        tv.text = "  🎵 [语音] ${parsed.mediaPath?.let { "(${it}ms)" } ?: ""}\n  👆 点击播放"
                        tv.setOnClickListener { openFile(ctx, voiceLocal!!) }
                    } else { tv.text = "  🎵 [语音]\n  ⚠️ 语音文件已丢失" }
                }
            } catch (_: Exception) { handler.post { tv.text = "  🎵 [语音]\n  ⚠️ 检查异常" } }
        }.start()
    }

    // ── File ──
    private fun addFile(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, fileName: String?) {
        val tv = TextView(ctx).apply {
            text = "  📎 ${fileName?.take(50) ?: "未知文件"}\n  ⏳ 检查文件..."
            textSize = 14f; setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
        }
        vert.addView(tv)
        // File attachments in WeChat 8.0.74+ are stored in encrypted SFS
        // and cannot be directly accessed from disk
        // Show file info from XML content instead
        Thread {
            // Try to extract file info from appattach table via msgSvrId
            val key = "e9cd2ae"; val dbPath = "/sdcard/Download/EnMicroMsg.db"
            val tag = System.currentTimeMillis().toString()
            val sqlFile = File(cacheDir, "fi_${tag}.sql")
            sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT fileName,filePath,fileSize,status FROM appattach WHERE msgInfoId=${msg.msgSvrId} LIMIT 1;")
            val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
            val p = su("$sc '$dbPath' < '${sqlFile.absolutePath}'")
            val infoLine = p.lines().lastOrNull { it.isNotBlank() && !it.startsWith("ok") }
            handler.post {
                if (infoLine != null) {
                    val parts = infoLine.split("|")
                    val fName = fileName?.take(50) ?: "文件"
                    val filePath = parts.getOrNull(1) ?: ""
                    val fileSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val status = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    val sizeStr = if (fileSize > 0) {
                        if (fileSize > 1024*1024) "${"%.1f".format(fileSize.toFloat()/(1024*1024))}MB"
                        else if (fileSize > 1024) "${fileSize/1024}KB"
                        else "${fileSize}B"
                    } else ""
                    tv.text = buildString {
                        append("  📎 $fName")
                        if (sizeStr.isNotEmpty()) append(" ($sizeStr)")
                        append("\n")
                        append(when {
                            status == 199 -> "  ⚠️ 文件未下载（需在微信中打开）"
                            filePath.isNotEmpty() -> "  ✅ 点此打开"
                            else -> "  🔒 文件已加密（SFS存储）"
                        })
                    }
                    if (filePath.isNotEmpty()) tv.setOnClickListener { openFile(ctx, filePath) }
                } else {
                    tv.text = "  📎 ${fileName?.take(50) ?: "文件"}\n  ⚠️ 文件未下载（需在微信中打开）"
                }
            }
        }.start()
    }

    // ── Text content ──
    private fun addTextContent(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, parsed: MessageParser.ParsedMessage) {
        val tv = TextView(ctx).apply { textSize = 14f; setTextColor(0xDE000000.toInt()); setPadding(0, 8, 0, 0) }
        when (msg.type and 0xFF) {
            1 -> {
                val raw = parsed.content ?: "(空)"
                if (isGroup && raw.contains(": ")) {
                    val idx = raw.indexOf(": ")
                    val sender = raw.substring(0, idx)
                    val msg = raw.substring(idx + 2).trim()
                    val nick = nickCache.getOrPut(sender) { fetchNickName(sender) }
                    tv.text = "\n[$nick]\n$msg"
                } else {
                    tv.text = raw
                }
            }
            47 -> tv.text = "[表情] ${parsed.content?.take(100) ?: ""}"
            48 -> { tv.text = "[位置] ${parsed.content?.take(100) ?: ""}"; tv.setTextColor(0xFF6200EE.toInt()) }
            42 -> { tv.text = "[名片] ${parsed.content?.take(100) ?: ""}"; tv.setTextColor(0xFF6200EE.toInt()) }
            49 -> {
                when (parsed.subType) {
                    MessageParser.APP_LINK -> {
                        tv.text = buildString { parsed.title?.let { appendLine(it) }; parsed.url?.take(80)?.let { appendLine(it) } }
                        tv.setTextColor(0xFF6200EE.toInt())
                        parsed.url?.takeUnless { it.isBlank() }?.let { url ->
                            tv.setOnClickListener {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: Exception) { Toast.makeText(ctx, "无法打开链接", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    MessageParser.APP_FILE -> addFile(vert, ctx, msg, parsed.title ?: parsed.fileName)
                    MessageParser.APP_MINI_PROGRAM -> tv.text = "[小程序] ${parsed.title?.take(50) ?: ""}"
                    MessageParser.APP_TRANSFER -> tv.text = "💰 转账\n  金额: ${parsed.typeDesc}\n  ${parsed.title?.take(100) ?: ""}"
                    MessageParser.APP_RED_PACKET -> tv.text = "🧧 红包\n  ${parsed.title?.take(100) ?: ""}"
                    MessageParser.APP_MERGE_FORWARD -> tv.text = "[合并转发]"
                    else -> tv.text = parsed.content?.take(500) ?: "(空)"
                }
            }
            10000 -> { tv.text = parsed.content?.take(500) ?: ""; tv.setTextColor(0x8A000000.toInt()); tv.textSize = 12f }
            10002 -> { tv.text = "[对方撤回了一条消息]"; tv.setTextColor(0x8A000000.toInt()) }
            50   -> { tv.text = "[动画表情]"; tv.setTextColor(0xFF6200EE.toInt()) }
            else -> { val r = msg.content?.take(300) ?: ""; tv.text = r.ifEmpty { "(类型${msg.type})" }; tv.setTextColor(0x8A000000.toInt()) }
        }
        if (tv.text.isNotBlank()) vert.addView(tv)
    }

    private fun typeTag(msg: ChatMessage, parsed: MessageParser.ParsedMessage): String = when (msg.type and 0xFF) {
        1 -> "📝 文本"; 3 -> "🖼 图片"; 34 -> "🎵 语音"; 43 -> "🎬 视频"
        47 -> "😊 表情"; 48 -> "📍 位置"; 42 -> "👤 名片"
        49 -> when (parsed.subType) {
            MessageParser.APP_LINK -> "🔗 链接"; MessageParser.APP_FILE -> "📎 文件"
            MessageParser.APP_MINI_PROGRAM -> "🧩 小程序"; MessageParser.APP_MUSIC -> "🎵 音乐"
            MessageParser.APP_MERGE_FORWARD -> "💬 合并转发"; MessageParser.APP_LOCATION -> "📍 实时位置"
            MessageParser.APP_TRANSFER -> "💰 转账"; MessageParser.APP_RED_PACKET -> "🧧 红包"
            else -> "📦 ${parsed.typeDesc}"
        }
        10000 -> "ℹ️ 系统"; 10002 -> "↩️ 撤回"; else -> "❓ 类型${msg.type}"
    }

    private fun openFile(ctx: android.content.Context, localPath: String) {
        val file = File(localPath)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
            val mime = when {
                localPath.endsWith(".mp4")||localPath.endsWith(".avi") -> "video/*"
                localPath.endsWith(".amr")||localPath.endsWith(".mp3") -> "audio/*"
                localPath.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)")) -> "image/*"
                else -> "*/*"
            }
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { Toast.makeText(ctx, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun getItemCount() = items.size
}