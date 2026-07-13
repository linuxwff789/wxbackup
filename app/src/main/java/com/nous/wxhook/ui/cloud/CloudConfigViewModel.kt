package com.nous.wxhook.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class RemoteInfo(val name: String, val type: String)

data class CloudConfigUiState(
    val statusText: String = "",
    val remotes: List<RemoteInfo> = emptyList(),
    val testResult: String = "",
    val toastMessage: String = "",
)

class CloudConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CloudConfigUiState())
    val uiState: StateFlow<CloudConfigUiState> = _uiState.asStateFlow()

    private val filesDir: File = application.filesDir
    private val rcloneCfgFile: File get() = File(filesDir, ".config/rclone/rclone.conf")

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            val remotes = withContext(Dispatchers.IO) { parseRemotes() }
            _uiState.value = _uiState.value.copy(remotes = remotes)

            val statusText = withContext(Dispatchers.IO) { loadStatus(remotes.size) }
            _uiState.value = _uiState.value.copy(statusText = statusText)
        }
    }

    fun testRemote(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(testResult = "⏳ 测试 $name...")
            val result = BackupHookLocal.testRemoteConnection(name, rcloneCfgFile.absolutePath)
            val first = result.lines().first().take(60)
            _uiState.value = _uiState.value.copy(
                testResult = first,
                toastMessage = result
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = "")
    }

    fun addRemote(provider: String): String {
        return "remote${System.currentTimeMillis() % 10000}"
    }

    fun saveS3Config(name: String, provider: String, region: String, endpoint: String, accessKey: String, secretKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.appendLine("[$name]"); sb.appendLine("type = s3")
                sb.appendLine("provider = $provider"); sb.appendLine("access_key_id = $accessKey")
                sb.appendLine("secret_access_key = $secretKey"); sb.appendLine("region = $region")
                if (endpoint.isNotEmpty()) sb.appendLine("endpoint = $endpoint")
                sb.appendLine("acl = private")

                rcloneCfgFile.parentFile?.mkdirs()
                val existing = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() + "\n" else ""
                rcloneCfgFile.writeText(existing + sb.toString())
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(toastMessage = "✅ $name 已保存")
                }
                loadAll()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(toastMessage = "❌ ${e.message}")
                }
            }
        }
    }

    fun saveWebdavConfig(name: String, url: String, vendor: String, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val obscured = try {
                    RootGateways.gateway.run(arrayOf(BackupHookLocal.binPath + "/rclone", "obscure", pass).joinToString(" ")).stdout.trim()
                } catch (_: Exception) { pass }

                val sb = StringBuilder()
                sb.appendLine("url = $url"); sb.appendLine("vendor = $vendor")
                sb.appendLine("user = $user"); sb.appendLine("pass = $obscured")

                rcloneCfgFile.parentFile?.mkdirs()
                val existing = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() + "\n" else ""
                rcloneCfgFile.writeText(existing + "[$name]\ntype = ??\n$sb")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(toastMessage = "✅ $name 已保存")
                }
                loadAll()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(toastMessage = "❌ ${e.message}")
                }
            }
        }
    }

    fun getS3Endpoint(s3Provider: String, region: String): String {
        return when (s3Provider) {
            "AWS" -> "s3.$region.amazonaws.com"
            "Cloudflare" -> "https://$region.r2.cloudflarestorage.com"
            "Minio" -> "http://127.0.0.1:9000"
            "Alibaba" -> "oss-$region.aliyuncs.com"
            "TencentCOS" -> "cos.$region.myqcloud.com"
            "HuaweiOBS" -> "obs.$region.myhuaweicloud.com"
            else -> ""
        }
    }

    private fun parseRemotes(): List<RemoteInfo> {
        if (!rcloneCfgFile.exists()) return emptyList()
        return try {
            val lines = rcloneCfgFile.readText().lines()
            val result = mutableListOf<RemoteInfo>()
            var currentName = ""
            var currentType = ""
            for (line in lines) {
                val m = Regex("^\\[(.+)]$").find(line.trim())
                if (m != null) {
                    if (currentName.isNotEmpty() && currentType.isNotEmpty())
                        result.add(RemoteInfo(currentName, currentType))
                    currentName = m.groupValues[1]
                    currentType = ""
                } else if (line.trim().startsWith("type = ")) {
                    currentType = line.trim().removePrefix("type = ")
                }
            }
            if (currentName.isNotEmpty() && currentType.isNotEmpty())
                result.add(RemoteInfo(currentName, currentType))
            result
        } catch (_: Exception) { emptyList() }
    }

    private fun loadStatus(remoteCount: Int): String {
        val sb = StringBuilder()
        val cfgRaw = try {
            File("/sdcard/Download/wxhook_backup/remote_config.json").readText()
        } catch (_: Exception) { "{}" }
        val cfg = JSONObject(cfgRaw)
        sb.appendLine("云同步: ${if (cfg.optBoolean("enabled", false)) "✅ 已启用" else "⛔ 未启用"}")
        sb.appendLine("远端路径: ${cfg.optString("remote", "未设置")}")
        sb.appendLine("rclone配置: $remoteCount 个远端")
        return sb.toString()
    }
}
