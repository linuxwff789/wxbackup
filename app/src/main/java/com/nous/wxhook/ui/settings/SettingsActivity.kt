package com.nous.wxhook.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File

// ── Data ──
sealed class SettingsItem {
    data class Header(val title: String) : SettingsItem()
    data class Toggle(val label: String, val key: String, val def: Boolean = false) : SettingsItem()
    data class Input(val label: String, val key: String, val def: String = "", val hint: String = "") : SettingsItem()
    data class Button(val text: String, val action: String = "") : SettingsItem()
    data class Info(val text: String) : SettingsItem()
}

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            id = View.generateViewId()
        }
        setContentView(recyclerView)
        com.nous.wxhook.rootbridge.backup.BackupHookLocal.init(this)
        buildItems()

        // Observe ViewModel state for action bar updates
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    supportActionBar?.title = state.actionTitle
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun buildItems() {
        val items = mutableListOf<SettingsItem>()

        // ── Cloud sync section ──
        items.add(SettingsItem.Header("☁️ 云同步"))
        items.add(SettingsItem.Toggle("启用云同步", "sync_enabled", false))
        items.add(SettingsItem.Button("立即同步到云盘", "sync_now"))

        // ── WebDAV config section ──
        items.add(SettingsItem.Header("🔑 WebDAV 配置"))
        items.add(SettingsItem.Input("WebDAV 地址", "webdav_url", "", "https://example.com/dav/"))
        items.add(SettingsItem.Input("用户名", "webdav_user", ""))
        items.add(SettingsItem.Input("密码", "webdav_pass", ""))
        items.add(SettingsItem.Button("保存 WebDAV 配置", "save_webdav"))
        items.add(SettingsItem.Button("🔍 测试 WebDAV 连接", "test_webdav"))

        // ── Backup section ──
        items.add(SettingsItem.Header("📂 备份设置"))
        items.add(SettingsItem.Input("备份路径", "backup_path", "/sdcard/Download/wxhook_backup"))
        items.add(SettingsItem.Toggle("使用 zstd 压缩（关闭则使用 gzip）", "zstd", false))
        items.add(SettingsItem.Header("🛠 工具"))
        items.add(SettingsItem.Button("重建备份状态", "rebuild_state"))

        recyclerView.adapter = SettingsAdapter(items, recyclerView) { action, data ->
            handleAction(action, data)
        }
    }

    private fun handleAction(action: String, data: Any?) {
        when {
            action == "save_webdav" -> {
                val cfg = runCatching { JSONObject(File(filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                val url = cfg.optString("webdav_url", "")
                val user = cfg.optString("webdav_user", "")
                val pass = cfg.optString("webdav_pass", "")
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this, "请输入WebDAV地址和用户名", Toast.LENGTH_SHORT).show()
                    return
                }
                viewModel.saveWebdavConfig(url, user, pass)
                Toast.makeText(this, "✅ WebDAV配置已保存", Toast.LENGTH_SHORT).show()
            }
            action == "test_webdav" -> {
                val cfg = runCatching { JSONObject(File(filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                val url = cfg.optString("webdav_url", "")
                val user = cfg.optString("webdav_user", "")
                val pass = cfg.optString("webdav_pass", "")
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this, "请输入WebDAV地址和用户名", Toast.LENGTH_SHORT).show()
                    return
                }
                viewModel.testWebDavConnection(url, user, pass)
            }
            action == "sync_now" -> viewModel.doSync()
            action == "rebuild_state" -> {
                viewModel.rebuildState()
                Toast.makeText(this, "⏳ 重建中...", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ── Adapter ──
class SettingsAdapter(
    private val items: List<SettingsItem>,
    private val recyclerView: RecyclerView,
    private val onAction: (String, Any?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        const val TYPE_HEADER = 0; const val TYPE_TOGGLE = 1
        const val TYPE_INPUT = 2; const val TYPE_BUTTON = 3; const val TYPE_INFO = 4
    }

    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is SettingsItem.Header -> TYPE_HEADER; is SettingsItem.Toggle -> TYPE_TOGGLE
        is SettingsItem.Input -> TYPE_INPUT; is SettingsItem.Button -> TYPE_BUTTON
        is SettingsItem.Info -> TYPE_INFO
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (vt) {
            TYPE_HEADER -> {
                val tv = TextView(ctx).apply {
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF6200EE.toInt())
                    setPadding(20, 24, 20, 8); setBackgroundColor(0xFFF5F5F5.toInt())
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            TYPE_TOGGLE -> {
                val card = CardView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    radius = 8f; cardElevation = 1f; setContentPadding(16, 8, 16, 8)
                    setCardBackgroundColor(Color.WHITE)
                    val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(16, 4, 16, 4)
                    }
                    layoutParams = lp
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    minimumHeight = 48
                }
                val label = TextView(ctx).apply {
                    id = View.generateViewId(); textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(label)
                val sw = Switch(ctx).apply { id = View.generateViewId() }
                row.addView(sw)
                card.addView(row)
                object : RecyclerView.ViewHolder(card) {}
            }
            TYPE_INPUT -> {
                val card = CardView(ctx).apply {
                    radius = 8f; cardElevation = 1f; setContentPadding(16, 8, 16, 8)
                    setCardBackgroundColor(Color.WHITE)
                    val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(16, 4, 16, 4)
                    }
                    layoutParams = lp
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val label = TextView(ctx).apply {
                    id = View.generateViewId(); textSize = 13f; setTextColor(0xFF757575.toInt())
                }
                col.addView(label)
                val et = EditText(ctx).apply {
                    id = View.generateViewId(); textSize = 14f
                    setPadding(0, 4, 0, 4); setBackgroundColor(Color.TRANSPARENT)
                }
                col.addView(et)
                card.addView(col)
                object : RecyclerView.ViewHolder(card) {}
            }
            TYPE_BUTTON -> {
                val btn = Button(ctx).apply {
                    textSize = 14f; setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF6200EE.toInt())
                    val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(16, 8, 16, 8)
                    }
                    layoutParams = lp
                }
                object : RecyclerView.ViewHolder(btn) {}
            }
            TYPE_INFO -> {
                val tv = TextView(ctx).apply {
                    textSize = 12f; setTextColor(0xFF9E9E9E.toInt())
                    setPadding(20, 4, 20, 4)
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            else -> error("unknown type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val ctx = holder.itemView.context
        when (val item = items[pos]) {
            is SettingsItem.Header -> (holder.itemView as TextView).text = item.title
            is SettingsItem.Toggle -> {
                val card = holder.itemView as CardView
                val label = card.findViewWithTag<TextView>("label") ?: (card.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView
                label?.text = item.label
                // Load actual value from config
                val cfg = runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                val sw = card.findViewWithTag<Switch>("switch") ?: (card.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? Switch
                sw?.isChecked = cfg.optBoolean(item.key, item.def)
                sw?.setOnCheckedChangeListener { _, checked ->
                    val o = runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                    o.put(item.key, checked)
                    File(ctx.filesDir, "settings_config.json").writeText(o.toString())
                }
            }
            is SettingsItem.Input -> {
                val card = holder.itemView as CardView
                val col = card.getChildAt(0) as? LinearLayout
                val label = col?.getChildAt(0) as? TextView
                label?.text = item.label
                val et = col?.getChildAt(1) as? EditText
                if (et != null) {
                    et.setText(runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject()).optString(item.key, item.def))
                    et.hint = item.hint
                    // Save on focus change
                    et.setOnFocusChangeListener { _, focused ->
                        if (!focused) {
                            val o = runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                            o.put(item.key, et.text.toString())
                            File(ctx.filesDir, "settings_config.json").writeText(o.toString())
                        }
                    }
                }
            }
            is SettingsItem.Button -> {
                val btn = holder.itemView as Button
                btn.text = item.text
                btn.setOnClickListener {
                    onAction(item.action, null)
                }
            }
            is SettingsItem.Info -> (holder.itemView as TextView).text = item.text
        }
    }
}
