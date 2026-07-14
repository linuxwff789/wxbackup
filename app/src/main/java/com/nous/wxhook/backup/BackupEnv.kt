package com.nous.wxhook.backup

import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONObject
import java.io.File

/**
 * Shared state and utility helpers for the backup subsystem.
 * Initialized once by BackupHookLocal.init().
 */
object BackupEnv {

    var binDir: String = "/data/data/com.termux/files/usr/bin"
        internal set
    var filesDirPath: String = "/data/local/tmp"
        internal set

    val backupDir: String get() = WxHookPaths.BACKUP_DIR

    fun su(cmd: String, timeoutMs: Long = 60_000) =
        RootGateways.run(cmd, timeoutMs)

    fun suOut(cmd: String, timeoutMs: Long = 60_000) =
        RootGateways.runQuiet(cmd, timeoutMs)

    fun suCopy(tmp: File, dest: File, mode: String = "644"): Boolean {
        return su("cp \"${tmp.absolutePath}\" \"${dest.absolutePath}\" && chmod $mode \"${dest.absolutePath}\"").isSuccess
    }

    fun suCopyResult(src: String, dest: String, mode: String = "664"): Boolean {
        return su("cp \"$src\" \"$dest\" && chmod $mode \"$dest\"", 120_000).isSuccess
    }

    fun filesDirForWrite(): File = File(filesDirPath).apply { mkdirs() }

    fun backupExists(path: String): Boolean =
        suOut("test -e \"$path\" && echo 1").trim() == "1"

    fun backupSize(path: String): Long =
        suOut("stat -c %s \"$path\" 2>/dev/null").trim().toLongOrNull() ?: 0L

    fun backupRead(path: String): String =
        suOut("cat \"$path\" 2>/dev/null")

    fun backupWrite(path: String, content: String) {
        val tmp = File(filesDirForWrite(), "sdcard_write_${System.nanoTime()}.tmp")
        tmp.writeText(content)
        suCopy(tmp, File(path))
        tmp.delete()
    }

    fun useZstd(): Boolean = try {
        val cfg = JSONObject(backupRead(File(backupDir, WxHookPaths.DB_CONFIG_FILE).absolutePath))
        cfg.optBoolean("zstd", false)
    } catch (_: Exception) { false }

    fun ext(): String = if (useZstd()) ".sql.zst" else ".sql.gz"
}
