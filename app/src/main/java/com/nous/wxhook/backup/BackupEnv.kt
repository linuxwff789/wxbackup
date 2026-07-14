package com.nous.wxhook.backup

import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONObject
import java.io.File

object BackupEnv {
    val backupDir = WxHookPaths.BACKUP_DIR
    var binDir = "/data/local/tmp/wxhook_bin"
    var filesDirPath = "/data/local/tmp"
    var rcloneConfigPath = ""

    fun init(binDirectory: String, filesDir: String, rcloneCfg: String = "") {
        binDir = binDirectory
        filesDirPath = filesDir
        rcloneConfigPath = rcloneCfg
    }

    fun useZstd(): Boolean = try {
        val cfg = File(backupDir, "db_config.json")
        if (cfg.exists()) {
            val json = JSONObject(backupRead(cfg.absolutePath))
            json.optString("compression", "gzip") == "zstd"
        } else false
    } catch (_: Exception) { false }

    fun ext(): String = if (useZstd()) ".sql.zst" else ".sql.gz"

    // ── Root 操作 ──

    fun su(cmd: String, timeoutMs: Long = 60_000) =
        RootGateways.run(cmd, timeoutMs)

    fun suOut(cmd: String, timeoutMs: Long = 60_000) =
        RootGateways.runQuiet(cmd, timeoutMs)

    fun suCopy(tmp: File, dest: File, mode: String = "644"): Boolean {
        return RootGateways.copy(tmp.absolutePath, dest.absolutePath)
    }

    fun suCopyResult(src: String, dest: String, mode: String = "664"): Boolean {
        return RootGateways.copy(src, dest)
    }

    fun filesDirForWrite(): File = File(filesDirPath).apply { mkdirs() }

    // ── /sdcard 操作（走 root） ──

    fun backupExists(path: String): Boolean =
        RootGateways.exists(path)

    fun backupSize(path: String): Long =
        RootGateways.fileSize(path)

    fun backupRead(path: String): String =
        RootGateways.readFile(path)

    fun backupWrite(path: String, content: String) {
        RootGateways.writeFile(path, content)
    }

    fun backupMkdirs(path: String): Boolean =
        RootGateways.mkdirs(path)

    fun backupDelete(path: String): Boolean =
        RootGateways.delete(path)
}
