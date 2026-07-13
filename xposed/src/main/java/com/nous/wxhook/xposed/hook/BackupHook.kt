package com.nous.wxhook.xposed.hook

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Backup functionality for Xposed module.
 * Runs inside WeChat process, can directly access WeChat files via Java IO.
 */
object BackupHook {

    private const val TAG = "wxhook:Backup"
    private const val DEFAULT_BACKUP_DIR = "/sdcard/Download/wxhook_backup"
    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"
    private var backupDir = DEFAULT_BACKUP_DIR

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Get PID directly from current process
        val pid = android.os.Process.myPid()
        XposedBridge.log("$TAG hook installed (pid=$pid)")
    }

    fun setBackupDir(path: String) { backupDir = path }
    fun getBackupDir(): String = backupDir

    /**
     * Find WeChat data directory dynamically
     */
    private fun findWxDataPath(): String? {
        val pid = android.os.Process.myPid()
        val basePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg"
        val baseDir = File(basePath)
        if (!baseDir.exists()) return null

        // Find directory containing EnMicroMsg.db
        baseDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val dbFile = File(dir, "EnMicroMsg.db")
                if (dbFile.exists()) return dir.absolutePath
            }
        }
        return null
    }

    /**
     * Full backup: DB + all attachments with compression
     */
    fun doFullBackup(): BackupResult {
        return try {
            val dir = File(backupDir)
            if (!dir.exists()) dir.mkdirs()

            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L
            var fileCount = 0L
            var totalSize = 0L

            // Backup DB (with gzip)
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists()) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db.gz")
                compressFile(dbSrc, dbDst)
                dbSize = dbDst.length()
                fileCount++
                totalSize += dbSize
                XposedBridge.log("$TAG DB: ${dbDst.name} (${dbSize} bytes)")
            }

            // Backup attachments
            val wxBase = findWxDataPath()
            if (wxBase != null) {
                val attDirs = listOf("image2", "voice2", "video", "cdn")
                for (attDir in attDirs) {
                    val src = File(wxBase, attDir)
                    if (src.exists()) {
                        val count = copyDirRecursive(src, File(dir, attDir))
                        fileCount += count.first
                        totalSize += count.second
                        XposedBridge.log("$TAG $attDir: ${count.first} files")
                    }
                }
            }

            // Save state and record
            saveState(tag, fileCount, totalSize)
            val record = createRecord(tag, "full", dbSize, fileCount, totalSize, "全量备份完成")
            addRecord(record)

            BackupResult(true, dbSize, fileCount, totalSize, fileCount, "全量备份完成: ${fileCount}个文件")
        } catch (e: Exception) {
            XposedBridge.log("$TAG fullBackup error: $e")
            BackupResult(false, 0, 0, 0, 0, "备份失败: ${e.message}")
        }
    }

    /**
     * Incremental backup: only new/modified files
     */
    fun doIncrementalBackup(): BackupResult {
        return try {
            val dir = File(backupDir)
            if (!dir.exists()) dir.mkdirs()

            val state = loadState()
            val lastBackupTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L
            var fileCount = 0L
            var totalSize = 0L
            var newFiles = 0L

            // Check DB
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists() && dbSrc.lastModified() > lastBackupTime) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db.gz")
                // Remove old DB backups
                dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && it.name.endsWith(".db.gz") }?.forEach { it.delete() }
                compressFile(dbSrc, dbDst)
                dbSize = dbDst.length()
                fileCount++
                totalSize += dbSize
                newFiles++
            }

            // Check attachments
            val wxBase = findWxDataPath()
            if (wxBase != null) {
                val attDirs = listOf("image2", "voice2", "video", "cdn")
                for (attDir in attDirs) {
                    val src = File(wxBase, attDir)
                    if (src.exists()) {
                        val dst = File(dir, attDir)
                        val count = copyDirIncremental(src, dst, lastBackupTime)
                        fileCount += count.first
                        totalSize += count.second
                        newFiles += count.third
                    }
                }
            }

            saveState(tag, fileCount, totalSize)
            val record = createRecord(tag, "incremental", dbSize, fileCount, totalSize, if (newFiles > 0) "增量: ${newFiles}个新文件" else "无新文件")
            addRecord(record)

            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件" else "无新文件"
            BackupResult(true, dbSize, fileCount, totalSize, newFiles, msg)
        } catch (e: Exception) {
            XposedBridge.log("$TAG incrementalBackup error: $e")
            BackupResult(false, 0, 0, 0, 0, "增量备份失败: ${e.message}")
        }
    }

    /**
     * Get all backup records
     */
    fun getRecords(): List<JSONObject> {
        val f = File(backupDir, RECORDS_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    // ── Helpers ──

    private fun compressFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            GZIPOutputStream(FileOutputStream(dst)).use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
    }

    private fun copyDirRecursive(src: File, dst: File): Pair<Long, Long> {
        var count = 0L; var size = 0L
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dst, file.name); sub.mkdirs()
                val r = copyDirRecursive(file, sub); count += r.first; size += r.second
            } else {
                val dstFile = File(dst, file.name)
                FileInputStream(file).use { input ->
                    FileOutputStream(dstFile).use { output -> input.copyTo(output, bufferSize = 8192) }
                }
                dstFile.setReadable(true, false) // world-readable
                count++; size += file.length()
            }
        }
        return Pair(count, size)
    }

    private fun copyDirIncremental(src: File, dst: File, lastTime: Long): Triple<Long, Long, Int> {
        var count = 0L; var size = 0L; var newCount = 0
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dst, file.name); sub.mkdirs()
                val r = copyDirIncremental(file, sub, lastTime); count += r.first; size += r.second; newCount += r.third
            } else {
                val dstFile = File(dst, file.name)
                if (file.lastModified() > lastTime || !dstFile.exists()) {
                    FileInputStream(file).use { input ->
                        FileOutputStream(dstFile).use { output -> input.copyTo(output, bufferSize = 8192) }
                    }
                    dstFile.setReadable(true, false)
                    newCount++
                }
                count++; size += file.length()
            }
        }
        return Triple(count, size, newCount)
    }

    private fun createRecord(tag: String, type: String, dbSize: Long, fileCount: Long, totalSize: Long, message: String): JSONObject {
        val record = JSONObject()
        record.put("tag", tag)
        record.put("type", type)
        record.put("time", System.currentTimeMillis())
        record.put("dbSize", dbSize)
        record.put("fileCount", fileCount)
        record.put("totalSize", totalSize)
        record.put("message", message)
        record.put("backupDir", backupDir)
        return record
    }

    private fun addRecord(record: JSONObject) {
        val f = File(backupDir, RECORDS_FILE)
        val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        arr.put(record)
        // Keep last 50 records
        while (arr.length() > 50) arr.remove(0)
        f.writeText(arr.toString())
    }

    private fun saveState(tag: String, count: Long, size: Long) {
        val state = JSONObject()
        state.put("lastBackupTime", System.currentTimeMillis())
        state.put("lastBackupTag", tag)
        state.put("fileCount", count)
        state.put("totalSize", size)
        File(backupDir, STATE_FILE).writeText(state.toString())
    }

    private fun loadState(): JSONObject {
        val f = File(backupDir, STATE_FILE)
        return if (f.exists()) try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() } else JSONObject()
    }

    data class BackupResult(val success: Boolean, val dbSize: Long, val fileCount: Long, val totalSize: Long, val newFiles: Long, val message: String)
}
