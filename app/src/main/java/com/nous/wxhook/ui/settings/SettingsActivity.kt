package com.nous.wxhook.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.ui.M3
import org.json.JSONObject
import java.io.File

// ── Data model ──
sealed class SettingsItem {
    data class Header(val title: String) : SettingsItem()
    data class Toggle(val label: String, val key: String, val def: Boolean = false) : SettingsItem()
    data class Input(val label: String, val key: String, val def: String = "", val hint: String = "") : SettingsItem()
    data class Action(val text: String, val action: String = "") : SettingsItem()
}

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BackupHookLocal.init(this)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            setPadding(M3.dp(this@SettingsActivity, 0), M3.dp(this@SettingsActivity, 8), 0, 0)
        }
        setContentView(recyclerView)
        buildItems(recyclerView)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildItems(recyclerView: RecyclerView) {
        val items = mutableListOf<SettingsItem>()
        items.add(SettingsItem.Header("🔑 WebDAV 配置"))
        items.add(SettingsItem.Input("WebDAV 地址", "webdav_url", "", "https://example.com/dav/"))
        items.add(SettingsItem.Input("用户名", "webdav_user", ""))
        items.add(SettingsItem.Input("密码", "webdav_pass", ""))
        items.add(SettingsItem.Action("💾 保存 WebDAV 配置", "save_webdav"))
        items.add(SettingsItem.Action("🔍 测试连接", "test_webdav"))

        items.add(SettingsItem.Header("📂 备份设置"))
        items.add(SettingsItem.Input("备份路径", "backup_path", "/sdcard/Download/wxhook_backup"))
        items.add(SettingsItem.Toggle("使用 zstd 压缩", "zstd", false))

        items.add(SettingsItem.Header("🛠 工具"))
        items.add(SettingsItem.Action("🔄 重建备份状态", "rebuild_state"))

        recyclerView.adapter = SettingsAdapter(items, this) { action, _ -> handleAction(action) }
    }

    private fun handleAction(action: String) {
        val cfg = runCatching {
            JSONObject(File(filesDir, "settings_config.json").readText())
        }.getOrDefault(JSONObject())

        when (action) {
            "save_webdav" -> {
                val url = cfg.optString("webdav_url", "")
                val user = cfg.optString("webdav_user", "")
                val pass = cfg.optString("webdav_pass", "")
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this, "请输入 WebDAV 地址和用户名", Toast.LENGTH_SHORT).show()
                    return
                }
                viewModel.saveWebdavConfig(url, user, pass)
                Toast.makeText(this, "✅ WebDAV 配置已保存", Toast.LENGTH_SHORT).show()
            }
            "test_webdav" -> {
                val url = cfg.optString("webdav_url", "")
                val user = cfg.optString("webdav_user", "")
                val pass = cfg.optString("webdav_pass", "")
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this, "请输入 WebDAV 地址和用户名", Toast.LENGTH_SHORT).show()
                    return
                }
                viewModel.testWebDavConnection(url, user, pass)
            }
            "rebuild_state" -> {
                viewModel.rebuildState()
            }
        }
    }
}

