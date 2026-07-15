package com.nous.wxhook.backup

/** Names inside a full .tar.zst; no nested compression. */
object FullBackupLayout {
    fun databaseDumpName(): String = "EnMicroMsg_baseline.sql"
}
