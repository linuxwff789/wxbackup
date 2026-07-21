package com.nous.wxhook.ui.module

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch

class ModuleActivity : AppCompatActivity() {

    private val viewModel: ModuleViewModel by viewModels()
    private lateinit var logView: MaterialTextView
    private lateinit var statusView: MaterialTextView
    private lateinit var recordsView: MaterialTextView
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
        registerReceiver(
            backupFinishReceiver,
            android.content.IntentFilter(com.nous.wxhook.service.BackupService.ACTION_FINISH),
            RECEIVER_NOT_EXPORTED
        )

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            } catch (_: Exception) {}
        }

        supportActionBar?.title = "备份管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        buildUI()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    statusView.text = state.statusText.ifEmpty { "暂无状态信息" }
                    if (state.recordsText.isNotEmpty()) recordsView.text = state.recordsText
                    if (state.logText.isNotEmpty()) logView.text = state.logText
                }
            }
        }
    }

    private fun buildUI() {
        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        // ══════════════════════════════════════════
        // 📊 状态
        // ══════════════════════════════════════════
        val statusCard = M3.card(this)
        statusCard.addView(M3.titleMedium(this, "📊 状态"))
        statusView = M3.monoBody(this).apply {
            setPadding(0, M3.dp(this@ModuleActivity, 8), 0, M3.dp(this@ModuleActivity, 8))
        }
        statusCard.addView(statusView)

        val btnRow = M3.hLayout(this).apply {
            setPadding(0, M3.dp(this@ModuleActivity, 4), 0, 0)
        }
        btnRow.addView(M3.tonalButton(this, "🔍 检测环境") { viewModel.checkEnvironment() })
        btnRow.addView(M3.sp(this, 12))
        btnRow.addView(M3.outlinedButton(this, "🔄 刷新") { viewModel.refreshStatus() })
        statusCard.addView(btnRow)
        root.addView(statusCard)
        root.addView(M3.sp(this, 8))

        // ══════════════════════════════════════════
        // 📁 备份路径
        // ══════════════════════════════════════════
        val pathCard = M3.outlinedCard(this)
        pathCard.addView(M3.titleMedium(this, "📁 备份路径"))

        val pathLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputStyle
        ).apply {
            hint = "备份存储路径"
            helperText = "修改后点击保存"
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, M3.dp(this@ModuleActivity, 4), 0, 0)
        }
        pathInput = TextInputEditText(this).apply {
            setText(BackupManager.BACKUP_DIR)
        }
        pathLayout.addView(pathInput)
        pathCard.addView(pathLayout)

        pathCard.addView(M3.sp(this, 8))
        pathCard.addView(M3.tonalButton(this, "💾 保存路径") {
            viewModel.saveBackupPath(pathInput.text?.toString()?.trim() ?: "")
        })
        root.addView(pathCard)
        root.addView(M3.sp(this, 8))

        // ══════════════════════════════════════════
        // 💾 备份操作
        // ══════════════════════════════════════════
        val backupCard = M3.card(this)
        backupCard.addView(M3.titleMedium(this, "💾 备份操作"))

        backupCard.addView(M3.filledButton(this, "全量备份 (DB + 附件)") {
            viewModel.startBackup(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                M3.dp(this@ModuleActivity, 48)
            )
        })
        backupCard.addView(M3.sp(this, 8))
        backupCard.addView(M3.outlinedButton(this, "增量备份 (仅新文件)") {
            viewModel.startBackup(true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                M3.dp(this@ModuleActivity, 48)
            )
        })
        root.addView(backupCard)
        root.addView(M3.sp(this, 8))

        // ══════════════════════════════════════════
        // ☁️ 云同步
        // ══════════════════════════════════════════
        val syncCard = M3.card(this)
        syncCard.addView(M3.titleMedium(this, "☁️ 云同步"))

        val syncRow = M3.hLayout(this)
        syncRow.addView(M3.body(this, "启用同步").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val syncSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = viewModel.uiState.value.remoteEnabled
            setOnCheckedChangeListener { _, checked -> viewModel.setRemoteEnabled(checked) }
        }
        syncRow.addView(syncSwitch)
        syncCard.addView(syncRow)
        syncCard.addView(M3.sp(this, 8))

        val syncBtns = M3.hLayout(this)
        syncBtns.addView(M3.tonalButton(this, "☁️ 同步到云盘") { viewModel.doSync() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                M3.dp(this@ModuleActivity, 48)
            )
        })
        syncBtns.addView(M3.sp(this, 12))
        syncBtns.addView(
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "⚙️ 配置云存储"
                setTextColor(M3.colorPrimary(this@ModuleActivity))
                setOnClickListener {
                    startActivity(Intent(this@ModuleActivity, com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java))
                }
                insetTop = 0; insetBottom = 0; minWidth = 0
            }
        )
        syncCard.addView(syncBtns)
        root.addView(syncCard)
        root.addView(M3.sp(this, 8))

        // ══════════════════════════════════════════
        // 🛠 工具
        // ══════════════════════════════════════════
        val toolsCard = M3.outlinedCard(this)
        toolsCard.addView(M3.titleMedium(this, "🛠 工具"))
        toolsCard.addView(M3.textButton(this, "🔄 重建备份状态") {
            viewModel.rebuildState()
        })
        root.addView(toolsCard)
        root.addView(M3.sp(this, 8))

        // ══════════════════════════════════════════
        // 📋 备份记录
        // ══════════════════════════════════════════
        val recordsCard = M3.card(this)
        recordsCard.addView(M3.titleMedium(this, "📋 备份记录"))
        recordsView = M3.monoBody(this).apply {
            setPadding(0, M3.dp(this@ModuleActivity, 8), 0, 0)
        }
        recordsCard.addView(recordsView)
        recordsCard.addView(M3.sp(this, 8))
        recordsCard.addView(M3.textButton(this, "🔄 刷新记录") { viewModel.refreshRecords() })
        root.addView(recordsCard)
        root.addView(M3.sp(this, 8))

        // ══════════════════════════════════════════
        // 📝 运行日志
        // ══════════════════════════════════════════
        val logCard = M3.card(this)
        logCard.addView(M3.titleMedium(this, "📝 运行日志"))
        logView = M3.monoBody(this).apply {
            textSize = 11f
            setPadding(0, M3.dp(this@ModuleActivity, 8), 0, 0)
            setTextColor(M3.onSurfaceVariant(this@ModuleActivity))
            minLines = 4
        }
        logCard.addView(logView)
        root.addView(logCard)
        root.addView(M3.sp(this, 16))

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(backupFinishReceiver) }
        super.onDestroy()
    }
}
