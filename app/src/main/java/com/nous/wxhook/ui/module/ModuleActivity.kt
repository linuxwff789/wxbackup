package com.nous.wxhook.ui.module

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch

class ModuleActivity : AppCompatActivity() {

    private val viewModel: ModuleViewModel by viewModels()
    private lateinit var logText: android.widget.TextView
    private lateinit var statusText: android.widget.TextView
    private lateinit var recordsText: android.widget.TextView
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
                    statusText.text = state.statusText.ifEmpty { "暂无状态信息" }
                    if (state.recordsText.isNotEmpty()) recordsText.text = state.recordsText
                    if (state.logText.isNotEmpty()) logText.text = state.logText
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(bg: Int = Color.WHITE): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
            setCardBackgroundColor(bg)
            isClickable = false
            isFocusable = false
        }
    }

    private fun btn(text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }
    }

    private fun outlinedBtn(text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(text: String): android.widget.TextView {
        return android.widget.TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.onSurface(this@ModuleActivity))
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun buildUI() {
        val scrollView = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ── 📊 状态 ──
        val statusCard = card()
        statusCard.addView(sectionTitle("📊 状态"))
        statusText = android.widget.TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        statusCard.addView(statusText)
        statusCard.addView(space(8))

        val statusBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        statusBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "🔍 检测环境"
            insetTop = 0; insetBottom = 0
            setOnClickListener { viewModel.checkEnvironment() }
        })
        statusBtns.addView(space(12))
        statusBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🔄 刷新"
            insetTop = 0; insetBottom = 0
            setOnClickListener { viewModel.refreshStatus() }
        })
        statusCard.addView(statusBtns)
        root.addView(statusCard)
        root.addView(space(12))

        // ── 📁 备份路径 ──
        val pathCard = card(0xFFF5F0FF.toInt())
        pathCard.addView(sectionTitle("📁 备份路径"))
        pathInput = TextInputEditText(this).apply {
            setText(BackupManager.BACKUP_DIR)
            textSize = 14f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        pathCard.addView(pathInput)
        pathCard.addView(space(8))
        pathCard.addView(MaterialButton(this).apply {
            text = "💾 保存路径"
            insetTop = 0; insetBottom = 0
            setOnClickListener {
                viewModel.saveBackupPath(pathInput.text?.toString()?.trim() ?: "")
                Toast.makeText(this@ModuleActivity, "路径已保存", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(pathCard)
        root.addView(space(12))

        // ── 💾 备份操作 ──
        val backupCard = card()
        backupCard.addView(sectionTitle("💾 备份操作"))
        backupCard.addView(btn("全量备份 (DB + 附件)") {
            viewModel.startBackup(false)
        })
        backupCard.addView(space(10))
        backupCard.addView(outlinedBtn("增量备份 (仅新文件)") {
            viewModel.startBackup(true)
        })
        root.addView(backupCard)
        root.addView(space(12))

        // ── ☁️ 云同步 ──
        val syncCard = card()
        syncCard.addView(sectionTitle("☁️ 云同步"))

        val syncRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        syncRow.addView(android.widget.TextView(this).apply {
            text = "启用同步"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val syncSwitch = SwitchMaterial(this).apply {
            isChecked = viewModel.uiState.value.remoteEnabled
            setOnCheckedChangeListener { _, checked -> viewModel.setRemoteEnabled(checked) }
        }
        syncRow.addView(syncSwitch)
        syncCard.addView(syncRow)
        syncCard.addView(space(10))

        val syncBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        syncBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "☁️ 同步到云盘"
            insetTop = 0; insetBottom = 0
            setOnClickListener { viewModel.doSync() }
        })
        syncBtns.addView(space(12))
        syncBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "⚙️ 配置"
            setTextColor(M3.colorPrimary(this@ModuleActivity))
            insetTop = 0; insetBottom = 0; minWidth = 0
            setOnClickListener {
                startActivity(Intent(this@ModuleActivity, com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java))
            }
        })
        syncCard.addView(syncBtns)
        root.addView(syncCard)
        root.addView(space(12))

        // ── 🛠 工具 ──
        val toolsCard = card(0xFFF5F0FF.toInt())
        toolsCard.addView(sectionTitle("🛠 工具"))
        toolsCard.addView(outlinedBtn("🔄 重建备份状态") {
            viewModel.rebuildState()
        })
        root.addView(toolsCard)
        root.addView(space(12))

        // ── 📋 备份记录 ──
        val recordsCard = card()
        recordsCard.addView(sectionTitle("📋 备份记录"))
        recordsText = android.widget.TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF616161.toInt())
        }
        recordsCard.addView(recordsText)
        recordsCard.addView(space(8))
        recordsCard.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "🔄 刷新记录"
            setTextColor(M3.colorPrimary(this@ModuleActivity))
            insetTop = 0; insetBottom = 0; minWidth = 0
            setOnClickListener { viewModel.refreshRecords() }
        })
        root.addView(recordsCard)
        root.addView(space(12))

        // ── 📝 运行日志 ──
        val logCard = card()
        logCard.addView(sectionTitle("📝 运行日志"))
        logText = android.widget.TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF9E9E9E.toInt())
            minLines = 3
        }
        logCard.addView(logText)
        root.addView(logCard)
        root.addView(space(16))

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun space(h: Int): android.view.View {
        return object : android.view.View(this) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                setMeasuredDimension(1, dp(h))
            }
        }
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
