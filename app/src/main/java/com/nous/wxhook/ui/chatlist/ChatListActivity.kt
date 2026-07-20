package com.nous.wxhook.ui.chatlist

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.ui.M3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ChatConversation(
    val username: String,
    val nickname: String,
    val unReadCount: Int,
    val conversationTime: Long,
    val typeTag: String
)

data class SectionItem(
    val isHeader: Boolean,
    val title: String = "",
    val conv: ChatConversation? = null
)

class ChatListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var emptyView: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "消息列表"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = FrameLayout(this)

        recyclerView = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@ChatListActivity)
            setPadding(M3.dp(this@ChatListActivity, 0), M3.dp(this@ChatListActivity, 8), 0, 0)
        }
        root.addView(recyclerView)

        progressBar = LinearProgressIndicator(this, null, com.google.android.material.R.attr.linearProgressIndicatorStyle).apply {
            id = View.generateViewId()
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        root.addView(progressBar)

        emptyView = M3.body(this, "加载中...").apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(emptyView)

        setContentView(root)
        loadConversations()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadConversations() {
        progressBar.visibility = View.VISIBLE

        Thread {
            try {
                val key = "e9cd2ae"
                val dbPath = "/sdcard/Download/EnMicroMsg.db"

                if (!File(dbPath).exists()) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        emptyView.text = "❌ 数据库不存在\n请先复制 EnMicroMsg.db 到 /sdcard/Download/"
                        emptyView.visibility = View.VISIBLE
                    }
                    return@Thread
                }

                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cl_${tag}.sql")
                sqlFile.writeText(
                    "PRAGMA key='$key';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT c.username,IFNULL(NULLIF(r.conRemark,''),IFNULL(r.nickname,c.username))," +
                    "c.unReadCount,c.conversationTime," +
                    "CASE WHEN c.username LIKE '%@chatroom' THEN 'group' " +
                    "WHEN c.username LIKE '%@app' OR c.username LIKE 'gh_%' THEN 'official' " +
                    "ELSE 'contact' END " +
                    "FROM rconversation c " +
                    "LEFT JOIN rcontact r ON c.username=r.username " +
                    "ORDER BY c.conversationTime DESC LIMIT 200;"
                )

                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:" +
                         "/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val out = RootGateways.runQuiet("$sc '$dbPath' < '${sqlFile.absolutePath}'")
                val lines = out.lines()
                sqlFile.delete()

                val convs = mutableListOf<ChatConversation>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 5 && !p[0].startsWith("ok")) {
                        convs.add(
                            ChatConversation(
                                p[0], p[1],
                                p[2].toIntOrNull() ?: 0,
                                p.getOrNull(3)?.toLongOrNull() ?: 0L,
                                p[4]
                            )
                        )
                    }
                }

                // Build sectioned list
                val groups = mutableListOf<SectionItem>()
                for (type in listOf("contact", "group", "official")) {
                    val items = convs.filter { it.typeTag == type }
                    if (items.isNotEmpty()) {
                        val label = when (type) {
                            "group" -> "👥 群聊"; "official" -> "📢 公众号"
                            else -> "👤 联系人"
                        }
                        groups.add(SectionItem(isHeader = true, title = "$label (${items.size})"))
                        items.forEach { groups.add(SectionItem(isHeader = false, conv = it)) }
                    }
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (convs.isEmpty()) {
                        emptyView.text = "没有会话数据\n请确认微信已运行且数据库已解密"
                        emptyView.visibility = View.VISIBLE
                    } else {
                        recyclerView.adapter = SectionAdapter(groups) { conv ->
                            startActivity(
                                Intent(this@ChatListActivity, com.nous.wxhook.ui.chatdetail.ChatDetailActivity::class.java).apply {
                                    putExtra("talker", conv.username)
                                    putExtra("nickname", conv.nickname)
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    emptyView.text = "❌ 查询失败: ${e.message}"
                    emptyView.visibility = View.VISIBLE
                }
            }
        }.start()
    }
}

// ── Time formatting ──
private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    val cal = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = ts }
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return when {
        cal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            sdf.format(Date(ts))
        cal.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1 && cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            "昨天"
        cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
        else ->
            SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

// ── Sectioned Adapter ──
class SectionAdapter(
    private val items: List<SectionItem>,
    private val onClick: (ChatConversation) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    override fun getItemViewType(pos: Int) = if (items[pos].isHeader) TYPE_HEADER else TYPE_ITEM
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (vt == TYPE_HEADER) {
            val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
                setPadding(M3.dp(ctx, 72), M3.dp(ctx, 16), M3.dp(ctx, 20), M3.dp(ctx, 8))
                setTextColor(M3.onSurfaceVariant(ctx))
            }
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            val card = com.google.android.material.card.MaterialCardView(
                ctx, null, com.google.android.material.R.attr.materialCardViewElevatedStyle
            ).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                radius = 0f
                cardElevation = 0.5f
                setContentPadding(0, 0, 0, 0)
            }
            object : RecyclerView.ViewHolder(card) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val item = items[pos]
        if (item.isHeader) {
            (holder.itemView as MaterialTextView).text = item.title
            return
        }

        val conv = item.conv ?: return
        val ctx = holder.itemView.context
        val card = holder.itemView as com.google.android.material.card.MaterialCardView
        card.removeAllViews()
        card.setOnClickListener { onClick(conv) }

        val hRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(M3.dp(ctx, 16), M3.dp(ctx, 8), M3.dp(ctx, 16), M3.dp(ctx, 8))
        }

        // Avatar
        hRow.addView(M3.avatar(ctx, conv.nickname, 52, 22))

        // Text area
        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(M3.dp(ctx, 12), 0, M3.dp(ctx, 8), 0) }
        }

        // Name row
        val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        nameRow.addView(
            MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                text = conv.nickname
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        nameRow.addView(
            MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
                text = formatTime(conv.conversationTime)
                setTextColor(M3.onSurfaceVariant(ctx))
            }
        )
        textArea.addView(nameRow)

        // Bottom info row
        val infoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val typeLabel = when (conv.typeTag) {
            "group" -> "群聊"; "official" -> "公众号"; else -> "联系人"
        }
        infoRow.addView(
            MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
                text = typeLabel
                setTextColor(M3.onSurfaceVariant(ctx))
            }
        )

        if (conv.unReadCount > 0) {
            infoRow.addView(M3.badge(ctx, conv.unReadCount).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(M3.dp(ctx, 8), 0, 0, 0)
                layoutParams = lp
            })
        }

        textArea.addView(infoRow)
        hRow.addView(textArea)
        card.addView(hRow)
    }
}
