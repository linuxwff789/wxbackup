package com.nous.wxhook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nous.wxhook.rootbridge.RootCommandRunner
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupService : Service() {
    companion object {
        private const val CHANNEL_ID = "wxhook_backup"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.nous.wxhook.BACKUP_START"
        private const val EXTRA_INCREMENTAL = "incremental"
        const val ACTION_FINISH = "com.nous.wxhook.BACKUP_FINISH"
        const val EXTRA_OK = "ok"
        const val EXTRA_MSG = "msg"

        fun start(ctx: Context, incremental: Boolean) {
            val i = Intent(ctx, BackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCREMENTAL, incremental)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val incremental = intent.getBooleanExtra(EXTRA_INCREMENTAL, true)
            startBackup(incremental)
        }
        return START_NOT_STICKY
    }

    private fun startBackup(incremental: Boolean) {
        // startForeground MUST be on main thread, else system kills the app
        try { startForeground(NOTIFICATION_ID, createNotification(if (incremental) "增量备份中..." else "全量备份中...")) } catch (_: Exception) {}
        Thread {
            try {
                BackupHookLocal.init(this)
                appendLog("服务启动: " + if (incremental) "增量备份" else "全量备份")
                updateNotification("前台服务已启动，开始前置检查")
                val version = runSu("LD_PRELOAD='${BackupHookLocal.binPath}/libz.so.1:${BackupHookLocal.binPath}/libcrypto.so.3:${BackupHookLocal.binPath}/libedit.so:${BackupHookLocal.binPath}/libncursesw.so.6' ${BackupHookLocal.binPath}/sqlcipher -version 2>/dev/null | head -1")
                appendLog("sqlcipher: " + if (version.isNotBlank()) version else "(empty)")
                updateNotification("sqlcipher检查完成")
                val cb = object : BackupHookLocal.ProgressCallback {
                    override fun onProgress(current: String, fileCount: Long, totalSize: Long) {
                        updateNotification(current)
                        appendLog(current)
                    }
                }
                val result = if (incremental) BackupHookLocal.doIncrementalBackup(cb) else BackupHookLocal.doFullBackup(cb)
                appendLog((if (result.success) "完成: " else "失败: ") + result.message)
                updateNotification(result.message)
                sendBroadcast(Intent(ACTION_FINISH).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_OK, result.success)
                    putExtra(EXTRA_MSG, result.message)
                })
            } catch (e: Exception) {
                appendLog("服务异常: ${e.message}")
                updateNotification("服务异常: ${e.message}")
                sendBroadcast(Intent(ACTION_FINISH).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_OK, false)
                    putExtra(EXTRA_MSG, "服务异常: ${e.message}")
                })
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
        }.start()
    }

    private fun appendLog(msg: String) {
        try {
            val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "] " + msg
            val tmp = File(filesDir, "backup_live.log")
            tmp.appendText(line + "\n")
            RootCommandRunner.runSu("mkdir -p /sdcard/Download/wxhook_backup && cat \"${tmp.absolutePath}\" >> /sdcard/Download/wxhook_backup/backup_live.log && chmod 644 /sdcard/Download/wxhook_backup/backup_live.log")
            tmp.writeText("")
        } catch (_: Exception) {}
    }

    private fun runSu(cmd: String): String {
        return RootCommandRunner.runSuQuiet(cmd)
    }

    private fun createNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "备份服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("wxhook 备份")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }
}
