package com.nous.wxhook.ui.module

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import kotlinx.coroutines.launch
import org.json.JSONObject

class ModuleActivity : AppCompatActivity() {

    private val viewModel: ModuleViewModel by viewModels()
    private lateinit var logView: TextView
    private lateinit var backupBtn: Button
    private lateinit var incrBtn: Button
    private lateinit var pathInput: EditText

    private val backupFinishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == com.nous.wxhook.service.BackupService.ACTION_FINISH) {
                val ok = intent.getBooleanExtra(com.nous.wxhook.service.BackupService.EXTRA_OK, false)
                val msg = intent.getStringExtra(com.nous.wxhook.service.BackupService.EXTRA_MSG) ?: ""
                viewModel.onBackupFinished(ok, msg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("wxhook:startup", "1")
        com.nous.wxhook.util.SetupManager.setup(this)
        android.util.Log.e("wxhook:startup", "2")
        com.nous.wxhook.rootbridge.backup.BackupHookLocal.init(this)
        android.util.Log.e("wxhook:startup", "3")
        registerReceiver(backupFinishReceiver, android.content.IntentFilter(com.nous.wxhook.service.BackupService.ACTION_FINISH), RECEIVER_NOT_EXPORTED)

        // Request notification permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            } catch (_: Exception) {}
        }

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "wxhook 模块"
            textSize = 22f; setTextColor(0xFF6200EE.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })

        // Settings button (gear icon)
        root.addView(Button(this).apply {
            text = "⚙️ 设置"
            textSize = 13f; setTextColor(0xFF6200EE.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.END
            setOnClickListener { startActivity(Intent(this@ModuleActivity, com.nous.wxhook.ui.settings.SettingsActivity::class.java)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END }
        })

        // ── Status card ──
        root.addView(makeCardTitle("📊 状态"))
        val statusCard = makeCard()
        val statusText = TextView(this).apply { textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)); typeface = Typeface.MONOSPACE }

        // 检测按钮
        val checkBtn = Button(this).apply {
            text = "🔍 检测环境"
            setOnClickListener { viewModel.checkEnvironment() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        statusCard.addView(checkBtn)
        statusCard.addView(statusText)
        root.addView(statusCard)

        // ── Backup path ──
        root.addView(makeCardTitle("📁 备份路径"))
        val pathCard = makeCard()
        pathInput = EditText(this).apply {
            setText(BackupManager.BACKUP_DIR)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        pathCard.addView(pathInput)
        val savePathBtn = Button(this).apply {
            text = "保存路径"; textSize = 12f
            setOnClickListener { viewModel.saveBackupPath(pathInput.text.toString().trim()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        pathCard.addView(savePathBtn)
        root.addView(pathCard)

        // ── Remote config card ──
        root.addView(makeCardTitle("☁️ 云同步"))
        val remoteCard = makeCard()
        val remoteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL }
        remoteRow.addView(TextView(this).apply { text = "启用云同步"; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val remoteSwitch = Switch(this).apply {
            isChecked = viewModel.uiState.value.remoteEnabled
            setOnCheckedChangeListener { _, checked -> viewModel.setRemoteEnabled(checked) }
        }
        remoteRow.addView(remoteSwitch)
        remoteCard.addView(remoteRow)

        // Remote path input
        val remotePathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL }
        remotePathRow.addView(TextView(this).apply { text = "远程路径: "; textSize = 13f })
        val remotePathInput = EditText(this).apply {
            setText(viewModel.uiState.value.remotePath)
            textSize = 13f; setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        remotePathRow.addView(remotePathInput)
        val saveRemoteBtn = Button(this).apply {
            text = "保存"; textSize = 12f
            setOnClickListener { viewModel.saveRemotePath(remotePathInput.text.toString().trim()) }
        }
        remotePathRow.addView(saveRemoteBtn)
        remoteCard.addView(remotePathRow)

        // Sync button
        val syncBtn = Button(this).apply {
            text = "立即同步到云盘"; textSize = 12f
            setOnClickListener { viewModel.doSync() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        remoteCard.addView(syncBtn)

        // Rclone config editor
        val cfgLabel = TextView(this).apply {
            text = "rclone 配置 (rclone.conf)"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        remoteCard.addView(cfgLabel)
        val rcloneCfgInput = EditText(this).apply {
            setText(viewModel.uiState.value.rcloneConfText)
            textSize = 10f; typeface = Typeface.MONOSPACE
            minLines = 8; gravity = Gravity.START
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFF0F0F0.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        remoteCard.addView(rcloneCfgInput)
        val saveCfgBtn = Button(this).apply {
            text = "保存配置"; textSize = 12f
            setOnClickListener { viewModel.saveRcloneConf(rcloneCfgInput.text.toString()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        remoteCard.addView(saveCfgBtn)
        root.addView(remoteCard)

        // ── Backup card ──
        root.addView(makeCardTitle("💾 备份"))
        val backupCard = makeCard()

        backupBtn = Button(this).apply {
            text = "全量备份 (DB + 附件)"
            setOnClickListener {
                android.util.Log.e("wxhook:CLICK", "full backup clicked")
                viewModel.startBackup(false)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) }
        }
        backupCard.addView(backupBtn)

        incrBtn = Button(this).apply {
            text = "增量备份 (仅新文件)"
            setOnClickListener {
                android.util.Log.e("wxhook:CLICK", "incremental backup clicked")
                viewModel.startBackup(true)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        backupCard.addView(incrBtn)
        root.addView(backupCard)

        // ── Records card ──
        root.addView(makeCardTitle("📋 备份记录"))
        val recordsCard = makeCard()
        logView = TextView(this).apply {
            textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(0xFF424242.toInt())
        }
        recordsCard.addView(logView)
        root.addView(recordsCard)

        sv.addView(root)
        setContentView(sv)

        // Observe ViewModel state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    statusText.text = state.statusText.ifEmpty { state.statusText }
                    if (state.recordsText.isNotEmpty()) logView.text = state.recordsText
                    if (state.logText.isNotEmpty()) logView.text = state.logText

                    backupBtn.isEnabled = state.backupBtnEnabled
                    incrBtn.isEnabled = state.incrBtnEnabled
                    backupBtn.text = state.backupBtnText
                    incrBtn.text = state.incrBtnText
                }
            }
        }
    }

    private fun makeCardTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(0xFF424242.toInt())
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(6))
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
            val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE); setStroke(1, 0xFFE0E0E0.toInt()) }
            background = bg
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(backupFinishReceiver) }
        super.onDestroy()
    }
}
