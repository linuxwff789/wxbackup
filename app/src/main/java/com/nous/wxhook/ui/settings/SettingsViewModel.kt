package com.nous.wxhook.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class SettingsUiState(
    val actionTitle: String = "设置",
    val rcloneConfText: String = "",
    val configLoaded: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val filesDir: File = application.filesDir
    private val configFile: File get() = File(filesDir, "settings_config.json")
    private val rcloneCfgFile: File get() = File(filesDir, ".config/rclone/rclone.conf")

    init {
        loadConfig()
    }

    private fun loadConfig() {
        val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        val rcloneConfText = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() else ""
        _uiState.value = _uiState.value.copy(rcloneConfText = rcloneConfText, configLoaded = true)
    }

    fun loadConfigValue(key: String, defaultValue: String = ""): String {
        val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        return cfg.optString(key, defaultValue)
    }

    fun loadConfigBoolean(key: String, defaultValue: Boolean = false): Boolean {
        val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        return cfg.optBoolean(key, defaultValue)
    }

    fun saveConfigValue(key: String, value: String) {
        val o = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        o.put(key, value)
        configFile.writeText(o.toString())
    }

    fun saveConfigBoolean(key: String, value: Boolean) {
        val o = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        o.put(key, value)
        configFile.writeText(o.toString())
        if (key == "zstd") {
            runCatching { BackupHookLocal.setCompressionUseZstd(value) }
        }
    }

    fun saveRcloneConf(text: String) {
        rcloneCfgFile.parentFile?.mkdirs()
        rcloneCfgFile.writeText(text)
        _uiState.value = _uiState.value.copy(rcloneConfText = text, actionTitle = "设置 ✅ 配置已保存")
    }

    fun doSync() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
            val enabled = cfg.optBoolean("sync_enabled", false)
            val remote = cfg.optString("remote_path", "")
            if (!enabled || remote.isBlank()) {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ⚠️ 未启用或未配置路径") }
                return@launch
            }
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ☁️ 同步中...") }
            try {
                val args = mutableListOf(
                    BackupHookLocal.binPath + "/rclone", "sync",
                    "/sdcard/Download/wxhook_backup", remote, "--update"
                )
                if (rcloneCfgFile.exists()) { args.add("--config"); args.add(rcloneCfgFile.absolutePath) }
                val syncResult = RootGateways.run(args.joinToString(" "), 120_000)
                if (!syncResult.isSuccess) {
                    withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ❌ 同步失败(exit=${syncResult.exitCode})") }
                    return@launch
                }
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ✅ 同步完成") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ❌ 同步失败: ${e.message}") }
            }
        }
    }

    fun testRcloneConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ⏳ 测试连接中...") }
            val conf = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() else ""
            val remote = conf.lines().firstOrNull { it.startsWith("[") && it != "[rclone]" }
                ?.removeSurrounding("[", "]") ?: ""
            if (remote.isNotEmpty()) {
                val result = BackupHookLocal.testRemoteConnection(remote, rcloneCfgFile.absolutePath)
                val short = result.lines().first().take(60)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(actionTitle = "设置 $short")
                }
            } else {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ⚠️ 请先保存rclone配置") }
            }
        }
    }

    fun rebuildState() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { BackupHookLocal.rebuildDbState() }
                .getOrElse { "重建失败: ${it.message}" }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(actionTitle = "设置 ✅ 重建完成")
            }
        }
    }

    fun saveS3Config(name: String, provider: String, region: String, endpoint: String, accessKey: String, secretKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.appendLine("[$name]"); sb.appendLine("type = s3"); sb.appendLine("provider = $provider")
                sb.appendLine("access_key_id = $accessKey"); sb.appendLine("secret_access_key = $secretKey")
                sb.appendLine("region = $region")
                if (endpoint.isNotEmpty()) sb.appendLine("endpoint = $endpoint")
                sb.appendLine("acl = private")

                rcloneCfgFile.parentFile?.mkdirs()
                val existing = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() + "\n" else ""
                rcloneCfgFile.writeText(existing + sb.toString())
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ✅ S3 已保存") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ❌ ${e.message}") }
            }
        }
    }

    fun saveWebdavConfig(name: String, url: String, vendor: String, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val obscured = try {
                    RootGateways.run(arrayOf(BackupHookLocal.binPath + "/rclone", "obscure", pass).joinToString(" ")).stdout.trim()
                } catch (_: Exception) { pass }

                val sb = StringBuilder()
                sb.appendLine("[$name]"); sb.appendLine("type = webdav"); sb.appendLine("url = $url")
                sb.appendLine("vendor = $vendor"); sb.appendLine("user = $user"); sb.appendLine("pass = $obscured")

                rcloneCfgFile.parentFile?.mkdirs()
                val existing = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() + "\n" else ""
                rcloneCfgFile.writeText(existing + sb.toString())
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ✅ WebDAV 已保存") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ❌ ${e.message}") }
            }
        }
    }

    fun createDriveConfig(name: String) {
        // TODO: Google Drive OAuth - will implement after confirming build passes
        _uiState.value = _uiState.value.copy(actionTitle = "设置 ⚠️ Google Drive OAuth 待实现")
    }

    fun getS3Endpoint(s3Provider: String, region: String): String {
        return when (s3Provider) {
            "AWS" -> "s3.$region.amazonaws.com"
            "Cloudflare" -> "https://$region.r2.cloudflarestorage.com"
            "Minio" -> "http://127.0.0.1:9000"
            "Alibaba" -> "oss-$region.aliyuncs.com"
            "TencentCOS" -> "cos.$region.myqcloud.com"
            "HuaweiOBS" -> "obs.$region.myhuaweicloud.com"
            "DigitalOcean" -> "$region.digitaloceanspaces.com"
            "Wasabi" -> "s3.$region.wasabisys.com"
            else -> ""
        }
    }
}
