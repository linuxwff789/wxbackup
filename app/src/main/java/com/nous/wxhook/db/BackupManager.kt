package com.nous.wxhook.db

import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    const val BACKUP_DIR = WxHookPaths.BACKUP_DIR
    private const val RECORDS_FILE = WxHookPaths.RECORDS_FILE
    private const val STATE_FILE = WxHookPaths.STATE_FILE

    data class BackupRecord(val tag: String, val type: String, val time: Long, val dbSize: Long, val fileCount: Long, val totalSize: Long, val message: String)

    fun getRecords(): List<BackupRecord> {
        val f = File(BackupEnv.backupDataDir, RECORDS_FILE)
        val txt = RootGateways.runQuiet("cat \"${f.absolutePath}\" 2>/dev/null").trim()
        if (txt.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(txt)
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                BackupRecord(
                    tag = r.optString("tag", ""),
                    type = r.optString("type", ""),
                    time = r.optLong("time", 0),
                    dbSize = r.optLong("dbSize", 0),
                    fileCount = r.optLong("fileCount", 0),
                    totalSize = r.optLong("totalSize", 0),
                    message = r.optString("message", "")
                )
            }.sortedByDescending { it.time }
        } catch (e: Exception) { emptyList() }
    }

    fun getBackupInfo(): JSONObject {
        val dir = File(BACKUP_DIR)
        val info = JSONObject()
        info.put("backupDir", dir.absolutePath)
        info.put("exists", RootGateways.runQuiet("test -d \"${dir.absolutePath}\" && echo 1").trim() == "1")

        val totalSizeStr = RootGateways.runQuiet("du -sb \"${dir.absolutePath}\" 2>/dev/null | cut -f1").trim()
        info.put("totalSize", totalSizeStr.toLongOrNull() ?: 0L)

        val fileCountStr = RootGateways.runQuiet("find \"${dir.absolutePath}\" -type f 2>/dev/null | wc -l").trim()
        info.put("fileCount", fileCountStr.toLongOrNull() ?: 0L)

        // Load state
        val stateFile = File(BackupEnv.backupDataDir, STATE_FILE)
        try {
            val txt = RootGateways.runQuiet("cat \"${stateFile.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) {
                val state = JSONObject(txt)
                info.put("lastBackupTime", state.optLong("lastBackupTime", 0))
                info.put("lastFileCount", state.optInt("fileCount", 0))
                info.put("lastTotalSize", state.optLong("totalSize", 0))
            }
        } catch (_: Exception) {}

        return info
    }

    fun deleteBackup(path: String): Boolean {
        val f = File(path)
        return if (f.absolutePath.startsWith(WxHookPaths.BACKUP_DIR) && RootGateways.exists(path)) {
            RootGateways.delete(path)
        } else false
    }

    fun formatSize(bytes: Long): String = when {
        bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / 1024 / 1024 / 1024)
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }

    fun formatTime(time: Long): String =
        if (time == 0L) "无"
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}
