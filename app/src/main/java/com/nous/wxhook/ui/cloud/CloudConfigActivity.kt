package com.nous.wxhook.ui.cloud

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.service.SyncService
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class CloudConfigActivity : AppCompatActivity() {

    private val viewModel: CloudConfigViewModel by viewModels()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(M3.colorSurface(this@CloudConfigActivity)); setStroke(1, M3.colorOutline(this@CloudConfigActivity)) }
        elevation = dp(2).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "云同步配置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BackupHookLocal.init(this)
        buildUI()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.toastMessage.isNotEmpty()) { Toast.makeText(this@CloudConfigActivity, state.toastMessage, Toast.LENGTH_LONG).show(); viewModel.clearToast() }
                    if (state.testResult.isNotEmpty()) supportActionBar?.title = state.testResult
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); viewModel.loadAll() }

    private fun buildUI() {
        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // Status
        val statusCard = cardBg()
        statusCard.addView(TextView(this).apply { text = "📊 同步状态"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        val statusText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0); minLines = 4 }
        statusCard.addView(statusText)
        root.addView(statusCard)

        // Remote config
        val configCard = cardBg()
        configCard.addView(TextView(this).apply { text = "🔑 远端配置"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        val remotes = viewModel.uiState.value.remotes
        if (remotes.isEmpty()) {
            configCard.addView(TextView(this).apply { text = "暂无配置"; textSize = 14f; setTextColor(M3.onSurfaceVariant(this@CloudConfigActivity)); setPadding(0, dp(8), 0, 0) })
        } else {
            for (r in remotes) {
                configCard.addView(TextView(this).apply { text = "📦 ${r.name} (${r.type})"; textSize = 14f; setPadding(0, dp(4), 0, 0) })
            }
        }
        val addBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        addBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+ WebDAV"; insetTop = 0; insetBottom = 0
            setOnClickListener { val n = "remote${System.currentTimeMillis()%10000}"; showWebdavDialog(n) }
        })
        addBtns.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        addBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+ S3"; insetTop = 0; insetBottom = 0
            setOnClickListener { val n = "remote${System.currentTimeMillis()%10000}"; showS3Dialog(n) }
        })
        configCard.addView(addBtns)
        configCard.addView(MaterialButton(this).apply {
            text = "☁️ + 阿里云盘"; insetTop = 0; insetBottom = 0
            setOnClickListener { val n = "remote${System.currentTimeMillis()%10000}"; showAliyundriveDialog(n) }
        })
        root.addView(configCard)

        // Sync
        val syncCard = cardBg()
        syncCard.addView(TextView(this).apply { text = "▶️ 同步控制"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        syncCard.addView(MaterialButton(this).apply {
            text = "☁️ 立即同步"; insetTop = 0; insetBottom = 0
            setOnClickListener { SyncService.start(this@CloudConfigActivity) }
        })
        root.addView(syncCard)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })

        sv.addView(root)
        setContentView(sv)

        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiState.collect { state -> if (state.statusText.isNotEmpty()) statusText.text = state.statusText }
        }}
    }

    private fun showWebdavDialog(name: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        col.addView(TextView(this).apply { text = "WebDAV 地址"; textSize = 13f })
        val url = EditText(this).apply { hint = "https://..." }
        col.addView(url)
        col.addView(TextView(this).apply { text = "用户名"; textSize = 13f })
        val user = EditText(this)
        col.addView(user)
        col.addView(TextView(this).apply { text = "密码"; textSize = 13f })
        val pass = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(pass)
        col.addView(TextView(this).apply { text = "远端目录"; textSize = 13f })
        val path = EditText(this).apply { hint = "wxhook-backup" }
        col.addView(path)
        android.app.AlertDialog.Builder(this).setTitle("WebDAV").setView(col)
            .setPositiveButton("保存") { _, _ ->
                var u = url.text.toString().trim(); if (!u.startsWith("http")) u = "https://$u"
                if (u.isBlank() || user.text.toString().isBlank()) return@setPositiveButton
                viewModel.saveWebdavConfig(name, u, "other", user.text.toString(), pass.text.toString(), path.text.toString().ifEmpty { "wxhook-backup" })
                viewModel.loadAll()
            }.setNegativeButton("取消", null).show()
    }

    private fun showS3Dialog(name: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        col.addView(TextView(this).apply { text = "Access Key ID"; textSize = 13f })
        val ak = EditText(this)
        col.addView(ak)
        col.addView(TextView(this).apply { text = "Secret Access Key"; textSize = 13f })
        val sk = EditText(this)
        col.addView(sk)
        col.addView(TextView(this).apply { text = "Endpoint"; textSize = 13f })
        val ep = EditText(this).apply { hint = "https://..." }
        col.addView(ep)
        android.app.AlertDialog.Builder(this).setTitle("S3").setView(col)
            .setPositiveButton("保存") { _, _ ->
                if (ak.text.toString().isBlank() || sk.text.toString().isBlank()) return@setPositiveButton
                viewModel.saveS3Config(name, "Other", "auto", ep.text.toString(), ak.text.toString(), sk.text.toString())
                viewModel.loadAll()
            }.setNegativeButton("取消", null).show()
    }

    private fun showAliyundriveDialog(name: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        col.addView(TextView(this).apply { text = "Refresh Token"; textSize = 13f })
        val token = EditText(this)
        col.addView(token)
        col.addView(TextView(this).apply { text = "API 地址"; textSize = 13f })
        val api = EditText(this).apply { setText("https://api.oplist.org/alicloud/renewapi") }
        col.addView(api)
        col.addView(TextView(this).apply { text = "远端目录"; textSize = 13f })
        val path = EditText(this).apply { hint = "wxhook-backup" }
        col.addView(path)
        android.app.AlertDialog.Builder(this).setTitle("阿里云盘").setView(col)
            .setPositiveButton("保存") { _, _ ->
                if (token.text.toString().isBlank()) return@setPositiveButton
                viewModel.saveAliyundriveConfig(name, token.text.toString(), api.text.toString(), "root", path.text.toString().ifEmpty { "wxhook-backup" })
                viewModel.loadAll()
            }.setNegativeButton("取消", null).show()
    }
}
