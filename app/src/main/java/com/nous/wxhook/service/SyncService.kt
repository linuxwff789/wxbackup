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
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.sync.WebDavClient
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
        private const val BACKUP_DIR = "/sdcard/Download/wxhook_backup/backupdata"
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
        try { startForeground(NOTIFICATION_ID, createNotification("同步中...")) } catch (_: Exception) {}
        Thread {
            var result = "同步失败"
            try {
                appendLog("同步服务启动")

                // Read WebDAV config
                val settingsCfgRaw = try { File(filesDir, "settings_config.json").readText() } catch (_: Exception) { "{}" }
                val settingsCfg = org.json.JSONObject(settingsCfgRaw)
                val webdavUrl = settingsCfg.optString("webdav_url", "")
                val webdavUser = settingsCfg.optString("webdav_user", "")
                val webdavPass = settingsCfg.optString("webdav_pass", "")
                val remotePath = settingsCfg.optString("remote_path", "wxhook-backup")

                if (webdavUrl.isBlank() || webdavUser.isBlank()) {
                    result = "WebDAV未配置"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread
                }

                // Check remote config enabled
                val remoteCfgRaw = RootGateways.runQuiet("cat \"${BackupEnv.backupDir}/remote_config.json\" 2>/dev/null").ifBlank { "{}" }
                val remoteCfg = org.json.JSONObject(remoteCfgRaw)
                if (!remoteCfg.optBoolean("enabled", false)) {
                    result = "同步未启用"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread
                }

                // Find latest wxbackup_full_*.tar.zst (backupdata or legacy)
                updateNotification("查找备份包...")
                val findResult = RootGateways.runQuiet(
                    "ls -t $BACKUP_DIR/wxbackup_full_*.tar.zst ${BackupEnv.backupDir}/wxbackup_full_*.tar.zst 2>/dev/null | head -1"
                )
                val pkgPath = findResult.trim()
                if (pkgPath.isBlank()) {
                    result = "无备份包可同步"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread
                }
                val pkgName = File(pkgPath).name
                val pkgSize = RootGateways.runQuiet("stat -c %s \"$pkgPath\" 2>/dev/null").trim().toLongOrNull() ?: 0L
                appendLog("找到备份包: $pkgName (${formatSize(pkgSize)})")

                // Connect and upload
                updateNotification("连接 WebDAV...")
                val client = WebDavClient(webdavUrl, webdavUser, webdavPass)
                val testResult = kotlinx.coroutines.runBlocking { client.testConnection() }
                if (testResult.isFailure) {
                    result = "WebDAV连接失败: ${testResult.exceptionOrNull()?.message}"
                    appendLog(result); updateNotification(result); sendResult(false, result); return@Thread
                }

                updateNotification("确保远端目录存在...")
                kotlinx.coroutines.runBlocking { client.ensureDirectory(remotePath) }

                // Check if already uploaded
                val remoteFiles = kotlinx.coroutines.runBlocking { client.list(remotePath) }.getOrNull() ?: emptyList()
                val remoteMatch = remoteFiles.find { it.path.endsWith(pkgName) }
                if (remoteMatch != null && remoteMatch.size == pkgSize) {
                    result = "跳过: $pkgName (远程已存在)"
                    appendLog(result); updateNotification(result); sendResult(true, result); return@Thread
                }

                // Upload
                updateNotification("上传 $pkgName (${formatSize(pkgSize)})...")
                appendLog("上传: $pkgName")
                val localFile = File(pkgPath)
                val uploadResult = kotlinx.coroutines.runBlocking { client.upload(localFile, "$remotePath/$pkgName") }
                if (uploadResult.isFailure) {
                    result = "上传失败: ${uploadResult.exceptionOrNull()?.message}"
                    appendLog(result); updateNotification(result); sendResult(false, result); return@Thread
                }

                result = "同步完成: $pkgName (${formatSize(pkgSize)})"
                appendLog(result)
                updateNotification(result)
                sendResult(true, result)
                // Schedule next sync if interval configured
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
                updateNotification(result)
                sendResult(false, result)
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

    private fun createNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "云同步", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("wxhook 同步")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / 1024 / 1024 / 1024)
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }
}
