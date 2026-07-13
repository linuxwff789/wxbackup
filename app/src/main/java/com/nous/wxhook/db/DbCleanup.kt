package com.nous.wxhook.db

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Database cleanup manager.
 * Handles old DB copies and prevents disk space issues.
 */
object DbCleanup {

    private const val TAG = "wxhook:Cleanup"
    private const val DB_DIR = "/sdcard/Download"
    private const val DB_PREFIX = "EnMicroMsg"
    private const val MAX_COPIES = 2 // Keep at most 2 copies

    /**
     * Clean old database copies, keep only latest N.
     */
    fun cleanOldCopies() {
        try {
            val dir = File(DB_DIR)
            val dbFiles = dir.listFiles { file ->
                file.name.startsWith(DB_PREFIX) && file.name.endsWith(".db")
            }?.sortedByDescending { it.lastModified() } ?: return

            if (dbFiles.size <= MAX_COPIES) return

            val toDelete = dbFiles.drop(MAX_COPIES)
            for (file in toDelete) {
                val deleted = file.delete()
                Log.i(TAG, "Deleted old copy: ${file.name} (${file.length() / 1024 / 1024} MB) = $deleted")
            }

            // Also clean WAL and SHM files
            dir.listFiles { file ->
                file.name.startsWith(DB_PREFIX) && (file.name.endsWith("-wal") || file.name.endsWith("-shm"))
            }?.forEach { file ->
                // Only delete if main DB is deleted
                val mainDb = File(file.path.removeSuffix("-wal").removeSuffix("-shm"))
                if (!mainDb.exists()) {
                    file.delete()
                    Log.i(TAG, "Deleted orphan: ${file.name}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Clean failed: ${e.message}")
        }
    }

    /**
     * Get disk usage info.
     */
    fun getDiskInfo(): String {
        return try {
            val dir = File(DB_DIR)
            val dbFiles = dir.listFiles { file ->
                file.name.startsWith(DB_PREFIX) && file.name.endsWith(".db")
            } ?: emptyArray()

            val totalSize = dbFiles.sumOf { it.length() }
            val count = dbFiles.size

            "数据库副本: ${count}个, 共${totalSize / 1024 / 1024} MB"
        } catch (_: Exception) { "无法获取磁盘信息" }
    }
}
