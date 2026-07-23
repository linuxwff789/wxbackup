package com.nous.wxhook.backup

import com.nous.wxhook.root.RootGateways
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

    // ── DB State (single file at backup root plus per-user copies) ──

    private fun dbStateFile(): File = File(BackupEnv.backupDataDir, "db_state.json")
    private fun userDbStateDir(hash: String): File = File(BackupEnv.backupDataDir, hash)

    /** Write db_state for a user: centralized + per-user copy. */
    fun saveDbState(userHash: String, tag: String, fromRowId: Long = 0, maxRowId: Long = 0): Boolean {
        val f = dbStateFile()
        val all = runCatching { JSONObject(BackupEnv.backupRead(f.absolutePath)) }.getOrDefault(JSONObject())
        val u = all.optJSONObject(userHash) ?: JSONObject()
        u.put("lastBackupTag", tag)
        u.put("lastBackupTime", System.currentTimeMillis())
        u.put("lastMessageRowIdFrom", fromRowId)
        if (maxRowId > 0) u.put("lastMessageRowId", maxRowId)
        all.put(userHash, u)
        if (!RootGateways.writeFile(f.absolutePath, all.toString())) return false
        val uDir = File(BackupEnv.backupDataDir, userHash).absolutePath
        RootGateways.mkdirs(uDir)
        return RootGateways.writeFile("$uDir/db_state.json", u.toString())
    }

    fun loadDbState(userHash: String): JSONObject {
        val all = runCatching {
            JSONObject(BackupEnv.backupRead(dbStateFile().absolutePath))
        }.getOrDefault(JSONObject())
        return all.optJSONObject(userHash) ?: JSONObject()
    }

    /** Update db_state during incremental backup. */
    fun updateDbState(userHash: String, tag: String, fromRowId: Long, toRowId: Long) {
        val f = dbStateFile()
        val all = runCatching { JSONObject(BackupEnv.backupRead(f.absolutePath)) }.getOrDefault(JSONObject())
        val u = all.optJSONObject(userHash) ?: JSONObject()
        u.put("lastBackupTag", tag)
        u.put("lastBackupTime", System.currentTimeMillis())
        u.put("incrCount", u.optInt("incrCount", 0) + 1)
        u.put("lastMessageRowIdFrom", fromRowId)
        if (toRowId > 0) u.put("lastMessageRowId", toRowId)
        all.put(userHash, u)
        RootGateways.writeFile(f.absolutePath, all.toString())
        // Per-user copy (via root gateways since sdcard)
        val uDir = File(BackupEnv.backupDataDir, userHash).absolutePath
        RootGateways.mkdirs(uDir)
        RootGateways.writeFile("$uDir/db_state.json", u.toString())
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
        val dir = BackupEnv.backupDir
        BackupEnv.su("mkdir -p \"$dir\"")
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

    fun writeSortedRecords(sorted: List<JSONObject>): Boolean {
        val dir = BackupEnv.backupDir
        BackupEnv.su("mkdir -p \"$dir\"")
        val arr = JSONArray(sorted)
        val tmp = File(BackupEnv.filesDirForWrite(), RECORDS_FILE)
        tmp.writeText(arr.toString())
        return BackupEnv.suCopy(tmp, File(dir, RECORDS_FILE))
    }
}
