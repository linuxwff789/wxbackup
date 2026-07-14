package com.nous.wxhook.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nous.wxhook.root.RootGateways
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
    private val configFile: File get() = File(filesDir, "settings_config.json")

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            val statusText = withContext(Dispatchers.IO) { loadStatus() }
            _uiState.value = _uiState.value.copy(statusText = statusText)
        }
    }

    fun testRemote(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(testResult = "⏳ 测试 $name...")
            try {
                val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
                val url = cfg.optString("webdav_url", "")
                val user = cfg.optString("webdav_user", "")
                val pass = cfg.optString("webdav_pass", "")
                if (url.isBlank() || user.isBlank()) {
                    _uiState.value = _uiState.value.copy(testResult = "⚠️ WebDAV未配置")
                    return@launch
                }
                val client = com.nous.wxhook.sync.WebDavClient(url, user, pass)
                val result = client.testConnection()
                val msg = if (result.isSuccess) "✅ 连接成功" else "❌ ${result.exceptionOrNull()?.message}"
                _uiState.value = _uiState.value.copy(
                    testResult = msg,
                    toastMessage = msg
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    testResult = "❌ ${e.message}",
                    toastMessage = "❌ ${e.message}"
                )
            }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = "")
    }

    fun addRemote(provider: String): String {
        return "remote${System.currentTimeMillis() % 10000}"
    }

    fun saveWebdavConfig(name: String, url: String, vendor: String, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val o = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
                o.put("webdav_url", url)
                o.put("webdav_user", user)
                o.put("webdav_pass", pass)
                configFile.writeText(o.toString())
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

    private fun loadStatus(): String {
        val sb = StringBuilder()
        val cfgRaw = try {
            File("/sdcard/Download/wxhook_backup/remote_config.json").readText()
        } catch (_: Exception) { "{}" }
        val cfg = JSONObject(cfgRaw)
        sb.appendLine("云同步: ${if (cfg.optBoolean("enabled", false)) "✅ 已启用" else "⛔ 未启用"}")

        // Load WebDAV config
        val settingsCfg = try {
            JSONObject(configFile.readText())
        } catch (_: Exception) { JSONObject() }
        val webdavUrl = settingsCfg.optString("webdav_url", "")
        sb.appendLine("WebDAV: ${if (webdavUrl.isNotBlank()) "✅ 已配置 ($webdavUrl)" else "⚠️ 未配置"}")
        return sb.toString()
    }
}
