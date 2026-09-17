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
import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.backup.BackupManifest
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.sync.SyncSettings
import com.nous.wxhook.sync.Syncer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncService : Service() {
    companion object {
        private const val CHANNEL_ID = "wxhook_sync"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_SYNC = "com.nous.wxhook.SYNC_START"
        const val ACTION_FINISH = "com.nous.wxhook.SYNC_FINISH"
        const val EXTRA_OK = "ok"
        const val EXTRA_MSG = "msg"
        private const val INTERVAL_KEY = "sync_interval_min"

        fun start(ctx: Context) {
            val i = Intent(ctx, SyncService::class.java).apply { action = ACTION_SYNC }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC) startSync()
        return START_NOT_STICKY
    }

    private fun startSync() {
        // 定时闹钟可在未打开界面时冷启动服务，必须先使用本应用可写的私有目录。
        BackupEnv.filesDirPath = filesDir.absolutePath
        try { startForeground(NOTIFICATION_ID, createNotification("同步中...")) } catch (_: Exception) {}
        Thread {
            val startTime = System.currentTimeMillis()
            val tag = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            var result = "同步失败"
            try {
                appendLog("同步服务启动")

                // Load config and check enabled
                val config = Syncer.loadConfig()
                if (!config.isValid) {
                    result = "WebDAV未配置"; appendLog(result); sendResult(false, result); finishNotification()
                    BackupManifest.addRecord(BackupManifest.createRecord(tag, "sync", 0L, 0L, result, durationMs = System.currentTimeMillis() - startTime))
                    return@Thread
                }
                // 云同步开关（唯一来源见 SyncSettings；以前这里读 /sdcard 的 remote_config.json，
                // 与设置页开关写的 settings_config.json 不是同一个文件 → 开关形同虚设）
                if (!SyncSettings.isRemoteEnabled()) {
                    result = "同步未启用"; appendLog(result); sendResult(false, result); finishNotification()
                    BackupManifest.addRecord(BackupManifest.createRecord(tag, "sync", 0L, 0L, result, durationMs = System.currentTimeMillis() - startTime))
                    return@Thread
                }

                // Sync via shared Syncer
                val syncResult = Syncer.sync(config) { p ->
                    updateSyncNotification(p)
                }

                result = syncResult.message
                appendLog(result)
                sendResult(syncResult.success, result)
                // 同步结束：取消通知（以前 stopSelf 后通知还挂在通知栏，带着进度条一直闪）
                finishNotification()

                // Save sync record
                BackupManifest.addRecord(BackupManifest.createRecord(tag, "sync",
                    syncResult.uploaded.toLong(), syncResult.totalBytes, result,
                    durationMs = System.currentTimeMillis() - startTime))

                // Schedule next sync if interval configured
                val settingsCfgRaw = try { File(filesDir, "settings_config.json").readText() } catch (_: Exception) { "{}" }
                val settingsCfg = org.json.JSONObject(settingsCfgRaw)
                val intervalMin = settingsCfg.optInt(INTERVAL_KEY, 0)
                if (intervalMin > 0) {
                    appendLog("下次同步: ${intervalMin}分钟后")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startSync()
                    }, intervalMin * 60_000L)
                } else {
                    stopSelfAfter(3000)
                }
            } catch (e: Exception) {
                result = "同步异常: ${e.message}"
                appendLog(result)
                sendResult(false, result)
                finishNotification()
                BackupManifest.addRecord(BackupManifest.createRecord(tag, "sync", 0L, 0L, result, durationMs = System.currentTimeMillis() - startTime))
                // On error, retry after 30 min if interval is set
                val intervalMin = try {
                    org.json.JSONObject(try { File(filesDir, "settings_config.json").readText() } catch (_: Exception) { "{}" }).optInt(INTERVAL_KEY, 0)
                } catch (_: Exception) { 0 }
                if (intervalMin > 0) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startSync()
                    }, 30 * 60_000L)
                } else {
                    stopSelfAfter(3000)
                }
            }
        }.start()
    }

    private fun sendResult(ok: Boolean, msg: String) {
        sendBroadcast(Intent(ACTION_FINISH).apply {
            setPackage(packageName)
            putExtra(EXTRA_OK, ok)
            putExtra(EXTRA_MSG, msg)
        })
    }

    private fun stopSelfAfter(delayMs: Long) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, delayMs)
    }

    /**
     * 同步结束：撤前台状态 + 取消通知。
     * 以前只 stopSelf()，而通知是 notify() 单独发的（不随前台服务消失），
     * 于是同步结束后通知栏里一直挂着一条带进度条的通知。
     */
    private fun finishNotification() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun appendLog(msg: String) {
        try {
            val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "] $msg"
            val tmp = File(filesDir, "sync_live.log")
            tmp.appendText("$line\n")
            RootGateways.run("cat \"${tmp.absolutePath}\" >> ${BackupEnv.backupDir}/sync_live.log && chmod 644 ${BackupEnv.backupDir}/sync_live.log")
            tmp.writeText("")
        } catch (_: Exception) {}
    }

    private fun updateNotification(text: String) {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(text)) } catch (_: Exception) {}
    }

    /**
     * 同步进度写进通知：能拿到字节（WebDAV）就显示确定进度条，拿不到（阿里云盘 AAR）
     * 就用不确定进度条 + 每秒刷新的详细文案（已耗时/包大小）。
     */
    private fun updateSyncNotification(p: Syncer.Progress) {
        try {
            val percent = if (p.bytesTotal > 0 && p.bytesSent > 0) {
                ((p.bytesSent * 100) / p.bytesTotal).toInt().coerceIn(0, 100)
            } else -1
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, createNotification(p.message, percent))
        } catch (_: Exception) {}
    }

    private fun createNotification(text: String, percent: Int = -1): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "云同步", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("wxhook 同步")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
        if (percent >= 0) builder.setProgress(100, percent, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }
}