// ── Adapter ──
class SettingsAdapter(
    private val items: List<SettingsItem>,
    private val activity: SettingsActivity,
    private val onAction: (String, Any?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TOGGLE = 1
        const val TYPE_INPUT = 2
        const val TYPE_ACTION = 3
    }

    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is SettingsItem.Header -> TYPE_HEADER
        is SettingsItem.Toggle -> TYPE_TOGGLE
        is SettingsItem.Input -> TYPE_INPUT
        is SettingsItem.Action -> TYPE_ACTION
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (vt) {
            TYPE_HEADER -> {
                val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceTitleMedium).apply {
                    setPadding(M3.dp(ctx, 20), M3.dp(ctx, 20), M3.dp(ctx, 20), M3.dp(ctx, 8))
                    setTextColor(M3.colorPrimary(ctx))
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            TYPE_TOGGLE -> {
                val card = M3.outlinedCard(ctx).apply {
                    val lp = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(M3.dp(ctx, 16), M3.dp(ctx, 4), M3.dp(ctx, 16), M3.dp(ctx, 4))
                    layoutParams = lp
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val label = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(label)
                val sw = SwitchMaterial(ctx)
                row.addView(sw)
                card.addView(row)
                object : RecyclerView.ViewHolder(card) {
                    val labelView = label
                    val switchView = sw
                }
            }
            TYPE_INPUT -> {
                val card = M3.outlinedCard(ctx).apply {
                    val lp = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(M3.dp(ctx, 16), M3.dp(ctx, 4), M3.dp(ctx, 16), M3.dp(ctx, 4))
                    layoutParams = lp
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val label = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceLabelMedium)
                col.addView(label)
                val et = EditText(ctx).apply {
                    textSize = 16f
                    setPadding(0, M3.dp(ctx, 4), 0, M3.dp(ctx, 4))
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                col.addView(et)
                card.addView(col)
                object : RecyclerView.ViewHolder(card) {
                    val labelView = label
                    val editText = et
                }
            }
            TYPE_ACTION -> {
                val card = M3.outlinedCard(ctx).apply {
                    val lp = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(M3.dp(ctx, 16), M3.dp(ctx, 4), M3.dp(ctx, 16), M3.dp(ctx, 4))
                    layoutParams = lp
                }
                val tv = MaterialTextView(ctx, null, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                    setTextColor(M3.colorPrimary(ctx))
                    gravity = Gravity.CENTER
                    setPadding(0, M3.dp(ctx, 4), 0, M3.dp(ctx, 4))
                }
                card.addView(tv)
                object : RecyclerView.ViewHolder(card) {
                    val textView = tv
                }
            }
            else -> error("unknown type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val ctx = holder.itemView.context
        when (val item = items[pos]) {
            is SettingsItem.Header -> {
                (holder.itemView as MaterialTextView).text = item.title
            }
            is SettingsItem.Toggle -> {
                val cardView = holder.itemView as MaterialCardView
                val row = cardView.getChildAt(0) as? LinearLayout
                val label = row?.getChildAt(0) as? MaterialTextView
                val sw = row?.getChildAt(1) as? SwitchMaterial
                label?.text = item.label
                val cfg = runCatching {
                    JSONObject(File(activity.filesDir, "settings_config.json").readText())
                }.getOrDefault(JSONObject())
                sw?.isChecked = cfg.optBoolean(item.key, item.def)
                sw?.setOnCheckedChangeListener { _, checked ->
                    val o = runCatching {
                        JSONObject(File(activity.filesDir, "settings_config.json").readText())
                    }.getOrDefault(JSONObject())
                    o.put(item.key, checked)
                    File(activity.filesDir, "settings_config.json").writeText(o.toString())
                }
            }
            is SettingsItem.Input -> {
                val cardView = holder.itemView as MaterialCardView
                val col = cardView.getChildAt(0) as? LinearLayout
                val label = col?.getChildAt(0) as? MaterialTextView
                val et = col?.getChildAt(1) as? EditText
                label?.text = item.label
                if (et != null) {
                    val cfg = runCatching {
                        JSONObject(File(activity.filesDir, "settings_config.json").readText())
                    }.getOrDefault(JSONObject())
                    et.setText(cfg.optString(item.key, item.def))
                    et.hint = item.hint
                    et.setOnFocusChangeListener { _, focused ->
                        if (!focused) {
                            val o = runCatching {
                                JSONObject(File(activity.filesDir, "settings_config.json").readText())
                            }.getOrDefault(JSONObject())
                            o.put(item.key, et.text.toString())
                            File(activity.filesDir, "settings_config.json").writeText(o.toString())
                        }
                    }
                }
            }
            is SettingsItem.Action -> {
                holder.itemView.let { card ->
                    val tv = (card as MaterialCardView).getChildAt(0) as? MaterialTextView
                    tv?.text = item.text
                }
                holder.itemView.setOnClickListener { onAction(item.action, null) }
            }
        }
    }
}
