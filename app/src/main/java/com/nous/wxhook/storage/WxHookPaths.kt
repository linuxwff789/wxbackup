package com.nous.wxhook.storage

object WxHookPaths {
    const val DOWNLOAD_DIR = "/sdcard/Download"
    const val BACKUP_DIR = "/sdcard/Download/wxhook_backup"
    const val BACKUP_DATA_DIR = "/sdcard/Download/wxhook_backup/backupdata"
    const val TEMP_DIR = "/data/local/tmp/wxhook"
    const val RECORDS_FILE = "backup_records.json"
    const val STATE_FILE = "backup_state.json"
    const val DB_STATE_FILE = "db_state.json"
    const val DB_CONFIG_FILE = "db_config.json"
    const val DB_HASH_DIR = "6d1f34a5edc49e8b6d238141b2d004f3"
    const val SYNC_LOG = "sync_live.log"
    const val BACKUP_LOG = "backup_live.log"

    fun backupPath(vararg parts: String): String =
        listOf(BACKUP_DIR, *parts).joinToString("/")

    fun backupDataPath(vararg parts: String): String =
        listOf(BACKUP_DATA_DIR, *parts).joinToString("/")

    fun tempPath(vararg parts: String): String =
        listOf(TEMP_DIR, *parts).joinToString("/")

    fun backupRecordsPath(): String = backupPath(RECORDS_FILE)
    fun backupStatePath(): String = backupPath(STATE_FILE)
}
