package com.nous.wxhook.sync

import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.backup.BackupManifest
import com.nous.wxhook.root.RootGateways
import org.json.JSONObject
import java.io.File

/**
 * Shared sync logic — used by BackupOrchestrator (backup-time sync)
 * and SyncService (manual/timed sync). Only syncs archive files
 * (.tar.zst), not loose metadata files.
 */
object Syncer {

    data class Config(
        val url: String = "",
        val user: String = "",
        val pass: String = "",
        val remotePath: String = "wxhook-backup",
    ) {
        val isValid: Boolean get() = url.isNotBlank() && user.isNotBlank()
    }

    data class Progress(
        val message: String,
        val current: Int = 0,
        val total: Int = 0,
    )

    data class Result(
        val success: Boolean,
        val uploaded: Int = 0,
        val skipped: Int = 0,
        val totalBytes: Long = 0,
        val message: String = "",
    )

    /** Load WebDAV config from filesDir + fallback to legacy remote_config.json for remote path. */
    fun loadConfig(): Config {
        val cfg = try {
            JSONObject(File(BackupEnv.filesDirPath, "settings_config.json").readText())
        } catch (_: Exception) { JSONObject() }
        // Legacy fallback: remote_config.json may have "remote" path
        val remoteCfg = try {
            val raw = RootGateways.runQuiet("cat \"${BackupEnv.backupDir}/remote_config.json\" 2>/dev/null")
            if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
        } catch (_: Exception) { JSONObject() }
        val remotePath = cfg.optString("remote_path", "").takeIf { it.isNotBlank() }
            ?: remoteCfg.optString("remote", "wxhook-backup")
        return Config(
            url = cfg.optString("webdav_url", ""),
            user = cfg.optString("webdav_user", ""),
            pass = cfg.optString("webdav_pass", ""),
            remotePath = remotePath,
        )
    }

    /**
     * Scan backupDataDir for all files (archives + metadata), excluding tmp.
     * Returns list of absolute paths, archives first then sorted.
     */
    fun scanArchives(): List<String> {
        val all = RootGateways.runQuiet(
            "find ${BackupEnv.backupDataDir} -maxdepth 2 -type f ! -path '*/tmp/*' 2>/dev/null"
        ).lines().filter { it.isNotBlank() }
        // Archives first (full > incr), then other files
        val archives = all.filter { it.endsWith(".tar.zst") }
            .sortedByDescending { File(it).lastModified() }
        val others = all.filter { !it.endsWith(".tar.zst") }.sorted()
        return archives + others
    }

    /**
     * Sync archives to WebDAV.
     *
     * @param config WebDAV connection config
     * @param archives specific archives to upload (empty = auto-scan)
     * @param onProgress progress callback (runs on calling thread)
     * @return sync result
     */
    fun sync(
        config: Config = loadConfig(),
        archives: List<String> = emptyList(),
        onProgress: ((Progress) -> Unit)? = null,
    ): Result {
        if (!config.isValid) {
            onProgress?.invoke(Progress("WebDAV未配置"))
            return Result(false, message = "WebDAV未配置")
        }

        val client = WebDavClient(config.url, config.user, config.pass)

        // 1. Test connection
        onProgress?.invoke(Progress("连接 WebDAV..."))
        val testResult = kotlinx.coroutines.runBlocking { client.testConnection() }
        if (testResult.isFailure) {
            val msg = "WebDAV连接失败: ${testResult.exceptionOrNull()?.message}"
            onProgress?.invoke(Progress(msg))
            return Result(false, message = msg)
        }

        // 2. Ensure remote directory (skip if root)
        if (config.remotePath.isNotBlank()) {
            onProgress?.invoke(Progress("确保远端目录..."))
            kotlinx.coroutines.runBlocking { client.ensureDirectory(config.remotePath) }
        }

        // 3. Determine what to upload
        val toUpload = if (archives.isNotEmpty()) {
            archives.filter { BackupEnv.backupExists(it) && BackupEnv.backupSize(it) > 100L }
        } else {
            scanArchives()
        }

        if (toUpload.isEmpty()) {
            onProgress?.invoke(Progress("无备份包可同步"))
            return Result(true, message = "无备份包可同步")
        }

        // 4. List remote files for dedup
        onProgress?.invoke(Progress("扫描远端文件..."))
        val remoteFiles = kotlinx.coroutines.runBlocking { client.list(config.remotePath) }
            .getOrNull() ?: emptyList()

        var uploaded = 0
        var skipped = 0
        var totalBytes = 0L

        for ((idx, pkgPath) in toUpload.withIndex()) {
            val pkgSize = BackupEnv.backupSize(pkgPath)
            val pkgName = File(pkgPath).name

            val progressMsg = "[${idx + 1}/${toUpload.size}] $pkgName"
            onProgress?.invoke(Progress(progressMsg, idx + 1, toUpload.size))

            // Skip if remote has same file with same size
            val remoteMatch = remoteFiles.find { it.path.endsWith(pkgName) }
            if (remoteMatch != null && remoteMatch.size == pkgSize) {
                skipped++
                continue
            }

            // Upload
            onProgress?.invoke(Progress("上传 $pkgName (${BackupManifest.formatSize(pkgSize)})...", idx + 1, toUpload.size))
            val remoteUploadPath = if (config.remotePath.isNotBlank()) "${config.remotePath}/$pkgName" else pkgName
            val uploadResult = kotlinx.coroutines.runBlocking {
                client.upload(File(pkgPath), remoteUploadPath)
            }

            if (uploadResult.isSuccess) {
                uploaded++
                totalBytes += pkgSize
            } else {
                onProgress?.invoke(Progress("上传失败: $pkgName - ${uploadResult.exceptionOrNull()?.message}"))
            }
        }

        val msg = if (uploaded > 0) {
            "同步完成: $uploaded 个上传, $skipped 个跳过, ${BackupManifest.formatSize(totalBytes)}"
        } else {
            "同步完成: $skipped 个跳过，无新文件"
        }
        onProgress?.invoke(Progress(msg))
        return Result(true, uploaded, skipped, totalBytes, msg)
    }
}
