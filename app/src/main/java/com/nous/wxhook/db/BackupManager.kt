package com.nous.wxhook.db

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup file manager for the Manager App.
 * Lists backup records, reads/deletes backups.
 */
object BackupManager {

    const val BACKUP_DIR = "/sdcard/Download/wxhook_backup"
    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"

    data class BackupRecord(val tag: String, val type: String, val time: Long, val dbSize: Long, val fileCount: Long, val totalSize: Long, val message: String)

    /**
     * Get all backup records
     */
    fun getRecords(): List<BackupRecord> {
        val f = File(BACKUP_DIR, RECORDS_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
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

    /**
     * Get backup directory info
     */
    fun getBackupInfo(): JSONObject {
        val dir = File(BACKUP_DIR)
        val info = JSONObject()
        info.put("backupDir", dir.absolutePath)
        info.put("exists", dir.exists())

        if (dir.exists()) {
            var totalSize = 0L
            var fileCount = 0
            dir.listFiles()?.forEach { f ->
                if (f.isFile) { totalSize += f.length(); fileCount++ }
                if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }.forEach { totalSize += it.length(); fileCount++ }
                }
            }
            info.put("totalSize", totalSize)
            info.put("fileCount", fileCount)

            // Attachment dirs
            val attDirs = listOf("image2", "voice2", "video", "cdn")
            attDirs.forEach { d ->
                val ad = File(dir, d)
                if (ad.exists()) {
                    val count = ad.walkTopDown().filter { it.isFile }.count()
                    val size = ad.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    info.put("${d}_count", count)
                    info.put("${d}_size", size)
                }
            }
        }

        // Load state
        val stateFile = File(BACKUP_DIR, STATE_FILE)
        if (stateFile.exists()) {
            try {
                val state = JSONObject(stateFile.readText())
                info.put("lastBackupTime", state.optLong("lastBackupTime", 0))
                info.put("lastFileCount", state.optInt("fileCount", 0))
                info.put("lastTotalSize", state.optLong("totalSize", 0))
            } catch (_: Exception) {}
        }

        return info
    }

    /**
     * Delete a backup file
     */
    fun deleteBackup(path: String): Boolean {
        val f = File(path)
        return if (f.exists() && f.absolutePath.startsWith(BACKUP_DIR)) {
            f.delete()
        } else false
    }

    /**
     * Format file size
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / 1024 / 1024 / 1024)
            bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
            bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
            else -> "$bytes B"
        }
    }

    /**
     * Format time
     */
    fun formatTime(time: Long): String {
        return if (time == 0L) "无"
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }
}
