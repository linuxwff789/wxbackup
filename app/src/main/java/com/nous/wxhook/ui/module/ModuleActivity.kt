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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.receiver.ScheduleManager
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch

class ModuleActivity : AppCompatActivity() {

    private val viewModel: ModuleViewModel by viewModels()
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordsText: TextView
    private lateinit var pathInput: TextInputEditText
    private lateinit var syncSwitch: SwitchMaterial
    private lateinit var syncButton: MaterialButton
    private lateinit var syncProgressRow: LinearLayout
    private lateinit var syncBar: LinearProgressIndicator
    private lateinit var syncTitleText: TextView
    private lateinit var syncDetailText: TextView
    private lateinit var backupProgressRow: LinearLayout
    private lateinit var backupBar: LinearProgressIndicator
    private lateinit var backupTitleText: TextView
    private lateinit var backupDetailText: TextView
    private lateinit var backupPercentText: TextView
    private lateinit var syncPercentText: TextView
    /** 最近一次收到备份进度的时间（避免 state 刷新早于服务广播时把进度区提前收起） */
    @Volatile private var backupProgressAt = 0L
    private val backupProgressReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            if (intent?.action == com.nous.wxhook.service.BackupService.ACTION_PROGRESS) {
                val percent = intent.getIntExtra(com.nous.wxhook.service.BackupService.EXTRA_PERCENT, -1)
                val detail = intent.getStringExtra(com.nous.wxhook.service.BackupService.EXTRA_DETAIL) ?: ""
                runOnUiThread {
                    if (!::backupProgressRow.isInitialized) return@runOnUiThread
                    backupProgressAt = System.currentTimeMillis()
                    backupProgressRow.visibility = android.view.View.VISIBLE
                    backupDetailText.text = detail
                    if (percent in 0..100) {
                        backupBar.isIndeterminate = false
                        backupBar.setProgressCompat(percent, true)
                        backupTitleText.text = "备份中"
                        backupPercentText.text = "$percent%"
                    } else {
                        backupBar.isIndeterminate = true
                        backupTitleText.text = "备份中..."
                        backupPercentText.text = ""
                    }
                }
            }
        }
    }
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
        ScheduleManager.updateAll(this)
        registerReceiver(backupFinishReceiver,
            android.content.IntentFilter(com.nous.wxhook.service.BackupService.ACTION_FINISH),
            RECEIVER_NOT_EXPORTED)
        registerReceiver(backupProgressReceiver,
            android.content.IntentFilter(com.nous.wxhook.service.BackupService.ACTION_PROGRESS),
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
                    if (::syncSwitch.isInitialized) syncSwitch.isChecked = state.remoteEnabled
                    if (::syncProgressRow.isInitialized) {
                        syncProgressRow.visibility =
                            if (state.syncRunning) android.view.View.VISIBLE else android.view.View.GONE
                        syncButton.isEnabled = !state.syncRunning
                        if (state.syncRunning) {
                            syncTitleText.text = state.syncTitle
                            syncDetailText.text = state.syncDetail
                            if (state.syncPercent >= 0) {
                                syncBar.isIndeterminate = false
                                syncBar.setProgressCompat(state.syncPercent, true)
                                syncPercentText.text = "${state.syncPercent}%"
                            } else {
                                syncBar.isIndeterminate = true
                                syncPercentText.text = ""
                            }
                        }
                    }
                    if (::backupProgressRow.isInitialized) {
                        if (state.backupRunning) {
                            // 开始备份后立刻显示（第一条服务广播要等约 1 秒）
                            if (backupProgressRow.visibility != android.view.View.VISIBLE) {
                                backupProgressRow.visibility = android.view.View.VISIBLE
                                backupTitleText.text = "备份中..."
                                backupDetailText.text = ""
                                backupPercentText.text = ""
                                backupBar.isIndeterminate = true
                            }
                        } else if (System.currentTimeMillis() - backupProgressAt > 3000) {
                            // 备份结束且 3 秒内没有新进度 → 收起进度区（服务那边同时会取消通知）
                            backupProgressRow.visibility = android.view.View.GONE
                            backupBar.isIndeterminate = true
                        }
                    }
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

    /** 显示时间选择器，回调传回选中的 时:分 */
    private fun showTimePicker(currentHour: Int, currentMinute: Int, onPicked: (Int, Int) -> Unit) {
        android.app.TimePickerDialog(this, { _, h, m -> onPicked(h, m) }, currentHour, currentMinute, true).show()
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

        // 备份进度（真实信号：数据库 dump 产物大小 / native 打包条目数，由服务每秒广播）
        backupProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        backupTitleText = TextView(this).apply {
            textSize = 13f
            setTextColor(M3.onSurface(this@ModuleActivity))
        }
        backupPercentText = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.colorPrimary(this@ModuleActivity))
        }
        val backupTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backupTitleRow.addView(backupTitleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        backupTitleRow.addView(backupPercentText)
        backupProgressRow.addView(backupTitleRow)
        backupBar = LinearProgressIndicator(
            this, null, com.google.android.material.R.attr.linearProgressIndicatorStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(8) }
        }
        backupProgressRow.addView(backupBar)
        backupDetailText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(M3.onSurfaceVariant(this@ModuleActivity))
            setPadding(0, dp(6), 0, 0)
        }
        backupProgressRow.addView(backupDetailText)
        backupCard.addView(backupProgressRow)
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
        syncSwitch = SwitchMaterial(this).apply {
            isChecked = viewModel.uiState.value.remoteEnabled
            setOnCheckedChangeListener { _, c -> viewModel.setRemoteEnabled(c) }
        }
        syncRow.addView(syncSwitch)
        syncCard.addView(syncRow)

        val syncBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        syncButton = primaryButton("☁️ 同步到云盘") { viewModel.doSync() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)
            )
        }
        syncBtns.addView(syncButton)
        syncBtns.addView(spacer(12))
        syncBtns.addView(textBtn("⚙️ 配置") {
            startActivity(Intent(this, com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java))
        })
        syncCard.addView(syncBtns)

        // 同步进度（只在同步进行中显示：标题 + 进度条 + 明细）
        syncProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        syncTitleText = TextView(this).apply {
            textSize = 13f
            setTextColor(M3.onSurface(this@ModuleActivity))
        }
        syncPercentText = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.colorPrimary(this@ModuleActivity))
        }
        val syncTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        syncTitleRow.addView(syncTitleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        syncTitleRow.addView(syncPercentText)
        syncProgressRow.addView(syncTitleRow)
        syncBar = LinearProgressIndicator(
            this, null, com.google.android.material.R.attr.linearProgressIndicatorStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(8) }
        }
        syncProgressRow.addView(syncBar)
        syncDetailText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(M3.onSurfaceVariant(this@ModuleActivity))
            setPadding(0, dp(6), 0, 0)
        }
        syncProgressRow.addView(syncDetailText)
        syncCard.addView(syncProgressRow)
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
    override fun onDestroy() {
        runCatching { unregisterReceiver(backupFinishReceiver) }
        runCatching { unregisterReceiver(backupProgressReceiver) }
        super.onDestroy()
    }
}
