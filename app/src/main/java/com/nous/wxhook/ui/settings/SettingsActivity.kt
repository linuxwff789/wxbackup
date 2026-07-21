package com.nous.wxhook.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // Driver config fields
    private var wdUrl: EditText? = null
    private var wdUser: EditText? = null
    private var wdPass: EditText? = null
    private var wdPath: EditText? = null
    private var alToken: EditText? = null
    private var alApi: EditText? = null
    private var alPath: EditText? = null
    private var s3Ak: EditText? = null
    private var s3Sk: EditText? = null
    private var s3Ep: EditText? = null
    private var s3Region: EditText? = null
    private var s3Bucket: EditText? = null

    private var dynamicContainer: LinearLayout? = null
    private var testBtn: com.google.android.material.button.MaterialButton? = null
    private var saveBtn: com.google.android.material.button.MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BackupHookLocal.init(this)

        val scrollView = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3.dp(this@SettingsActivity, 16), M3.dp(this@SettingsActivity, 12), M3.dp(this@SettingsActivity, 16), M3.dp(this@SettingsActivity, 16))
        }

        buildDriverConfig(root)
        buildOtherSettings(root)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── 云存储驱动配置（下拉 + 动态字段 + 测试） ──
    private fun buildDriverConfig(root: LinearLayout) {
        val d = M3.dp(this@SettingsActivity, 1).toFloat()

        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            radius = d * 12
            setContentPadding((d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt())
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (d * 12).toInt()
            layoutParams = lp
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(MaterialTextView(this, null, com.google.android.material.R.attr.textAppearanceTitleMedium).apply {
            text = "☁️ 云存储驱动"
            setTextColor(M3.colorPrimary(this@SettingsActivity))
        })

        // 驱动类型下拉框
        val drivers = listOf("无", "WebDAV", "阿里云盘", "S3")
        val driverKeys = listOf("", "webdav", "aliyundrive", "s3")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, drivers)
        }
        col.addView(spinner)
        col.addView(spacer(8))

        // 动态字段容器
        dynamicContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(dynamicContainer!!)

        // 按钮行
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        saveBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "💾 保存"; insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, M3.dp(this@SettingsActivity, 48), 1f)
            setOnClickListener { saveDriverConfig(driverKeys[spinner.selectedItemPosition]) }
        }
        btnRow.addView(saveBtn!!)
        btnRow.addView(spacer(12))

        testBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🔍 测试连接"; insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, M3.dp(this@SettingsActivity, 48), 1f)
            setOnClickListener { testDriver(driverKeys[spinner.selectedItemPosition]) }
        }
        btnRow.addView(testBtn!!)
        col.addView(spacer(8))
        col.addView(btnRow)

        card.addView(col)
        root.addView(card)

        // 下拉切换时更新字段
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateDriverFields(driverKeys[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 加载已保存的 driver 类型
        try {
            val cfg = JSONObject(File(filesDir, "settings_config.json").readText())
            val saved = cfg.optString("sync_driver", "")
            val idx = driverKeys.indexOf(saved).coerceAtLeast(0)
            spinner.setSelection(idx)
        } catch (_: Exception) {}

        loadDriverConfig()
    }

    private fun updateDriverFields(driver: String) {
        dynamicContainer?.removeAllViews()
        wdUrl = null; wdUser = null; wdPass = null; wdPath = null
        alToken = null; alApi = null; alPath = null
        s3Ak = null; s3Sk = null; s3Ep = null; s3Region = null; s3Bucket = null

        val ctx = this
        val d = M3.dp(ctx, 1).toFloat()
        fun field(hint: String) = EditText(ctx).apply {
            this.hint = hint; textSize = 14f
            setPadding(0, (d * 4).toInt(), 0, (d * 4).toInt())
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        when (driver) {
            "webdav" -> {
                dynamicContainer?.addView(label("服务器地址"))
                wdUrl = field("https://example.com/dav/").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("用户名"))
                wdUser = field("").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("密码"))
                wdPass = field("").also { dynamicContainer?.addView(it) }
                wdPass?.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                dynamicContainer?.addView(label("远端目录（默认 wxhook-backup）"))
                wdPath = field("wxhook-backup").also { dynamicContainer?.addView(it) }
            }
            "aliyundrive" -> {
                dynamicContainer?.addView(label("Refresh Token"))
                alToken = field("eyJ0eXAiOiJKV1Qi...").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("API 地址"))
                alApi = field("https://api.oplist.org/alicloud/renewapi").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("远端目录（默认 wxhook-backup）"))
                alPath = field("wxhook-backup").also { dynamicContainer?.addView(it) }
            }
            "s3" -> {
                dynamicContainer?.addView(label("Access Key"))
                s3Ak = field("").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("Secret Key"))
                s3Sk = field("").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("Endpoint"))
                s3Ep = field("https://s3.amazonaws.com").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("区域（Region）"))
                s3Region = field("us-east-1").also { dynamicContainer?.addView(it) }
                dynamicContainer?.addView(label("Bucket 名称"))
                s3Bucket = field("").also { dynamicContainer?.addView(it) }
            }
        }
        loadDriverConfig()
    }

    private fun loadDriverConfig() {
        try {
            val cfg = JSONObject(File(filesDir, "settings_config.json").readText())
            wdUrl?.setText(cfg.optString("webdav_url", ""))
            wdUser?.setText(cfg.optString("webdav_user", ""))
            wdPass?.setText(cfg.optString("webdav_pass", ""))
            wdPath?.setText(cfg.optString("remote_path", "wxhook-backup"))
            alToken?.setText(cfg.optString("aliyundrive_refresh_token", ""))
            alApi?.setText(cfg.optString("aliyundrive_api_url", "https://api.oplist.org/alicloud/renewapi"))
            alPath?.setText(cfg.optString("remote_path", "wxhook-backup"))
            s3Ak?.setText(cfg.optString("s3_access_key", ""))
            s3Sk?.setText(cfg.optString("s3_secret_key", ""))
            s3Ep?.setText(cfg.optString("s3_endpoint", ""))
            s3Region?.setText(cfg.optString("s3_region", ""))
            s3Bucket?.setText(cfg.optString("s3_bucket", ""))
        } catch (_: Exception) {}
    }

    private fun saveDriverConfig(driver: String) {
        try {
            val f = File(filesDir, "settings_config.json")
            val cfg = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            cfg.put("sync_driver", driver)
            cfg.put("webdav_url", wdUrl?.text?.toString()?.trim() ?: "")
            cfg.put("webdav_user", wdUser?.text?.toString()?.trim() ?: "")
            cfg.put("webdav_pass", wdPass?.text?.toString()?.trim() ?: "")
            cfg.put("remote_path", wdPath?.text?.toString()?.trim()?.ifEmpty { "wxhook-backup" } ?: "wxhook-backup")
            cfg.put("aliyundrive_refresh_token", alToken?.text?.toString()?.trim() ?: "")
            cfg.put("aliyundrive_api_url", alApi?.text?.toString()?.trim()?.ifEmpty { "https://api.oplist.org/alicloud/renewapi" } ?: "https://api.oplist.org/alicloud/renewapi")
            cfg.put("s3_access_key", s3Ak?.text?.toString()?.trim() ?: "")
            cfg.put("s3_secret_key", s3Sk?.text?.toString()?.trim() ?: "")
            cfg.put("s3_endpoint", s3Ep?.text?.toString()?.trim() ?: "")
            cfg.put("s3_region", s3Region?.text?.toString()?.trim() ?: "")
            cfg.put("s3_bucket", s3Bucket?.text?.toString()?.trim() ?: "")
            f.writeText(cfg.toString())
            Toast.makeText(this, "✅ 已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testDriver(driver: String) {
        Toast.makeText(this, "⏳ 测试中...", Toast.LENGTH_SHORT).show()
        when (driver) {
            "webdav" -> {
                val url = wdUrl?.text?.toString()?.trim() ?: ""
                val user = wdUser?.text?.toString()?.trim() ?: ""
                val pass = wdPass?.text?.toString()?.trim() ?: ""
                if (url.isBlank() || user.isBlank()) { Toast.makeText(this, "请输入地址和用户名", Toast.LENGTH_SHORT).show(); return }
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                viewModel.testWebDavConnection(finalUrl, user, pass)
            }
            "aliyundrive" -> {
                val token = alToken?.text?.toString()?.trim() ?: ""
                val api = alApi?.text?.toString()?.trim()?.ifEmpty { "https://api.oplist.org/alicloud/renewapi" } ?: "https://api.oplist.org/alicloud/renewapi"
                if (token.isBlank()) { Toast.makeText(this, "请输入 Refresh Token", Toast.LENGTH_SHORT).show(); return }
                viewModel.testAliyundriveConnection(token, api)
            }
            "s3" -> {
                Toast.makeText(this, "S3 测试暂未实现", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(this, "请先选择驱动", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 其他设置（RecyclerView） ──
    private fun buildOtherSettings(root: LinearLayout) {
        val items = mutableListOf<SettingsItem>()
        items.add(SettingsItem.Header("📂 备份"))
        items.add(SettingsItem.Input("备份路径", "backup_path", "/sdcard/Download/wxhook_backup"))
        items.add(SettingsItem.Toggle("使用 zstd 压缩", "zstd", false))
        items.add(SettingsItem.Header("🛠 工具"))
        items.add(SettingsItem.Action("🔄 重建备份状态", "rebuild_state"))
        items.add(SettingsItem.Header("⏱ 自动同步"))
        items.add(SettingsItem.Input("同步间隔（分钟）", "sync_interval_min", "", "留空=手动"))

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isNestedScrollingEnabled = false
        }
        recyclerView.adapter = SettingsAdapter(items, this@SettingsActivity) { action, _ ->
            handleAction(action)
            handleAction(action)
        }
        root.addView(recyclerView)
    }

    private fun handleAction(action: String) {
        when (action) {
            "rebuild_state" -> viewModel.rebuildState()
        }
    }

    private fun label(text: String) = MaterialTextView(this, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
        this.text = text; setPadding(0, M3.dp(this@SettingsActivity, 8), 0, M3.dp(this@SettingsActivity, 2))
        setTextColor(M3.onSurfaceVariant(this@SettingsActivity))
    }

    private fun spacer(w: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(M3.dp(this@SettingsActivity, w), 1)
    }
}
