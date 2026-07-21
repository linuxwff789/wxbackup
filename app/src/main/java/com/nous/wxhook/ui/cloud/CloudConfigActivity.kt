package com.nous.wxhook.ui.cloud

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.service.SyncService
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class CloudConfigActivity : AppCompatActivity() {

    private val viewModel: CloudConfigViewModel by viewModels()

    // WebDAV fields
    private lateinit var wdUrl: EditText
    private lateinit var wdUser: EditText
    private lateinit var wdPass: EditText
    private lateinit var wdPath: EditText

    // AliyunDrive fields
    private lateinit var alToken: EditText
    private lateinit var alApi: EditText
    private lateinit var alPath: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(M3.colorSurface(this@CloudConfigActivity))
            setStroke(1, M3.colorOutline(this@CloudConfigActivity))
        }
        elevation = dp(2).toFloat()
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(M3.onSurface(this@CloudConfigActivity))
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(M3.onSurfaceVariant(this@CloudConfigActivity))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun primaryBtn(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text; insetTop = 0; insetBottom = 0
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        setOnClickListener { onClick() }
    }

    private fun outlinedBtn(text: String, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.text = text; insetTop = 0; insetBottom = 0
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "云同步配置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BackupHookLocal.init(this)
        buildUI()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); viewModel.loadAll() }

    private fun buildUI() {
        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // ═══ 状态 ═══
        val statusCard = cardBg()
        statusCard.addView(sectionTitle("📊 同步状态"))
        val statusText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0); minLines = 3 }
        statusCard.addView(statusText)
        root.addView(statusCard)

        // ═══ WebDAV 配置 ═══
        val webdavCard = cardBg()
        webdavCard.addView(sectionTitle("🔗 WebDAV"))
        webdavCard.addView(fieldLabel("服务器地址"))
        wdUrl = EditText(this).apply { hint = "https://example.com/dav/" }
        webdavCard.addView(wdUrl)
        webdavCard.addView(fieldLabel("用户名"))
        wdUser = EditText(this)
        webdavCard.addView(wdUser)
        webdavCard.addView(fieldLabel("密码"))
        wdPass = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        webdavCard.addView(wdPass)
        webdavCard.addView(fieldLabel("远端目录"))
        wdPath = EditText(this).apply { hint = "wxhook-backup" }
        webdavCard.addView(wdPath)
        webdavCard.addView(spacer(8))

        val wdBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wdBtns.addView(primaryBtn("💾 保存") { saveWebdav() })
        wdBtns.addView(spacer(12))
        wdBtns.addView(outlinedBtn("🔍 测试连接") { testWebdav() })
        webdavCard.addView(wdBtns)
        root.addView(webdavCard)

        // ═══ 阿里云盘 ═══
        val aliyunCard = cardBg()
        aliyunCard.addView(sectionTitle("☁️ 阿里云盘"))
        aliyunCard.addView(fieldLabel("Refresh Token"))
        alToken = EditText(this).apply { hint = "eyJ0eXAiOiJKV1Qi..." }
        aliyunCard.addView(alToken)
        aliyunCard.addView(fieldLabel("API 地址"))
        alApi = EditText(this).apply { setText("https://api.oplist.org/alicloud/renewapi") }
        aliyunCard.addView(alApi)
        aliyunCard.addView(fieldLabel("远端目录"))
        alPath = EditText(this).apply { hint = "wxhook-backup" }
        aliyunCard.addView(alPath)
        aliyunCard.addView(spacer(8))

        val alBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alBtns.addView(primaryBtn("💾 保存") { saveAliyun() })
        alBtns.addView(spacer(12))
        alBtns.addView(outlinedBtn("🔍 测试连接") { testAliyun() })
        aliyunCard.addView(alBtns)
        root.addView(aliyunCard)

        // ═══ 同步控制 ═══
        val syncCard = cardBg()
        syncCard.addView(sectionTitle("▶️ 同步控制"))
        syncCard.addView(primaryBtn("☁️ 立即同步") { SyncService.start(this@CloudConfigActivity) })
        root.addView(syncCard)
        root.addView(spacer(16))

        sv.addView(root)
        setContentView(sv)

        // 加载已保存的配置
        loadSavedConfigs()

        // 状态更新
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.statusText.isNotEmpty()) statusText.text = state.statusText
                    if (state.toastMessage.isNotEmpty()) {
                        Toast.makeText(this@CloudConfigActivity, state.toastMessage, Toast.LENGTH_LONG).show()
                        viewModel.clearToast()
                    }
                    if (state.testResult.isNotEmpty()) supportActionBar?.title = state.testResult
                }
            }
        }
    }

    private fun loadSavedConfigs() {
        try {
            val cfg = JSONObject(File(filesDir, "settings_config.json").readText())
            wdUrl.setText(cfg.optString("webdav_url", ""))
            wdUser.setText(cfg.optString("webdav_user", ""))
            wdPass.setText(cfg.optString("webdav_pass", ""))
            wdPath.setText(cfg.optString("remote_path", "wxhook-backup"))

            alToken.setText(cfg.optString("aliyundrive_refresh_token", ""))
            val apiUrl = cfg.optString("aliyundrive_api_url", "https://api.oplist.org/alicloud/renewapi")
            alApi.setText(apiUrl)
            alPath.setText(cfg.optString("remote_path", "wxhook-backup"))
        } catch (_: Exception) {}
    }

    private fun saveWebdav() {
        val url = wdUrl.text.toString().trim()
        val user = wdUser.text.toString().trim()
        val pass = wdPass.text.toString().trim()
        val path = wdPath.text.toString().trim().ifEmpty { "wxhook-backup" }
        if (url.isBlank() || user.isBlank()) {
            Toast.makeText(this, "地址和用户名不能为空", Toast.LENGTH_SHORT).show(); return
        }
        val finalUrl = if (!url.startsWith("http")) "https://$url" else url
        viewModel.saveWebdavConfig("webdav", finalUrl, "other", user, pass, path)
    }

    private fun testWebdav() {
        val url = wdUrl.text.toString().trim()
        val user = wdUser.text.toString().trim()
        val pass = wdPass.text.toString().trim()
        if (url.isBlank() || user.isBlank()) {
            Toast.makeText(this, "地址和用户名不能为空", Toast.LENGTH_SHORT).show(); return
        }
        val finalUrl = if (!url.startsWith("http")) "https://$url" else url
        viewModel.testWebdavConnection(finalUrl, user, pass)
    }

    private fun saveAliyun() {
        val token = alToken.text.toString().trim()
        val api = alApi.text.toString().trim().ifEmpty { "https://api.oplist.org/alicloud/renewapi" }
        val path = alPath.text.toString().trim().ifEmpty { "wxhook-backup" }
        if (token.isBlank()) {
            Toast.makeText(this, "Refresh Token 不能为空", Toast.LENGTH_SHORT).show(); return
        }
        viewModel.saveAliyundriveConfig("aliyundrive", token, api, "root", path)
    }

    private fun testAliyun() {
        val token = alToken.text.toString().trim()
        val api = alApi.text.toString().trim().ifEmpty { "https://api.oplist.org/alicloud/renewapi" }
        if (token.isBlank()) {
            Toast.makeText(this, "Refresh Token 不能为空", Toast.LENGTH_SHORT).show(); return
        }
        viewModel.testAliyundriveConnection(token, api)
    }
}
