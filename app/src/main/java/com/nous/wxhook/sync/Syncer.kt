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
 *
 * Supports multiple cloud providers:
 * - "webdav":       WebDAV (via WebDavClient or OpenListDriver)
 * - "aliyundrive":  阿里云盘 Open (via OpenListDriver)
 */
object Syncer {

    data class Config(
        val provider: String = "webdav",
        // WebDAV fields
        val url: String = "",
        val user: String = "",
        val pass: String = "",
        // Common
        val remotePath: String = "wxhook-backup",
        // AliyunDrive fields
        val aliyunRefreshToken: String = "",
        val aliyunApiUrl: String = "https://api.oplist.org/alicloud/renewapi",
        val aliyunRootFolder: String = "root",
    ) {
        val isValid: Boolean get() = when (provider) {
            "webdav" -> url.isNotBlank() && user.isNotBlank()
            "aliyundrive" -> aliyunRefreshToken.isNotBlank()
            else -> false
        }
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

    /** Load config from settings_config.json + legacy remote_config.json. */
    fun loadConfig(): Config {
        val cfg = try {
            JSONObject(File(BackupEnv.filesDirPath, "settings_config.json").readText())
        } catch (_: Exception) { JSONObject() }

        // Detect provider type
        val provider = when {
            cfg.optString("aliyundrive_refresh_token", "").isNotBlank() -> "aliyundrive"
            cfg.optString("webdav_url", "").isNotBlank() -> "webdav"
            else -> "webdav"
        }

        // Legacy fallback: remote_config.json may have "remote" path
        val remoteCfg = try {
            val raw = RootGateways.runQuiet("cat \"${BackupEnv.backupDir}/remote_config.json\" 2>/dev/null")
            if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
        } catch (_: Exception) { JSONObject() }
        val remotePath = cfg.optString("remote_path", "").takeIf { it.isNotBlank() }
            ?: remoteCfg.optString("remote", "wxhook-backup")

        return Config(
            provider = provider,
            url = cfg.optString("webdav_url", ""),
            user = cfg.optString("webdav_user", ""),
            pass = cfg.optString("webdav_pass", ""),
            remotePath = remotePath,
            aliyunRefreshToken = cfg.optString("aliyundrive_refresh_token", ""),
            aliyunApiUrl = cfg.optString("aliyundrive_api_url", "https://api.oplist.org/alicloud/renewapi"),
            aliyunRootFolder = cfg.optString("aliyundrive_root_folder", "root"),
        )
    }

    /** Create a CloudClient for the given config. */
    fun createClient(config: Config): CloudClient? {
        return when (config.provider) {
            "aliyundrive" -> {
                val configJson = OpenListCloudClient.aliyunConfig(
                    refreshToken = config.aliyunRefreshToken,
                    apiUrl = config.aliyunApiUrl,
                    rootFolderId = config.aliyunRootFolder,
                )
                try {
                    OpenListCloudClient("AliyundriveOpen", configJson)
                } catch (e: Exception) {
                    null
                }
            }
            else -> WebDavClient(config.url, config.user, config.pass)
        }
    }

    /**
     * Scan backupDataDir for all files (archives + metadata), excluding tmp.
     * Returns list of absolute paths, archives first then sorted.
     */
    fun scanArchives(): List<String> {
        val all = RootGateways.runQuiet(
            "find ${BackupEnv.backupDataDir} -maxdepth 2 -type f ! -path '*/tmp/*' 2>/dev/null"
        ).lines().filter { it.isNotBlank() }
        val archives = all.filter { it.endsWith(".tar.zst") }
            .sortedByDescending { File(it).lastModified() }
        val others = all.filter { !it.endsWith(".tar.zst") }.sorted()
        return archives + others
    }

    /**
     * Sync archives to cloud storage.
     *
     * @param config cloud connection config
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
            val label = when (config.provider) {
                "aliyundrive" -> "阿里云盘"
                else -> "WebDAV"
            }
            onProgress?.invoke(Progress("$label 未配置"))
            return Result(false, message = "$label 未配置")
        }

        val client = createClient(config) ?: run {
            onProgress?.invoke(Progress("创建云存储客户端失败"))
            return Result(false, message = "创建云存储客户端失败")
        }

        val providerLabel = when (config.provider) {
            "aliyundrive" -> "阿里云盘"
            else -> "WebDAV"
        }

        // 1. Test connection
        onProgress?.invoke(Progress("连接 $providerLabel..."))
        val testResult = kotlinx.coroutines.runBlocking { client.testConnection() }
        if (testResult.isFailure) {
            val msg = "$providerLabel 连接失败: ${testResult.exceptionOrNull()?.message}"
            onProgress?.invoke(Progress(msg))
            return Result(false, message = msg)
        }

        // 2. Ensure remote directory
        onProgress?.invoke(Progress("确保远端目录..."))
        kotlinx.coroutines.runBlocking { client.ensureDirectory(config.remotePath) }

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
            val uploadResult = kotlinx.coroutines.runBlocking {
                client.upload(File(pkgPath), "${config.remotePath}/$pkgName")
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
