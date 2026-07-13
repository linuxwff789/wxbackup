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
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import kotlinx.coroutines.launch
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
        items.add(SettingsItem.Input("远程路径", "remote_path", "gdrive:wxhook-backup", "例: gdrive:wxhook-backup"))
        items.add(SettingsItem.Button("立即同步到云盘", "sync_now"))

        // ── Rclone config section ──
        items.add(SettingsItem.Header("🔑 rclone 配置"))
        items.add(SettingsItem.Info("选择云盘一键生成配置，免手动写 rclone.conf"))

        // Provider quick config buttons
        val providers = listOf(
            "📦 Google Drive" to "gdrive drive scope=drive",
            "📦 WebDAV" to "webdav webdav url=http://demo.com user=admin",
            "📦 S3 对象存储" to "s3 s3 provider=Other access_key_id= key_secret= region=cn-north-1 endpoint=http://127.0.0.1:9000 acl=private"
        )
        for ((name, args) in providers) {
            items.add(SettingsItem.Button("$name", "rclone_create::$args"))
        }

        val rcloneConfText = viewModel.uiState.value.rcloneConfText
        items.add(SettingsItem.Input("自定义 rclone.conf", "rclone_conf", rcloneConfText, "也可直接粘贴配置"))
        items.add(SettingsItem.Button("保存配置", "save_rclone"))
        items.add(SettingsItem.Button("🔍 测试连接", "test_rclone"))

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
            action == "save_rclone" -> {
                val text = data as? String ?: return
                viewModel.saveRcloneConf(text)
            }
            action == "sync_now" -> viewModel.doSync()
            action == "rebuild_state" -> {
                viewModel.rebuildState()
                Toast.makeText(this, "⏳ 重建中...", Toast.LENGTH_SHORT).show()
            }
            action.startsWith("rclone_create::") -> {
                val argsStr = action.removePrefix("rclone_create::")
                val parts = argsStr.split(" ")
                if (parts.size >= 2) {
                    val name = parts[0]; val provider = parts[1]
                    when (provider) {
                        "drive" -> showDriveOAuth(name)
                        "s3" -> showS3Dialog(name)
                        "webdav" -> showWebdavDialog(name)
                    }
                }
            }
            action == "test_rclone" -> viewModel.testRcloneConnection()
        }
    }

    private fun showDriveOAuth(name: String) {
        viewModel.createDriveConfig(name)
    }

    private fun showS3Dialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 16)
        }
        val provLabels = listOf("AWS S3","Cloudflare R2","MinIO","阿里云OSS","腾讯COS","华为OBS","DigitalOcean","Wasabi","其他")
        val provVals = listOf("AWS","Cloudflare","Minio","Alibaba","TencentCOS","HuaweiOBS","DigitalOcean","Wasabi","Other")
        col.addView(TextView(ctx).apply { text = "服务商"; textSize = 13f })
        val pSpin = android.widget.Spinner(ctx)
        pSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, provLabels)
        col.addView(pSpin)
        val ek = EditText(ctx).apply { hint = "Access Key ID"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        val sk = EditText(ctx).apply { hint = "Secret Access Key"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(TextView(ctx).apply { text = "Access Key ID"; textSize = 13f }); col.addView(ek)
        col.addView(TextView(ctx).apply { text = "Secret Access Key"; textSize = 13f }); col.addView(sk)
        val allRegions = listOf("us-east-1","us-east-2","us-west-1","us-west-2","eu-west-1","eu-central-1","ap-northeast-1","ap-southeast-1","cn-north-1","oss-cn-hangzhou","oss-cn-beijing","ap-beijing","ap-guangzhou","auto","")
        col.addView(TextView(ctx).apply { text = "区域"; textSize = 13f })
        val rSpin = android.widget.Spinner(ctx)
        rSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, allRegions)
        col.addView(rSpin)
        col.addView(TextView(ctx).apply { text = "Endpoint（留空自动填充）"; textSize = 13f })
        val ep = EditText(ctx).apply { hint = "留空自动填充"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(ep)
        android.app.AlertDialog.Builder(ctx).setTitle("S3 对象存储").setView(col)
            .setPositiveButton("保存") { _, _ ->
                val s3n = provVals[pSpin.selectedItemPosition]
                val region = rSpin.selectedItem.toString()
                var endpoint = ep.text.toString().trim()
                val ak = ek.text.toString().trim(); val ask = sk.text.toString().trim()
                if (ak.isEmpty() || ask.isEmpty()) return@setPositiveButton
                if (endpoint.isEmpty()) endpoint = viewModel.getS3Endpoint(s3n, region)
                viewModel.saveS3Config(name, s3n, region, endpoint, ak, ask)
            }.setNegativeButton("取消", null).show()
    }

    private fun showWebdavDialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 16)
        }
        col.addView(TextView(ctx).apply { text = "服务类型"; textSize = 13f })
        val vSpin = android.widget.Spinner(ctx)
        vSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("nextcloud","owncloud","sharepoint","fastmail","other"))
        col.addView(vSpin)
        col.addView(TextView(ctx).apply { text = "WebDAV 地址"; textSize = 13f })
        val urlEt = EditText(ctx).apply { hint = "https://example.com/remote.php/dav/files/user"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(urlEt)
        col.addView(TextView(ctx).apply { text = "用户名"; textSize = 13f })
        val userEt = EditText(ctx).apply { textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(userEt)
        col.addView(TextView(ctx).apply { text = "密码"; textSize = 13f })
        val passEt = EditText(ctx).apply { textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(passEt)
        android.app.AlertDialog.Builder(ctx).setTitle("WebDAV").setView(col)
            .setPositiveButton("保存") { _, _ ->
                var url = urlEt.text.toString().trim()
                val user = userEt.text.toString().trim(); val pass = passEt.text.toString().trim()
                val vendor = vSpin.selectedItem.toString()
                if (url.isEmpty() || user.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http")) url = "https://$url"
                viewModel.saveWebdavConfig(name, url, vendor, user, pass)
            }.setNegativeButton("取消", null).show()
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
                    if (item.key == "zstd") {
                        runCatching { BackupHookLocal.setCompressionUseZstd(checked) }
                    }
                }
            }
            is SettingsItem.Input -> {
                val card = holder.itemView as CardView
                val col = card.getChildAt(0) as? LinearLayout
                val label = col?.getChildAt(0) as? TextView
                label?.text = item.label
                val et = col?.getChildAt(1) as? EditText
                if (et != null) {
                    if (item.key == "rclone_conf") {
                        et.setText(item.def); et.minLines = 10; et.gravity = Gravity.START
                        et.typeface = Typeface.MONOSPACE; et.textSize = 11f
                        et.setBackgroundColor(0xFFF5F5F5.toInt())
                        et.setPadding(8, 8, 8, 8)
                    } else {
                        et.setText(runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject()).optString(item.key, item.def))
                        et.hint = item.hint
                    }
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
                    // For rclone save, pass the input text
                    if (item.action == "save_rclone") {
                        // Find the Input with key "rclone_conf" and get its text
                        for (i in items.indices) {
                            if (items[i] is SettingsItem.Input && (items[i] as SettingsItem.Input).key == "rclone_conf") {
                                val vh = recyclerView.findViewHolderForAdapterPosition(i)
                                if (vh != null) {
                                    val card = vh.itemView as CardView
                                    val col = card.getChildAt(0) as? LinearLayout
                                    val et = col?.getChildAt(1) as? EditText
                                    if (et != null) {
                                        onAction(item.action, et.text.toString())
                                        return@setOnClickListener
                                    }
                                }
                            }
                        }
                    }
                    onAction(item.action, null) // rclone_create buttons pass action
                }
            }
            is SettingsItem.Info -> (holder.itemView as TextView).text = item.text
        }
    }
}
