package com.nous.wxhook.backup

import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages backup state files, DB state, records, and config persistence.
 */
object BackupManifest {

    private const val RECORDS_FILE = WxHookPaths.RECORDS_FILE
    private const val STATE_FILE = WxHookPaths.STATE_FILE
    private const val DB_STATE_FILE = WxHookPaths.DB_STATE_FILE
    private const val DB_CONFIG_FILE = WxHookPaths.DB_CONFIG_FILE

    // ── DB State ──

    fun saveDbState(userDir: File, tag: String, maxRowId: Long = 0) {
        val state = JSONObject().apply {
            put("lastBackupTag", tag)
            put("lastBackupTime", System.currentTimeMillis())
            if (maxRowId > 0) put("lastMessageRowId", maxRowId)
        }
        val tmp = File(BackupEnv.filesDirForWrite(), "db_state_${userDir.name}.json")
        tmp.writeText(state.toString())
        BackupEnv.suCopy(tmp, File(userDir, DB_STATE_FILE))
    }

    fun loadDbState(userDir: File): JSONObject {
        val f = File(userDir, DB_STATE_FILE)
        return try {
            val txt = BackupEnv.suOut("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
        } catch (e: Exception) {
            android.util.Log.e("wxhook:INCR", "loadDbState failed: $e")
            JSONObject()
        }
    }

    fun updateDbState(userDir: File, tag: String, newRowId: String) {
        val state = loadDbState(userDir)
        state.put("lastBackupTag", tag)
        state.put("lastBackupTime", System.currentTimeMillis())
        state.put("incrCount", state.optInt("incrCount", 0) + 1)
        val rowId = newRowId.toLongOrNull()
        if (rowId != null && rowId > 0) state.put("lastMessageRowId", rowId)
        val tmp = File(BackupEnv.filesDirForWrite(), "db_state_${userDir.name}.json")
        tmp.writeText(state.toString())
        BackupEnv.suCopy(tmp, File(userDir, DB_STATE_FILE))
    }

    // ── Backup State ──

    fun saveState(tag: String, count: Long, size: Long) {
        val state = JSONObject().apply {
            put("lastBackupTime", System.currentTimeMillis())
            put("lastBackupTag", tag)
            put("fileCount", count)
            put("totalSize", size)
        }
        val tmp = File(BackupEnv.filesDirForWrite(), STATE_FILE)
        tmp.writeText(state.toString())
        BackupEnv.suCopy(tmp, File(BackupEnv.backupDir, STATE_FILE))
    }

    fun loadState(): JSONObject {
        val f = File(BackupEnv.backupDir, STATE_FILE)
        return try {
            val txt = BackupEnv.suOut("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
        } catch (_: Exception) { JSONObject() }
    }

    // ── DB Config ──

    fun saveDbConfig() {
        val config = JSONObject().apply {
            put("password", ArchiveService.getDbPassword())
            put("savedAt", System.currentTimeMillis())
        }
        val tmp = File(BackupEnv.filesDirForWrite(), DB_CONFIG_FILE)
        tmp.writeText(config.toString())
        BackupEnv.suCopy(tmp, File(BackupEnv.backupDir, DB_CONFIG_FILE))
    }

    fun setCompressionUseZstd(enabled: Boolean) {
        val cfg = try {
            JSONObject(BackupEnv.backupRead(File(BackupEnv.backupDir, DB_CONFIG_FILE).absolutePath))
        } catch (_: Exception) { JSONObject() }
        cfg.put("zstd", enabled)
        BackupEnv.backupWrite(File(BackupEnv.backupDir, DB_CONFIG_FILE).absolutePath, cfg.toString())
    }

    // ── Records ──

    fun createRecord(
        tag: String,
        type: String,
        fileCount: Long,
        totalSize: Long,
        message: String,
        compression: String = "",
        durationMs: Long = 0
    ): JSONObject {
        val comp = if (compression.isNotEmpty()) compression else if (BackupEnv.useZstd()) "zstd" else "gzip"
        return JSONObject().apply {
            put("tag", tag)
            put("type", type)
            put("time", System.currentTimeMillis())
            put("fileCount", fileCount)
            put("totalSize", totalSize)
            put("message", message)
            put("compression", comp)
            if (durationMs > 0) put("durationMs", durationMs)
        }
    }

    fun addRecord(record: JSONObject) {
        val dir = File(BackupEnv.backupDir)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, RECORDS_FILE)
        val arr = try {
            val txt = BackupEnv.suOut("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONArray(txt) else JSONArray()
        } catch (_: Exception) { JSONArray() }
        arr.put(record)
        while (arr.length() > 50) arr.remove(0)
        val tmp = File(BackupEnv.filesDirForWrite(), RECORDS_FILE)
        tmp.writeText(arr.toString())
        BackupEnv.suCopy(tmp, f)
    }

    // ── Helpers ──

    fun formatSize(bytes: Long): String = when {
        bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / 1024 / 1024 / 1024)
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }

    /**
     * Writes the gitCommit hash into the state file and all user db_state.json files.
     */
    fun stampGitCommit(gitHash: String) {
        if (gitHash.isEmpty()) return
        try {
            val stateFile = File(BackupEnv.backupDir, STATE_FILE)
            val st = JSONObject(BackupEnv.backupRead(stateFile.absolutePath))
            st.put("gitCommit", gitHash)
            BackupEnv.backupWrite(stateFile.absolutePath, st.toString())
            for (d in WeChatSourceResolver.findUserBackupDirs()) {
                val dbStateFile = File(d, DB_STATE_FILE)
                if (BackupEnv.backupExists(dbStateFile.absolutePath)) {
                    val dbst = JSONObject(BackupEnv.backupRead(dbStateFile.absolutePath))
                    dbst.put("gitCommit", gitHash)
                    BackupEnv.backupWrite(dbStateFile.absolutePath, dbst.toString())
                }
            }
        } catch (_: Exception) {}
    }
}
