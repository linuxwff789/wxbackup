package com.nous.wxhook.sync

import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.root.RootGateways
import org.json.JSONObject
import java.io.File

/**
 * 「启用同步」开关的唯一读取入口。
 *
 * 之前开关写的是 app 私有 `settings_config.json` 的 `remoteEnabled`，而 SyncService /
 * BackupOrchestrator 读的是 `/sdcard/Download/wxhook_backup/remote_config.json` 的 `enabled`
 * —— 两个文件、两个键，且后者在本机根本不存在 → 开关关掉也照样同步（服务侧默认 true）。
 *
 * 读取顺序：settings_config.json `remoteEnabled` → 旧版 remote_config.json `enabled` → 默认 true
 * （默认 true 与旧行为一致，避免升级后静默停止上传）。
 */
object SyncSettings {

    fun isRemoteEnabled(): Boolean {
        val cfg = try {
            JSONObject(File(BackupEnv.filesDirPath, "settings_config.json").readText())
        } catch (_: Exception) {
            JSONObject()
        }
        if (cfg.has("remoteEnabled")) return cfg.optBoolean("remoteEnabled", false)

        // 旧版开关（/sdcard 上，需要 root 读）
        val legacy = try {
            RootGateways.runQuiet("cat \"${BackupEnv.backupDir}/remote_config.json\" 2>/dev/null")
        } catch (_: Exception) {
            ""
        }
        if (legacy.isNotBlank()) {
            val rc = runCatching { JSONObject(legacy) }.getOrNull()
            if (rc != null && rc.has("enabled")) return rc.optBoolean("enabled", true)
        }
        return true
    }
}
