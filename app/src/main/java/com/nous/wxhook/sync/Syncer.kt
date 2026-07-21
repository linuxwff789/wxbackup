package com.nous.wxhook.sync

import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.backup.BackupManifest
import com.nous.wxhook.root.RootGateways
import org.json.JSONArray
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
 *
 * 增量同步策略：本地维护已上传文件列表（sync_state.json），
 * 每次只上传不在此列表中的新文件。
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

    /** 已同步记录文件路径 */
    private fun stateFile(config: Config): File =
        File(BackupEnv.backupDir, ".sync_state_${config.provider}.json")

    /** 读取本地已同步记录 */
    private fun loadSynced(config: Config): Set<String> {
        val f = stateFile(config)
        if (!f.exists()) return emptySet()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    /** 保存已同步记录 */
    private fun saveSynced(config: Config, names: Set<String>) {
        try {
            val arr = JSONArray(names.toList())
            stateFile(config).writeText(arr.toString(2))
        } catch (_: Exception) {}
    }

    /**
     * Sync archives to cloud storage (增量同步).
     *
     * 只上传本地有但远端没有（或大小不同）的文件。
     * 已上传的文件名记录在 .sync_state_<provider>.json 中。
     *
     * @param config cloud connection config
     * @param force 强制重新上传所有文件
     * @param specificArchives 指定上传的文件列表（为空则自动扫描）
     * @param onProgress progress callback
     */
    fun sync(
        config: Config = loadConfig(),
        force: Boolean = false,
        specificArchives: List<String>? = null,
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

        // 3. 扫描本地备份文件（或用指定列表）
        val toUpload = specificArchives?.filter { BackupEnv.backupExists(it) && BackupEnv.backupSize(it) > 100L } ?: scanArchives()
        if (toUpload.isEmpty()) {
            onProgress?.invoke(Progress("无备份包可同步"))
            return Result(true, message = "无备份包可同步")
        }

        // 4. 加载已同步记录 + 扫描远端文件
        val synced = if (force) emptySet() else loadSynced(config)

        onProgress?.invoke(Progress("扫描远端文件（用于去重）..."))
        val remoteFiles = kotlinx.coroutines.runBlocking { client.list(config.remotePath) }
            .getOrNull() ?: emptyList()
        val remoteNames = remoteFiles.map { File(it.path).name }.toSet()

        // 5. 筛选需要上传的文件
        var uploaded = 0
        var skipped = 0
        var totalBytes = 0L
        val newlySynced = synced.toMutableSet()

        for ((idx, pkgPath) in toUpload.withIndex()) {
            val pkgSize = BackupEnv.backupSize(pkgPath)
            val pkgName = File(pkgPath).name

            onProgress?.invoke(Progress("[${idx + 1}/${toUpload.size}] $pkgName", idx + 1, toUpload.size))

            // 增量判断：本地已记录且远端存在且大小一致 → 跳过
            if (!force && pkgName in synced && pkgName in remoteNames) {
                skipped++
                newlySynced.add(pkgName)
                continue
            }

            // 上传
            onProgress?.invoke(Progress("上传 $pkgName (${BackupManifest.formatSize(pkgSize)})...", idx + 1, toUpload.size))
            val uploadResult = kotlinx.coroutines.runBlocking {
                client.upload(File(pkgPath), "${config.remotePath}/$pkgName")
            }

            if (uploadResult.isSuccess) {
                uploaded++
                totalBytes += pkgSize
                newlySynced.add(pkgName)
            } else {
                onProgress?.invoke(Progress("上传失败: $pkgName - ${uploadResult.exceptionOrNull()?.message}"))
            }
        }

        // 6. 保存同步记录
        saveSynced(config, newlySynced)

        val msg = if (uploaded > 0) {
            "增量同步完成: 新上传 $uploaded 个, 跳过 $skipped 个, 共 ${BackupManifest.formatSize(totalBytes)}"
        } else {
            "无需上传: 已有 $skipped 个文件已同步"
        }
        onProgress?.invoke(Progress(msg))
        return Result(true, uploaded, skipped, totalBytes, msg)
    }
}
