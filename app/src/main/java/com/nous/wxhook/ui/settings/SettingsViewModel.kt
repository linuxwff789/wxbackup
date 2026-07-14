package com.nous.wxhook.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val configLoaded: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val filesDir: File = application.filesDir
    private val configFile: File get() = File(filesDir, "settings_config.json")

    init {
        loadConfig()
    }

    private fun loadConfig() {
        _uiState.value = _uiState.value.copy(configLoaded = true)
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
    }

    fun doSync() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
            val enabled = cfg.optBoolean("sync_enabled", false)
            val webdavUrl = cfg.optString("webdav_url", "")
            val webdavUser = cfg.optString("webdav_user", "")
            if (!enabled || webdavUrl.isBlank() || webdavUser.isBlank()) {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ⚠️ WebDAV未配置") }
                return@launch
            }
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ☁️ 同步中...") }
            try {
                com.nous.wxhook.service.SyncService.start(getApplication())
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ✅ 同步已启动") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ❌ 同步失败: ${e.message}") }
            }
        }
    }

    fun testWebDavConnection(url: String, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            android.util.Log.d("wxhook:WebDAV", "testWebDavConnection start")
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "设置 ⏳ WebDAV测试中...") }
            try {
                val client = com.nous.wxhook.sync.WebDavClient(url, user, pass)
                android.util.Log.d("wxhook:WebDAV", "calling testConnection...")
                val result = client.testConnection()
                android.util.Log.d("wxhook:WebDAV", "result: isSuccess=${result.isSuccess}, msg=${result.exceptionOrNull()?.message}")
                if (result.isSuccess) {
                    // List remote files
                    val listResult = client.list("")
                    val files = listResult.getOrNull() ?: emptyList()
                    val fileNames = files.map { it.path.trimEnd('/').substringAfterLast('/') }.filter { it.isNotEmpty() }.take(10)
                    val msg = if (fileNames.isEmpty()) "✅ 连接成功（远端为空）" else "✅ 连接成功\n远端文件: ${fileNames.joinToString(", ")}"
                    android.util.Log.d("wxhook:WebDAV", "listing: ${files.size} files")
                    withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = msg) }
                } else {
                    val msg = "❌ WebDAV: ${result.exceptionOrNull()?.message}"
                    withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = msg) }
                }
            } catch (e: Exception) {
                android.util.Log.e("wxhook:WebDAV", "exception: ${e.message}", e)
                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(actionTitle = "❌ WebDAV: ${e.message}") }
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

    fun saveWebdavConfig(url: String, user: String, pass: String) {
        val o = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        o.put("webdav_url", url)
        o.put("webdav_user", user)
        o.put("webdav_pass", pass)
        configFile.writeText(o.toString())
        _uiState.value = _uiState.value.copy(actionTitle = "设置 ✅ WebDAV 已保存")
    }
}
