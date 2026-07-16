package com.nous.wxhook.ui.module

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
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
)

class ModuleViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModuleUiState())
    val uiState: StateFlow<ModuleUiState> = _uiState.asStateFlow()

    private val filesDir: File = application.filesDir
    private val configFile: File get() = File(filesDir, "settings_config.json")

    init {
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

            loadRemoteConfig()
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
        viewModelScope.launch(Dispatchers.IO) {
            val remote = _uiState.value.remotePath
            try {
                withContext(Dispatchers.Main) { appendLog("☁️ 同步到 $remote...") }

                // Read WebDAV config from settings_config.json
                val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
                val webdavUrl = cfg.optString("webdav_url", "")
                val webdavUser = cfg.optString("webdav_user", "")
                val webdavPass = cfg.optString("webdav_pass", "")

                if (webdavUrl.isBlank() || webdavUser.isBlank()) {
                    withContext(Dispatchers.Main) { appendLog("☁️ WebDAV未配置") }
                    return@launch
                }

                val client = com.nous.wxhook.sync.WebDavClient(webdavUrl, webdavUser, webdavPass)
                val testResult = client.testConnection()
                if (testResult.isFailure) {
                    withContext(Dispatchers.Main) { appendLog("☁️ 连接失败: ${testResult.exceptionOrNull()?.message}") }
                    return@launch
                }

                client.ensureDirectory(remote)

                // Incremental: list remote files and only upload new/changed
                val remoteFiles = client.list(remote).getOrNull() ?: emptyList()
                val backupDir = File("/sdcard/Download/wxhook_backup")
                val localFiles = backupDir.listFiles() ?: emptyArray()
                var uploaded = 0
                var skipped = 0

                for (local in localFiles) {
                    if (!local.isFile) continue
                    val remoteMatch = remoteFiles.find { it.path.endsWith(local.name) }
                    if (remoteMatch == null || remoteMatch.size != local.length()) {
                        val uploadResult = client.upload(local, "$remote/${local.name}")
                        if (uploadResult.isSuccess) {
                            uploaded++
                        } else {
                            withContext(Dispatchers.Main) { appendLog("☁️ 上传失败: ${local.name}") }
                        }
                    } else {
                        skipped++
                    }
                }

                withContext(Dispatchers.Main) { appendLog("☁️ 同步完成: 上传${uploaded}个, 跳过${skipped}个") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("☁️ 同步失败: ${e.message}") }
            }
        }
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
                val keyFile = File("/data/local/tmp/.wechat_key")
                if (keyFile.exists()) {
                    val key = keyFile.readText().lines().find { it.startsWith("key=") } ?: "未知"
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
            val keyFile = File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val key = keyFile.readText().lines().find { it.startsWith("key=") } ?: "未知"
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
            val txt = RootGateways.runQuiet("cat /sdcard/Download/wxhook_backup/remote_config.json 2>/dev/null").trim()
            val cfg = JSONObject(if (txt.isNotEmpty()) txt else "{}")
            _uiState.value = _uiState.value.copy(
                remoteEnabled = cfg.optBoolean("enabled", false),
                remotePath = cfg.optString("remote", "wxhook-backup")
            )
        } catch (_: Exception) {}
    }

    private fun saveRemoteConfig(enabled: Boolean = _uiState.value.remoteEnabled, remote: String = _uiState.value.remotePath) {
        try {
            val f = File("/sdcard/Download/wxhook_backup/remote_config.json")
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            o.put("enabled", enabled)
            o.put("remote", remote)
            f.writeText(o.toString())
        } catch (_: Exception) {}
    }

    fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        val currentLog = _uiState.value.logText
        _uiState.value = _uiState.value.copy(logText = "$line\n$currentLog")

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
