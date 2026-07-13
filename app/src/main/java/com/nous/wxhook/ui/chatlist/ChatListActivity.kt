package com.nous.wxhook.ui.chatlist

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class ChatConversation(
    val username: String, val nickname: String,
    val unReadCount: Int, val conversationTime: Long, val typeTag: String
)
data class SectionItem(val isHeader: Boolean, val title: String = "", val conv: ChatConversation? = null)

class ChatListActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = androidx.constraintlayout.widget.ConstraintLayout(this)
        recyclerView = RecyclerView(this).apply { id = View.generateViewId() }
        recyclerView.layoutManager = LinearLayoutManager(this)
        progressBar = CircularProgressIndicator(this, null,
            com.google.android.material.R.attr.circularProgressIndicatorStyle).apply {
            id = View.generateViewId(); isIndeterminate = true
            layoutParams = ViewGroup.LayoutParams(96, 96)
        }
        root.addView(recyclerView); root.addView(progressBar); setContentView(root)
        supportActionBar?.title = "消息"
        loadConversations()
    }

    private fun typeLabel(t: String) = when (t) {
        "group" -> "👥 群聊"; "official" -> "📢 公众号"; else -> "👤 联系人"
    }

    private fun loadConversations() {
        Thread {
            try {
                val key = "e9cd2ae"; val dbPath = "/sdcard/Download/EnMicroMsg.db"
                if (!File(dbPath).exists()) {
                    handler.post { progressBar.visibility = View.GONE; setContentView(TextView(this).apply { text = "数据库不存在"; textSize = 18f }) }; return@Thread }
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cl_${tag}.sql")
                sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT c.username,IFNULL(NULLIF(r.conRemark,''),IFNULL(r.nickname,c.username)),c.unReadCount,c.conversationTime,CASE WHEN c.username LIKE '%@chatroom' THEN 'group' WHEN c.username LIKE '%@app' OR c.username LIKE 'gh_%' THEN 'official' ELSE 'contact' END FROM rconversation c LEFT JOIN rcontact r ON c.username=r.username ORDER BY c.conversationTime DESC LIMIT 200;")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val out = RootCommandRunner.runSuQuiet("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                val lines = out.lines(); sqlFile.delete()
                Log.i("wxhook:ChatList","lines=${lines.size}")
                val convs = mutableListOf<ChatConversation>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 5 && !p[0].startsWith("ok"))
                        convs.add(ChatConversation(p[0], p[1], p[2].toIntOrNull()?:0, p.getOrNull(3)?.toLongOrNull()?:0L, p[4]))
                }
                Log.i("wxhook:ChatList","parsed ${convs.size} conversations")
                val groups = mutableListOf<SectionItem>()
                for (type in listOf("contact", "group", "official")) {
                    val items = convs.filter { it.typeTag == type }
                    if (items.isNotEmpty()) {
                        groups.add(SectionItem(isHeader = true, title = typeLabel(type) + " (${items.size})"))
                        items.forEach { groups.add(SectionItem(isHeader = false, conv = it)) }
                    }
                }
                handler.post {
                    progressBar.visibility = View.GONE
                    if (convs.isEmpty()) setContentView(TextView(this).apply { text = "没有会话数据"; textSize = 18f })
                    else recyclerView.adapter = SectionAdapter(groups) { conv ->
                        startActivity(Intent(this@ChatListActivity, com.nous.wxhook.ui.chatdetail.ChatDetailActivity::class.java).apply {
                            putExtra("talker", conv.username); putExtra("nickname", conv.nickname)
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatList","failed",e)
                handler.post { progressBar.visibility = View.GONE; setContentView(TextView(this).apply { text = "查询失败: ${e.message}"; textSize = 14f }) }
            }
        }.start()
    }
}

// ── Sectioned Adapter with better visuals ──

val AVATAR_COLORS = intArrayOf(
    0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(), 0xFF3F51B5.toInt(),
    0xFF2196F3.toInt(), 0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFFFF5722.toInt(),
    0xFF795548.toInt(), 0xFF607D8B.toInt()
)

private fun avatarColor(name: String): Int {
    val i = kotlin.math.abs(name.hashCode()) % AVATAR_COLORS.size
    return AVATAR_COLORS[i]
}

private fun avatarChar(name: String): String {
    val c = name.firstOrNull { it.isLetterOrDigit() } ?: '#'
    return c.toString().uppercase()
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    val cal = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = ts }
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return when {
        cal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            sdf.format(Date(ts))
        cal.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1 && cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            "昨天 ${sdf.format(Date(ts))}"
        cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
        else ->
            SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

class SectionAdapter(
    private val items: List<SectionItem>,
    private val onClick: (ChatConversation) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object { const val TYPE_HEADER = 0; const val TYPE_ITEM = 1 }

    override fun getItemViewType(pos: Int) = if (items[pos].isHeader) TYPE_HEADER else TYPE_ITEM
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (vt == TYPE_HEADER) {
            val tv = TextView(ctx).apply {
                textSize = 13f; setTextColor(0xFF9E9E9E.toInt())
                setPadding(72, 20, 24, 8); setBackgroundColor(0xFFF5F5F5.toInt())
            }
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            // Better card with avatar + name + time + badge
            val card = CardView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                radius = 0f; cardElevation = 0.5f
                setContentPadding(0, 0, 0, 0); setBackgroundColor(Color.WHITE)
            }
            object : RecyclerView.ViewHolder(card) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val item = items[pos]
        if (item.isHeader) {
            (holder.itemView as TextView).text = item.title
            return
        }
        val conv = item.conv ?: return
        val ctx = holder.itemView.context
        val card = holder.itemView as CardView
        card.removeAllViews()

        val hRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 12, 16, 12)
        }

        // Avatar circle
        val avatarFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(56, 56)
        }
        avatarFrame.addView(TextView(ctx).apply {
            text = avatarChar(conv.nickname)
            textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(avatarColor(conv.nickname))
        }, FrameLayout.LayoutParams(56, 56).also {
            // We need a circular shape - use a drawable or just set radius on the view
            // For simplicity, use a solid square with the char
        })
        hRow.addView(avatarFrame)

        // Text area
        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(16, 0, 8, 0) }
        }

        // Top row: name + time
        val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        nameRow.addView(TextView(ctx).apply {
            text = conv.nickname; textSize = 16f; setTextColor(0xDE000000.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        nameRow.addView(TextView(ctx).apply {
            text = formatTime(conv.conversationTime); textSize = 11f; setTextColor(0x8A000000.toInt())
            gravity = Gravity.END
        })
        textArea.addView(nameRow)

        // Bottom row: type badge + unread
        val infoRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 0) }
        infoRow.addView(TextView(ctx).apply {
            text = when (conv.typeTag) { "group" -> "群聊"; "official" -> "公众号"; else -> "联系人" }
            textSize = 12f; setTextColor(0xFF9E9E9E.toInt())
        })
        if (conv.unReadCount > 0) {
            infoRow.addView(TextView(ctx).apply {
                text = "  ${conv.unReadCount}"; textSize = 11f; setTextColor(Color.WHITE)
                setBackgroundColor(0xFFFF4444.toInt())
                gravity = Gravity.CENTER
                setPadding(8, 2, 8, 2)
                // Make it a pill shape by setting min-width
                minWidth = 20
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 0, 0, 0)
            })
        }
        textArea.addView(infoRow)
        hRow.addView(textArea)
        card.addView(hRow)
        card.setOnClickListener { onClick(conv) }
    }
}