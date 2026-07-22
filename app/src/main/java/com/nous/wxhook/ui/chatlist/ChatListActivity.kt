package com.nous.wxhook.ui.chatlist

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.backup.NativeArchive
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
    private lateinit var spinner: Spinner

    // 当前使用的数据库路径
    private var currentDbPath = "/sdcard/Download/EnMicroMsg.db"

    data class BackupOption(val label: String, val path: String)  // path="" 表示实时库

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "消息列表"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 备份选择器
        val spinnerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(M3.dp(this@ChatListActivity, 16), M3.dp(this@ChatListActivity, 8), M3.dp(this@ChatListActivity, 16), M3.dp(this@ChatListActivity, 4))
        }
        spinnerRow.addView(MaterialTextView(this, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
            text = "数据源: "
            setPadding(0, 0, M3.dp(this@ChatListActivity, 8), 0)
        })
        spinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        spinnerRow.addView(spinner)
        root.addView(spinnerRow)

        // RecyclerView + Progress
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        recyclerView = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@ChatListActivity)
        }
        contentFrame.addView(recyclerView)

        progressBar = LinearProgressIndicator(this, null, com.google.android.material.R.attr.linearProgressIndicatorStyle).apply {
            id = View.generateViewId()
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP }
        }
        contentFrame.addView(progressBar)

        emptyView = MaterialTextView(this, null, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
        }
        contentFrame.addView(emptyView)
        root.addView(contentFrame)

        setContentView(root)

        loadBackupOptions()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** 扫描可用备份，填充下拉框 */
    private fun loadBackupOptions() {
        val options = mutableListOf(BackupOption("📱 实时数据库", ""))
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        try {
            val recordsRaw = RootGateways.runQuiet("cat /sdcard/Download/wxhook_backup/backupdata/backup_records.json 2>/dev/null")
            if (recordsRaw.isNotBlank()) {
                val arr = org.json.JSONArray(recordsRaw)
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val tag = r.optString("tag", "")
                    val type = r.optString("type", "")
                    val time = r.optLong("time", 0L)
                    val files = r.optJSONArray("files")
                    val archivePath = if (files != null && files.length() > 0) {
                        "/sdcard/Download/wxhook_backup/backupdata/${files.getString(0)}"
                    } else ""
                    val typeIcon = if (type == "full") "📦" else "📎"
                    val timeStr = if (time > 0) fmt.format(Date(time)) else "?"
                    val label = "$typeIcon ${tag.substringAfter("_")} ($timeStr)"
                    options.add(BackupOption(label, archivePath))
                }
            }
        } catch (_: Exception) {}

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val opt = options[pos]
                if (opt.path.isEmpty()) {
                    // 实时数据库：先自动从微信进程复制最新版本
                    progressBar.visibility = View.VISIBLE
                    refreshLiveDb()
                } else {
                    loadFromBackup(opt)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** 从备份包中提取数据库 */
    private fun loadFromBackup(opt: BackupOption) {
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        Thread {
            try {
                val archivePath = opt.path
                if (!File(archivePath).exists()) {
                    post { Toast.makeText(this, "备份文件不存在", Toast.LENGTH_SHORT).show(); progressBar.visibility = View.GONE }
                    return@Thread
                }

                val dumpName = "EnMicroMsg_baseline.sql"
                val tempDb = File(cacheDir, "backup_chat_${System.currentTimeMillis()}.db")
                val sqlScript = File(cacheDir, "backup_sql_${System.currentTimeMillis()}.sql")
                val schemaFile = File(cacheDir, "backup_schema_${System.currentTimeMillis()}.sql")

                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"

                // 1. 优先找同名的 .db 文件（新格式：完整未加密数据库）
                val companionDb = File(archivePath.replace(Regex("\\.tar\\.zst$"), ".db"))
                if (companionDb.exists() && companionDb.length() > 4096) {
                    post { Toast.makeText(this, "使用备份数据库...", Toast.LENGTH_SHORT).show() }
                    currentDbPath = companionDb.absolutePath
                    post { loadConversations() }
                    return@Thread
                }

                // 2. 旧格式：从 tar.zst 中提取 SQL dump + 重建
                post { Toast.makeText(this, "正在提取备份（旧格式）...", Toast.LENGTH_SHORT).show() }
                val sqlContent = NativeArchive.readFileFromTar(archivePath, dumpName)
                if (sqlContent.isNullOrBlank()) {
                    post { runOnUiThread { emptyView.text = "该备份不含数据库，请选择完整备份"; emptyView.visibility = View.VISIBLE; progressBar.visibility = View.GONE } }
                    return@Thread
                }

                sqlScript.writeText(sqlContent)

                // 从实时库提取建表语句
                val liveDb = "/sdcard/Download/EnMicroMsg.db"
                val schemaCmd = if (File(liveDb).exists()) {
                    "PRAGMA key='e9cd2ae';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;" +
                    ".output '${schemaFile.absolutePath}'\n.schema"
                } else ""

                if (schemaCmd.isNotEmpty()) {
                    val tmpSql = File(cacheDir, "get_schema_${System.currentTimeMillis()}.sql")
                    tmpSql.writeText(schemaCmd)
                    RootGateways.run("$sc '$liveDb' < '${tmpSql.absolutePath}' 2>/dev/null")
                    tmpSql.delete()
                }

                val buildSql = File(cacheDir, "build_db_${System.currentTimeMillis()}.sql")
                buildSql.writeText(
                    (if (schemaFile.exists()) ".read '${schemaFile.absolutePath}'\n" else "") +
                    ".read '${sqlScript.absolutePath}'\n" +
                    "SELECT count(*) FROM sqlite_master WHERE type='table';"
                )
                RootGateways.run("$sc '${tempDb.absolutePath}' < '${buildSql.absolutePath}' 2>/dev/null")
                buildSql.delete(); sqlScript.delete(); schemaFile.delete()

                if (tempDb.exists() && tempDb.length() > 4096) {
                    currentDbPath = tempDb.absolutePath
                    post { loadConversations() }
                } else {
                    tempDb.delete()
                    post { runOnUiThread { emptyView.text = "数据库重建失败"; emptyView.visibility = View.VISIBLE; progressBar.visibility = View.GONE } }
                }
            } catch (e: Exception) {
                post { runOnUiThread { emptyView.text = "加载失败: ${e.message}"; emptyView.visibility = View.VISIBLE; progressBar.visibility = View.GONE } }
            }
        }.start()
    }

    /** 自动从微信进程复制最新数据库到 /sdcard/Download/ */
    private fun refreshLiveDb() {
        Thread {
            try {
                val pid = RootGateways.runQuiet("pidof com.tencent.mm").trim()
                if (pid.isBlank()) {
                    // 微信没运行，用已有副本
                    post {
                        currentDbPath = "/sdcard/Download/EnMicroMsg.db"
                        loadConversations()
                    }
                    return@Thread
                }

                val src = "/proc/${pid}/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                val dst = "/sdcard/Download/EnMicroMsg.db"

                // 先用 stat 检查源文件大小
                val srcSize = RootGateways.runQuiet("stat -c%s '$src' 2>/dev/null").trim()
                if (srcSize.toLongOrNull() ?: 0L < 1024) {
                    post { currentDbPath = dst; loadConversations() }
                    return@Thread
                }

                post { Toast.makeText(this, "正在复制最新数据库...", Toast.LENGTH_SHORT).show() }
                RootGateways.run("dd if='$src' of='$dst' bs=1M 2>/dev/null")
                RootGateways.run("chmod 644 '$dst'")

                post {
                    currentDbPath = dst
                    android.util.Log.i("wxhook:ChatList", "DB copied: ${srcSize}B")
                    loadConversations()
                }
            } catch (e: Exception) {
                android.util.Log.e("wxhook:ChatList", "refresh DB failed", e)
                post {
                    currentDbPath = "/sdcard/Download/EnMicroMsg.db"
                    loadConversations()
                }
            }
        }.start()
    }

    private fun post(action: () -> Unit) = runOnUiThread { action() }

    /** 从当前 currentDbPath 加载会话列表 */
    private fun loadConversations() {
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        Thread {
            try {
                val dbPath = currentDbPath
                if (!File(dbPath).exists()) {
                    post { progressBar.visibility = View.GONE; emptyView.text = "数据库不存在"; emptyView.visibility = View.VISIBLE }
                    return@Thread
                }

                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cl_${tag}.sql")
                val isEncrypted = dbPath.startsWith("/sdcard/") && dbPath.contains("EnMicroMsg")
                val prg = if (isEncrypted) {
                    "PRAGMA key='e9cd2ae';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;"
                } else ""

                sqlFile.writeText(
                    "${prg}SELECT c.username,IFNULL(NULLIF(r.conRemark,''),IFNULL(r.nickname,c.username))," +
                    "c.unReadCount,c.conversationTime," +
                    "CASE WHEN c.username LIKE '%@chatroom' THEN 'group' " +
                    "WHEN c.username LIKE '%@app' OR c.username LIKE 'gh_%' THEN 'official' ELSE 'contact' END " +
                    "FROM rconversation c LEFT JOIN rcontact r ON c.username=r.username " +
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
                        convs.add(ChatConversation(p[0], p[1], p[2].toIntOrNull() ?: 0, p.getOrNull(3)?.toLongOrNull() ?: 0L, p[4]))
                    }
                }

                val groups = mutableListOf<SectionItem>()
                for (type in listOf("contact", "group", "official")) {
                    val items = convs.filter { it.typeTag == type }
                    if (items.isNotEmpty()) {
                        val label = when (type) { "group" -> "👥 群聊"; "official" -> "📢 公众号"; else -> "👤 联系人" }
                        groups.add(SectionItem(isHeader = true, title = "$label (${items.size})"))
                        items.forEach { groups.add(SectionItem(isHeader = false, conv = it)) }
                    }
                }

                post {
                    progressBar.visibility = View.GONE
                    if (convs.isEmpty()) {
                        emptyView.text = "没有会话数据\n请确认微信已运行且数据库已解密"
                        emptyView.visibility = View.VISIBLE
                    } else {
                        recyclerView.adapter = SectionAdapter(groups) { conv ->
                            startActivity(Intent(this@ChatListActivity, com.nous.wxhook.ui.chatdetail.ChatDetailActivity::class.java).apply {
                                putExtra("talker", conv.username); putExtra("nickname", conv.nickname)
                                putExtra("dbPath", currentDbPath)  // 传给详情页
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                post { progressBar.visibility = View.GONE; emptyView.text = "查询失败: ${e.message}"; emptyView.visibility = View.VISIBLE }
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
        cal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) -> sdf.format(Date(ts))
        cal.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1 && cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) -> "昨天"
        cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

// ── Sectioned Adapter ──
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
            val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
                setPadding(M3.dp(ctx, 72), M3.dp(ctx, 16), M3.dp(ctx, 20), M3.dp(ctx, 8))
                setTextColor(M3.onSurfaceVariant(ctx))
            }
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            val card = com.google.android.material.card.MaterialCardView(ctx, null, com.google.android.material.R.attr.materialCardViewElevatedStyle).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                radius = 0f; cardElevation = 0.5f; setContentPadding(0, 0, 0, 0)
            }
            object : RecyclerView.ViewHolder(card) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val item = items[pos]
        if (item.isHeader) { (holder.itemView as MaterialTextView).text = item.title; return }

        val conv = item.conv ?: return
        val ctx = holder.itemView.context
        val card = holder.itemView as com.google.android.material.card.MaterialCardView
        card.removeAllViews(); card.setOnClickListener { onClick(conv) }

        val hRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(M3.dp(ctx, 16), M3.dp(ctx, 8), M3.dp(ctx, 16), M3.dp(ctx, 8))
        }
        hRow.addView(M3.avatar(ctx, conv.nickname, 52, 22))

        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(M3.dp(ctx, 12), 0, M3.dp(ctx, 8), 0) }
        }
        val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        nameRow.addView(MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
            text = conv.nickname; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        nameRow.addView(MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
            text = formatTime(conv.conversationTime); setTextColor(M3.onSurfaceVariant(ctx))
        })
        textArea.addView(nameRow)

        val infoRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val typeLabel = when (conv.typeTag) { "group" -> "群聊"; "official" -> "公众号"; else -> "联系人" }
        infoRow.addView(MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
            text = typeLabel; setTextColor(M3.onSurfaceVariant(ctx))
        })
        if (conv.unReadCount > 0) {
            infoRow.addView(M3.badge(ctx, conv.unReadCount).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(M3.dp(ctx, 8), 0, 0, 0); layoutParams = lp
            })
        }
        textArea.addView(infoRow)
        hRow.addView(textArea)
        card.addView(hRow)
    }
}
