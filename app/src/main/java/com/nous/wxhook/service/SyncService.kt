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
            val binDir = "/data/local/tmp/wxhook_bin"
            val BACKUP_DIR = "/sdcard/Download/wxhook_backup"
            var result = "同步失败"
            try {
                appendLog("同步服务启动")
                updateNotification("读取配置...")
                // Read remote config
                val cfgRaw = RootCommandRunner.runSuQuiet("cat \"$BACKUP_DIR/remote_config.json\" 2>/dev/null").ifBlank { "{}" }
                val cfg = org.json.JSONObject(cfgRaw)
                val enabled = cfg.optBoolean("enabled", false)
                val remoteStr = cfg.optString("remote", "")
                if (!enabled || remoteStr.isBlank()) {
                    result = "同步未启用或未配置"; appendLog(result); updateNotification(result)
                    sendResult(false, result); return@Thread
                }
                val rcloneCfg = File(filesDir, ".config/rclone/rclone.conf")

                // Package backup files into .wxhook
                val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val pkgName = "wxhook_backup_$tag.wxhook"
                val pkgPath = "/data/local/tmp/$pkgName"

                updateNotification("打包备份文件...")
                appendLog("打包: $pkgName")
                val findCmd = "find \"$BACKUP_DIR\" -maxdepth 1 -type f -name \"backup_*.json\" 2>/dev/null; " +
                    "find \"$BACKUP_DIR\" -maxdepth 2 -type f \\( -name \"*.sql.gz\" -o -name \"*.sql.zst\" -o -name \"db_state.json\" \\) 2>/dev/null"
                val files = RootCommandRunner.runSuQuiet(findCmd).lines().filter { it.isNotBlank() }
                if (files.isEmpty()) { result = "无备份文件可同步"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread }

                val pkgInfo = BackupPackage.create(files, pkgPath, tag, "incremental")
                if (pkgInfo == null) { result = "打包失败"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread }
                appendLog("打包完成: ${pkgInfo.totalSize} bytes (${pkgInfo.fileCount} files)")

                // Upload
                updateNotification("上传中...")
                val env = "HOME=/data/local/tmp LD_LIBRARY_PATH=$binDir SSL_CERT_DIR=/system/etc/security/cacerts"
                val rclone = "$binDir/rclone"
                val args = mutableListOf("$env $rclone", "copy", pkgPath,
                    if (remoteStr.contains(":")) remoteStr else "$remoteStr:",
                    "--no-check-certificate", "--timeout=30s")
                if (rcloneCfg.exists()) {
                    args.add("--config"); args.add(rcloneCfg.absolutePath)
                }
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", args.joinToString(" ")))
                val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                proc.inputStream.bufferedReader().readText()
                if (!finished) { proc.destroyForcibly(); result = "上传超时"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread }
                if (proc.exitValue() != 0) { result = "上传失败(exit=${proc.exitValue()})"; appendLog(result); updateNotification(result); sendResult(false, result); return@Thread }

                // Cleanup local pkg
                RootCommandRunner.runSu("rm -f \"$pkgPath\"", 10_000)
                result = "同步完成: $pkgName (${formatSize(pkgInfo.totalSize)})"
                appendLog(result)
                updateNotification(result)
                sendResult(true, result)
            } catch (e: Exception) {
                result = "同步异常: ${e.message}"
                appendLog(result)
                updateNotification(result)
                sendResult(false, result)
            }
        }.start()
    }

    private fun sendResult(ok: Boolean, msg: String) {
        sendBroadcast(Intent(ACTION_FINISH).apply {
            setPackage(packageName)
            putExtra(EXTRA_OK, ok)
            putExtra(EXTRA_MSG, msg)
        })
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
    }

    private fun appendLog(msg: String) {
        try {
            val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "] " + msg
            val tmp = File(filesDir, "sync_live.log")
            tmp.appendText(line + "\n")
            RootCommandRunner.runSu("cat \"${tmp.absolutePath}\" >> /sdcard/Download/wxhook_backup/sync_live.log && chmod 644 /sdcard/Download/wxhook_backup/sync_live.log")
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
