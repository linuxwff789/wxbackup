package com.nous.wxhook.rootbridge.backup

import com.nous.wxhook.backup.ArchiveService
import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.backup.BackupManifest
import com.nous.wxhook.backup.BackupOrchestrator
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import java.io.File

/**
 * Entry point for the backup system.
 * Delegates to focused classes in com.nous.wxhook.backup:
 * - BackupEnv: shared state and su helpers
 * - TargetAppController: WeChat process lifecycle
 * - WeChatSourceResolver: data dir discovery
 * - ArchiveService: compression and DB decryption
 * - BackupManifest: state/records persistence
 * - BackupOrchestrator: backup flow orchestration
 */
object BackupHookLocal {

    val binPath: String get() = BackupEnv.binDir

    fun init(ctx: android.content.Context) {
        BackupEnv.binDir = "/data/local/tmp/wxhook_bin"
        BackupEnv.filesDirPath = ctx.filesDir.absolutePath
        // Ensure backup dirs exist
        try { File(WxHookPaths.BACKUP_DIR).mkdirs() } catch (_: Exception) {}
        try { File(WxHookPaths.BACKUP_DATA_DIR).mkdirs() } catch (_: Exception) {}
        try { File("${WxHookPaths.BACKUP_DATA_DIR}/tmp").mkdirs() } catch (_: Exception) {}
        // Prevent Android MediaStore from scanning backup images
        RootGateways.run(
            "touch ${WxHookPaths.BACKUP_DIR}/.nomedia && chmod 644 ${WxHookPaths.BACKUP_DIR}/.nomedia",
            10_000
        )
    }

    fun doFullBackup(callback: ProgressCallback? = null): Result =
        BackupOrchestrator.doFullBackup(callback)

    fun doIncrementalBackup(callback: ProgressCallback? = null): Result =
        BackupOrchestrator.doIncrementalBackup(callback)

    fun testRemoteConnection(remote: String, configPath: String = ""): String =
        BackupOrchestrator.testRemoteConnection(remote, configPath)

    fun rebuildDbState(): String =
        BackupOrchestrator.rebuildDbState()

    fun doRestore(callback: ProgressCallback? = null): Result =
        BackupOrchestrator.doRestore(callback)

    fun setCompressionUseZstd(enabled: Boolean) =
        BackupManifest.setCompressionUseZstd(enabled)

    // ── API surface kept for callers ──

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    data class Result(val success: Boolean, val message: String)
}
