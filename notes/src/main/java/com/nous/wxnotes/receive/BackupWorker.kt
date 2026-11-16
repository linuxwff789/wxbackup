package com.nous.wxnotes.receive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nous.wxnotes.data.WebDavBackup

/** 每日自动备份到 WebDAV（配置无效时静默跳过）。 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cfg = WebDavBackup.loadConfig(applicationContext)
        if (!cfg.isValid) return Result.success()
        return WebDavBackup.runBackup(applicationContext, cfg)
            .fold({ Result.success() }, { if (runAttemptCount < 2) Result.retry() else Result.success() })
    }

    companion object {
        const val UNIQUE_NAME = "wxnotes-webdav-daily"
    }
}
