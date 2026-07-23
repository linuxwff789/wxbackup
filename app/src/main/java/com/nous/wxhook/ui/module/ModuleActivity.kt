package com.nous.wxhook.ui.module

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.textfield.TextInputEditText
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch

class ModuleActivity : AppCompatActivity() {

    private val viewModel: ModuleViewModel by viewModels()
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordsText: TextView
    private lateinit var pathInput: TextInputEditText

    private val backupFinishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            if (intent?.action == com.nous.wxhook.service.BackupService.ACTION_FINISH) {
                val ok = intent.getBooleanExtra(com.nous.wxhook.service.BackupService.EXTRA_OK, false)
                val msg = intent.getStringExtra(com.nous.wxhook.service.BackupService.EXTRA_MSG) ?: ""
                viewModel.onBackupFinished(ok, msg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.nous.wxhook.util.SetupManager.setup(this)
        BackupHookLocal.init(this)
        registerReceiver(backupFinishReceiver,
            android.content.IntentFilter(com.nous.wxhook.service.BackupService.ACTION_FINISH),
            RECEIVER_NOT_EXPORTED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001) } catch (_: Exception) {}
        }
        supportActionBar?.title = "备份管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        buildUI()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    statusText.text = state.statusText.ifEmpty { "暂无状态信息" }
                    if (state.recordsText.isNotEmpty()) recordsText.text = state.recordsText
                    if (state.logText.isNotEmpty()) logText.text = state.logText
                }
            }
        }
    }

    // ── helpers ──
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 垂直 LinearLayout 卡片 */
    private fun cardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(M3.colorSurface(this@ModuleActivity))
                setStroke(1, M3.colorOutline(this@ModuleActivity))
            }
            elevation = dp(2).toFloat()
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(M3.onSurface(this@ModuleActivity))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        )
        insetTop = 0; insetBottom = 0
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun outlinedButton(text: String, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        )
        insetTop = 0; insetBottom = 0
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(h)
        )
    }

    private fun textBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f
        setTextColor(M3.colorPrimary(this@ModuleActivity))
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    // ── UI ──
    private fun buildUI() {
        val sv = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // ═══ 📊 状态 ═══
        val statusCard = cardLayout()
        statusCard.addView(sectionTitle("📊 状态"))
        statusText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE }
        statusCard.addView(statusText)
        statusCard.addView(spacer(8))
        val statusBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusBtns.addView(primaryButton("🔍 检测环境") { viewModel.checkEnvironment() })
        statusBtns.addView(spacer(12))
        statusBtns.addView(outlinedButton("🔄 刷新") { viewModel.refreshStatus() })
        statusCard.addView(statusBtns)
        root.addView(statusCard)

        // ═══ 📁 备份路径 ═══
        val pathCard = cardLayout()
        pathCard.addView(sectionTitle("📁 备份路径"))
        pathInput = TextInputEditText(this).apply {
            setText(BackupManager.BACKUP_DIR); textSize = 14f
        }
        pathCard.addView(pathInput)
        pathCard.addView(spacer(8))
        pathCard.addView(primaryButton("💾 保存路径") {
            viewModel.saveBackupPath(pathInput.text?.toString()?.trim() ?: "")
            Toast.makeText(this, "路径已保存", Toast.LENGTH_SHORT).show()
        })
        root.addView(pathCard)

        // ═══ 💾 备份操作 ═══
        val backupCard = cardLayout()
        backupCard.addView(sectionTitle("💾 备份操作"))
        backupCard.addView(primaryButton("全量备份 (DB + 附件)") { viewModel.startBackup(false) })
        backupCard.addView(spacer(10))
        backupCard.addView(outlinedButton("增量备份 (仅新文件)") { viewModel.startBackup(true) })
        root.addView(backupCard)

        // ═══ ☁️ 云同步 ═══
        val syncCard = cardLayout()
        syncCard.addView(sectionTitle("☁️ 云同步"))
        val syncRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        syncRow.addView(TextView(this).apply {
            text = "启用同步"; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        syncRow.addView(SwitchMaterial(this).apply {
            isChecked = viewModel.uiState.value.remoteEnabled
            setOnCheckedChangeListener { _, c -> viewModel.setRemoteEnabled(c) }
        })
        syncCard.addView(syncRow)

        val syncBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        syncBtns.addView(primaryButton("☁️ 同步到云盘") { viewModel.doSync() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)
            )
        })
        syncBtns.addView(spacer(12))
        syncBtns.addView(textBtn("⚙️ 配置") {
            startActivity(Intent(this, com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java))
        })
        syncCard.addView(syncBtns)
        root.addView(syncCard)

        // ═══ 🛠 工具 ═══
        val toolsCard = cardLayout()
        toolsCard.addView(sectionTitle("🛠 工具"))
        toolsCard.addView(outlinedButton("🔄 重建备份状态") { viewModel.rebuildState() })
        toolsCard.addView(spacer(10))
        toolsCard.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "⬇️ 从备份恢复微信"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            insetTop = 0; insetBottom = 0
            setTextColor(android.graphics.Color.parseColor("#FF5722"))
            isClickable = true; isFocusable = true
            setOnClickListener {
                android.app.AlertDialog.Builder(this@ModuleActivity)
                    .setTitle("⚠️ 从备份恢复微信")
                    .setMessage("此操作将：\n" +
                        "1. 停止微信\n" +
                        "2. 用备份全量包重建数据库\n" +
                        "3. 替换微信当前数据库\n\n" +
                        "⚠️ 当前数据会被备份到备份目录后再覆盖，但建议您先手动全量备份一次。\n\n" +
                        "确定继续吗？")
                    .setPositiveButton("确定恢复") { _, _ -> viewModel.startRestore() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        root.addView(toolsCard)

        // ═══ 📋 备份记录 ═══
        val recordsCard = cardLayout()
        recordsCard.addView(sectionTitle("📋 备份记录"))
        recordsText = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(M3.onSurfaceVariant(this@ModuleActivity)) }
        recordsCard.addView(recordsText)
        recordsCard.addView(spacer(4))
        recordsCard.addView(textBtn("🔄 刷新记录") { viewModel.refreshRecords() })
        root.addView(recordsCard)

        // ═══ 📝 运行日志（可折叠）═══
        val logCard = cardLayout()
        val logTitle = TextView(this).apply {
            text = "📝 运行日志  ▾"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.onSurface(this@ModuleActivity))
            isClickable = true; isFocusable = true
        }
        val logContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE  // 默认折叠
        }
        logTitle.setOnClickListener {
            val expanded = logContent.visibility != android.view.View.VISIBLE
            logContent.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            logTitle.text = if (expanded) "📝 运行日志  ▾" else "📝 运行日志  ▸"
        }
        logCard.addView(logTitle)

        logText = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(M3.onSurfaceVariant(this@ModuleActivity)); minLines = 3 }
        logContent.addView(logText)
        logContent.addView(spacer(4))
        logContent.addView(textBtn("🗑 清除日志") { viewModel.clearLog() })
        logCard.addView(logContent)
        root.addView(logCard)
        root.addView(spacer(16))

        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { runCatching { unregisterReceiver(backupFinishReceiver) }; super.onDestroy() }
}
