package com.nous.wxhook.ui.module

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.db.WxHookProvider
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.sync.Syncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ModuleUiState(
    val statusText: String = "",
    val recordsText: String = "",
    val logText: String = "",
    val backupRunning: Boolean = false,
    val backupBtnEnabled: Boolean = true,
    val incrBtnEnabled: Boolean = true,
    val backupBtnText: String = "全量备份 (DB + 附件)",
    val incrBtnText: String = "增量备份 (仅新文件)",
    val backupPath: String = BackupManager.BACKUP_DIR,
    val remoteEnabled: Boolean = false,
    val remotePath: String = "wxhook-backup",
    val statusLoaded: Boolean = false,
    // 手动同步进度
    val syncRunning: Boolean = false,
    /** 0..100；-1 = 不确定（阿里云盘 AAR 没有字节回调） */
    val syncPercent: Int = -1,
    val syncTitle: String = "",
    val syncDetail: String = "",
)

class ModuleViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModuleUiState())
    val uiState: StateFlow<ModuleUiState> = _uiState.asStateFlow()

    private val filesDir: File = application.filesDir
    private val configFile: File get() = File(filesDir, "settings_config.json")

    init {
        // 同步加载远程配置，确保 UI 能读到正确的初始值
        loadRemoteConfig()
        loadInitialData()
    }

    fun loadInitialData() {
        viewModelScope.launch {
            val statusText = withContext(Dispatchers.IO) { getStatusText() }
            _uiState.value = _uiState.value.copy(statusText = statusText, statusLoaded = true)

            val logText = withContext(Dispatchers.IO) { loadLiveLog() }
            _uiState.value = _uiState.value.copy(logText = logText)

            val recordsText = withContext(Dispatchers.IO) { loadRecords() }
            _uiState.value = _uiState.value.copy(recordsText = recordsText)
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val statusText = withContext(Dispatchers.IO) { getStatusText() }
            _uiState.value = _uiState.value.copy(statusText = statusText)
        }
    }

    fun checkEnvironment() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runEnvironmentCheck() }
            _uiState.value = _uiState.value.copy(statusText = result)
        }
    }

    fun saveBackupPath(path: String) {
        if (path.isNotEmpty()) {
            File(path).mkdirs()
            _uiState.value = _uiState.value.copy(backupPath = path)
            appendLog("📁 路径已保存: $path")
        }
    }

    fun setRemoteEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(remoteEnabled = enabled)
        saveRemoteConfig(enabled = enabled)
        appendLog(if (enabled) "☁️ 云同步已开启" else "☁️ 云同步已关闭")
    }

    fun saveRemotePath(path: String) {
        _uiState.value = _uiState.value.copy(remotePath = path)
        saveRemoteConfig(remote = path)
        appendLog("☁️ 远程路径已保存: $path")
    }

    fun startBackup(incremental: Boolean) {
        if (_uiState.value.backupRunning) {
            appendLog("⏳ 正在备份中...")
            return
        }

        _uiState.value = _uiState.value.copy(
            backupRunning = true,
            backupBtnEnabled = false,
            incrBtnEnabled = false,
            backupBtnText = "备份中...",
            incrBtnText = "备份中..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val msg = if (incremental) "已启动前台服务: 增量备份" else "已启动前台服务: 全量备份"
            withContext(Dispatchers.Main) { appendLog(msg) }
            try {
                com.nous.wxhook.service.BackupService.start(context, incremental)
            } catch (_: Exception) {}
        }
    }

    fun onBackupFinished(ok: Boolean, message: String) {
        val prefix = if (ok) "✅ " else "❌ "
        _uiState.value = _uiState.value.copy(
            backupRunning = false,
            backupBtnEnabled = true,
            incrBtnEnabled = true,
            backupBtnText = "全量备份 (DB + 附件)",
            incrBtnText = "增量备份 (仅新文件)"
        )
        appendLog("$prefix$message")
    }

    fun doSync() {
        if (_uiState.value.syncRunning) {
            appendLog("⏳ 正在同步中...")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val remote = _uiState.value.remotePath
            var ok = false
            publishSync(running = true, percent = -1, title = "准备同步...", detail = "")
            try {
                appendLog("☁️ 同步到 $remote...")

                val config = Syncer.loadConfig()
                if (!config.isValid) {
                    appendLog("☁️ WebDAV未配置")
                    return@launch
                }
                // Override remotePath from module UI setting
                val effectiveConfig = config.copy(remotePath = remote)

                val result = Syncer.sync(effectiveConfig) { p -> onSyncProgress(p) }
                ok = result.success
                appendLog("☁️ ${result.message}")
            } catch (e: Exception) {
                appendLog("☁️ 同步失败: ${e.message}")
            } finally {
                publishSync(
                    running = false,
                    percent = if (ok) 100 else -1,
                    title = if (ok) "同步完成" else "同步结束",
                    detail = "",
                )
            }
        }
    }

    /**
     * 同步进度：`tick`（每秒刷新的计时/字节进度）只更新进度区，不写日志 ——
     * 大包上传期间每秒一条日志会把日志框刷满。阶段消息（连接/扫描/上传开始/结果）照旧写日志。
     */
    private fun onSyncProgress(p: Syncer.Progress) {
        val percent = when {
            p.bytesTotal > 0 && p.bytesSent > 0 -> ((p.bytesSent * 100) / p.bytesTotal).toInt().coerceIn(0, 100)
            p.total > 0 && p.current > 0 -> (((p.current - 1) * 100) / p.total).coerceIn(0, 100)
            else -> -1
        }
        val detail = buildString {
            if (p.total > 0) append("第 ${p.current.coerceAtLeast(1)}/${p.total} 个包")
            if (p.bytesTotal > 0) {
                if (isNotEmpty()) append(" · ")
                append("本包 ${com.nous.wxhook.backup.BackupManifest.formatSize(p.bytesTotal)}")
            }
        }
        publishSync(running = true, percent = percent, title = p.message, detail = detail)
        if (!p.tick) appendLog(p.message)
    }

    private fun publishSync(running: Boolean, percent: Int, title: String, detail: String) {
        _uiState.value = _uiState.value.copy(
            syncRunning = running,
            syncPercent = percent,
            syncTitle = title,
            syncDetail = detail,
        )
    }

    fun rebuildState() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { BackupHookLocal.rebuildDbState() }
                .getOrElse { e -> "重建失败: " + (e.message ?: "") }
            withContext(Dispatchers.Main) {
                appendLog(result)
                val app = getApplication<Application>()
                android.widget.Toast.makeText(app, result, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startRestore() {
        if (_uiState.value.backupRunning) {
            appendLog("⏳ 正在备份/恢复中...")
            return
        }
        _uiState.value = _uiState.value.copy(
            backupRunning = true,
            backupBtnEnabled = false,
            incrBtnEnabled = false
        )
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.Main) { appendLog("🔄 启动前台服务：从备份恢复...") }
            try {
                com.nous.wxhook.service.BackupService.startRestore(ctx)
            } catch (_: Exception) {}
        }
    }

    fun clearLog() {
        _uiState.value = _uiState.value.copy(logText = "")
        appendLog("📋 日志已清除")
    }

    fun refreshRecords() {
        viewModelScope.launch {
            val recordsText = withContext(Dispatchers.IO) { loadRecords() }
            _uiState.value = _uiState.value.copy(recordsText = recordsText)
        }
    }

    private fun getStatusText(): String {
        try {
            val sb = StringBuilder()
            try {
                val app = getApplication<Application>()
                val cursor = app.contentResolver.query(
                    android.net.Uri.parse(WxHookProvider.KEY_URI),
                    null, null, null, null
                )
                val key = if (cursor != null && cursor.moveToFirst()) {
                    val hex = cursor.getString(cursor.getColumnIndexOrThrow("key"))
                    cursor.close()
                    if (hex != null) "key=${hex}" else null
                } else null
                if (key != null) {
                    sb.appendLine("  密钥: $key")
                } else {
                    sb.appendLine("  密钥: 未捕获")
                }
            } catch (_: Exception) { sb.appendLine("  密钥: 读取失败") }
            val dbFile = File("/sdcard/Download/EnMicroMsg.db")
            if (dbFile.exists()) {
                sb.appendLine("  数据库: ${BackupManager.formatSize(dbFile.length())}")
            } else {
                sb.appendLine("  数据库: 未复制")
            }
            val info = BackupManager.getBackupInfo()
            sb.appendLine("  备份目录: ${info.optString("backupDir", "无")}")
            sb.appendLine("  备份文件: ${info.optInt("fileCount", 0)}个")
            sb.appendLine("  最后备份: ${BackupManager.formatTime(info.optLong("lastBackupTime", 0))}")
            return sb.toString()
        } catch (e: Exception) { return "状态加载失败: ${e.message}" }
    }

    private fun runEnvironmentCheck(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 环境检测 ===")

        // 1. Root 检测
        try {
            val output = RootGateways.runQuiet("id")
            if (output.contains("uid=0")) {
                sb.appendLine("✅ Root: 正常 (${output})")
            } else {
                sb.appendLine("❌ Root: 失败 (${output})")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ Root: 异常 (${e.message})")
        }

        // 2. Xposed 模块检测
        try {
            val xpPkg = "com.nous.wxhook.xposed"
            val xpOutput = RootGateways.runQuiet("pm list packages | grep $xpPkg")
            if (xpOutput.contains(xpPkg)) {
                sb.appendLine("✅ Xposed 模块: 已安装")
            } else {
                sb.appendLine("❌ Xposed 模块: 未安装")
            }

            val lsOutput = RootGateways.runQuiet("ls /data/adb/lspd/modules/")
            if (lsOutput.contains("wxhook")) {
                sb.appendLine("✅ LSPosed: 模块已注册")
            } else {
                sb.appendLine("⚠️ LSPosed: 模块未注册")
            }

            val logOutput = RootGateways.runQuiet("logcat -d | grep 'wxhook:Hook' | tail -1")
            if (logOutput.isNotEmpty()) {
                sb.appendLine("✅ Xposed Hook: 已加载")
                sb.appendLine("   $logOutput")
            } else {
                sb.appendLine("⚠️ Xposed Hook: 未检测到日志")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ Xposed: 检测失败")
        }

        // 3. 微信进程检测
        try {
            val pid = RootGateways.runQuiet("pidof com.tencent.mm")
            if (pid.isNotEmpty()) {
                sb.appendLine("✅ 微信: 运行中 (pid=$pid)")
            } else {
                sb.appendLine("❌ 微信: 未运行")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ 微信: 检测失败")
        }

        // 4. 文件访问检测
        try {
            val dbFile = File("/sdcard/Download/EnMicroMsg.db")
            if (dbFile.exists()) {
                sb.appendLine("✅ 数据库: 存在 (${BackupManager.formatSize(dbFile.length())})")
            } else {
                sb.appendLine("⚠️ 数据库: 不存在")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ 数据库: 检测失败")
        }

        // 5. 备份目录检测
        val backupDir = File(_uiState.value.backupPath)
        sb.appendLine("${if (backupDir.exists()) "✅" else "⚠️"} 备份目录: ${backupDir.absolutePath}")

        // 6. 密钥检测
        try {
            val app = getApplication<Application>()
            val cursor = app.contentResolver.query(
                android.net.Uri.parse(WxHookProvider.KEY_URI),
                null, null, null, null
            )
            val key = if (cursor != null && cursor.moveToFirst()) {
                val hex = cursor.getString(cursor.getColumnIndexOrThrow("key"))
                cursor.close()
                if (hex != null) "key=${hex}" else null
            } else null
            if (key != null) {
                sb.appendLine("✅ 密钥: $key")
            } else {
                sb.appendLine("⚠️ 密钥: 未捕获")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ 密钥: 读取失败")
        }

        return sb.toString()
    }

    private fun loadRecords(): String {
        try {
            val records = BackupManager.getRecords()
            if (records.isEmpty()) return "暂无备份记录"
            val sb = StringBuilder()
            records.take(10).forEach { r ->
                val time = BackupManager.formatTime(r.time)
                val size = BackupManager.formatSize(r.totalSize)
                val type = if (r.type == "full") "全量" else "增量"
                sb.appendLine("[$time] $type | $size | ${r.fileCount}文件")
                sb.appendLine("  ${r.message}")
            }
            return sb.toString()
        } catch (e: Exception) {
            return "记录加载失败: ${e.message}"
        }
    }

    private fun loadLiveLog(): String {
        try {
            return RootGateways.runQuiet("tail -50 /sdcard/Download/wxhook_backup/backup_live.log 2>/dev/null")
        } catch (_: Exception) {}
        return ""
    }

    private fun loadRemoteConfig() {
        try {
            val cfg = JSONObject(configFile.readText())
            // 兼容旧版外部存储路径
            val legacyFile = File("/sdcard/Download/wxhook_backup/remote_config.json")
            val legacy = if (!cfg.has("remoteEnabled") && !cfg.has("remote") && legacyFile.exists()) {
                runCatching { JSONObject(legacyFile.readText()) }.getOrNull()
            } else null
            _uiState.value = _uiState.value.copy(
                // 缺键时与同步服务同源（SyncSettings 默认 true），否则会出现
                // 「UI 显示关闭、后台照旧上传」这种自相矛盾的状态
                remoteEnabled = when {
                    cfg.has("remoteEnabled") -> cfg.optBoolean("remoteEnabled", false)
                    legacy != null -> legacy.optBoolean("enabled", true)
                    else -> com.nous.wxhook.sync.SyncSettings.isRemoteEnabled()
                },
                remotePath = cfg.optString("remote", "").takeIf { it.isNotBlank() }
                    ?: legacy?.optString("remote", "wxhook-backup")
                    ?: "wxhook-backup",
            )
        } catch (_: Exception) {}
    }

    private fun saveRemoteConfig(enabled: Boolean = _uiState.value.remoteEnabled, remote: String = _uiState.value.remotePath) {
        try {
            val cfg = if (configFile.exists()) JSONObject(configFile.readText()) else JSONObject()
            cfg.put("remoteEnabled", enabled)
            cfg.put("remote", remote)
            configFile.writeText(cfg.toString())
        } catch (_: Exception) {}
    }

    companion object {
        private const val MAX_LOG_LINES = 30
    }

    fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        val currentLog = _uiState.value.logText
        // 只保留最多 MAX_LOG_LINES 行
        val lines = currentLog.lines()
        val newLog = if (lines.size >= MAX_LOG_LINES) {
            lines.dropLast(1).joinToString("\n").let { "$line\n$it" }
        } else {
            "$line\n$currentLog"
        }
        _uiState.value = _uiState.value.copy(logText = newLog)

        try {
            val tmp = File(filesDir, "backup_live.log")
            tmp.appendText("$line\n")
            RootGateways.run(
                "mkdir -p /sdcard/Download/wxhook_backup && cat \"${tmp.absolutePath}\" >> /sdcard/Download/wxhook_backup/backup_live.log && chmod 644 /sdcard/Download/wxhook_backup/backup_live.log"
            )
            tmp.writeText("")
        } catch (_: Exception) {}
    }
}
