package com.nous.wxhook.rootbridge.backup

import com.nous.wxhook.backup.ArchiveService
import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.backup.BackupManifest
import com.nous.wxhook.backup.BackupOrchestrator
import com.nous.wxhook.rootbridge.RootCommandRunner
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
        BackupEnv.rcloneConfigPath = ctx.filesDir.absolutePath + "/.config/rclone/rclone.conf"
        // Ensure backup dir exists (app process, not root)
        try { File(WxHookPaths.BACKUP_DIR).mkdirs() } catch (_: Exception) {}
        try { File("${WxHookPaths.BACKUP_DIR}/tmp").mkdirs() } catch (_: Exception) {}
        // Prevent Android MediaStore from scanning backup images
        RootCommandRunner.runSu(
            "touch ${WxHookPaths.BACKUP_DIR}/.nomedia && chmod 644 ${WxHookPaths.BACKUP_DIR}/.nomedia",
            10_000
        )
        // Fix DNS for rclone (Go resolver needs /etc/resolv.conf)
        RootCommandRunner.runSu(
            "mkdir -p /data/local/tmp/etc && echo 'nameserver 8.8.8.8' > /data/local/tmp/etc/resolv.conf && " +
            "mount --bind /data/local/tmp/etc /etc 2>/dev/null; " +
            "echo 'nameserver 8.8.4.4' >> /etc/resolv.conf 2>/dev/null",
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

    fun setCompressionUseZstd(enabled: Boolean) =
        BackupManifest.setCompressionUseZstd(enabled)

    // ── API surface kept for callers ──

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    data class Result(val success: Boolean, val message: String)
}
