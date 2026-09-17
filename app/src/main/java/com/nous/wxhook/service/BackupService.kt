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
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupService : Service() {
    companion object {
        private const val CHANNEL_ID = "wxhook_backup"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.nous.wxhook.BACKUP_START"
        private const val ACTION_REBUILD = "com.nous.wxhook.BACKUP_REBUILD"
        private const val ACTION_RESTORE = "com.nous.wxhook.BACKUP_RESTORE"
        private const val EXTRA_INCREMENTAL = "incremental"
        const val ACTION_FINISH = "com.nous.wxhook.BACKUP_FINISH"
        const val ACTION_PROGRESS = "com.nous.wxhook.BACKUP_PROGRESS"
        const val EXTRA_OK = "ok"
        const val EXTRA_MSG = "msg"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_DETAIL = "detail"

        fun start(ctx: Context, incremental: Boolean) {
            val i = Intent(ctx, BackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCREMENTAL, incremental)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun startRebuild(ctx: Context) {
            val i = Intent(ctx, BackupService::class.java).apply {
                action = ACTION_REBUILD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun startRestore(ctx: Context) {
            val i = Intent(ctx, BackupService::class.java).apply {
                action = ACTION_RESTORE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val incremental = intent.getBooleanExtra(EXTRA_INCREMENTAL, true)
            startBackup(incremental)
        } else if (intent?.action == ACTION_REBUILD) {
            startRebuild()
        } else if (intent?.action == ACTION_RESTORE) {
            startRestore()
        }
        return START_NOT_STICKY
    }

    private fun startBackup(incremental: Boolean) {
        // startForeground MUST be on main thread, else system kills the app
        try {
            startForeground(NOTIFICATION_ID, createNotification(if (incremental) "增量备份中..." else "全量备份中..."))
        } catch (e: Exception) {
            android.util.Log.e("wxhook:backup", "Failed to start foreground: ${e.message}")
        }
        Thread {
            try {
                val gateway = RootGateways.gateway as? RootGatewayImpl
                if (gateway == null || !runBlocking { gateway.ensureRootService() }) {
                    appendLog("失败: RootService 未连接")
                    updateNotification("RootService 未连接")
                    return@Thread
                }
                BackupHookLocal.init(this)
                appendLog("服务启动: " + if (incremental) "增量备份" else "全量备份")
                updateNotification("前台服务已启动，开始前置检查")
                val version = runSu("LD_PRELOAD='${BackupHookLocal.binPath}/libz.so.1:${BackupHookLocal.binPath}/libcrypto.so.3:${BackupHookLocal.binPath}/libedit.so:${BackupHookLocal.binPath}/libncursesw.so.6' ${BackupHookLocal.binPath}/sqlcipher -version 2>/dev/null | head -1")
                appendLog("sqlcipher: " + if (version.isNotBlank()) version else "(empty)")
                updateNotification("sqlcipher检查完成")
                val cb = object : BackupHookLocal.ProgressCallback {
                    override fun onProgress(current: String, fileCount: Long, totalSize: Long) {
                        updateNotification(current, currentPercent())
                        sendProgress(currentPercent(), current)
                        appendLog(current)
                    }
                }
                val stagePolling = startStagePolling()
                val result = try {
                    if (incremental) BackupHookLocal.doIncrementalBackup(cb) else BackupHookLocal.doFullBackup(cb)
                } finally {
                    stagePolling.set(true)
                    com.nous.wxhook.backup.BackupOrchestrator.ProgressStage.clear()
                }
                appendLog((if (result.success) "完成: " else "失败: ") + result.message)
                sendProgress(100, result.message)
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
            finishNotification()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
        }.start()
    }

    private fun startRebuild() {
        try {
            startForeground(NOTIFICATION_ID, createNotification("重建备份状态中..."))
        } catch (e: Exception) {
            android.util.Log.e("wxhook:rebuild", "Failed to start foreground: ${e.message}")
        }
        Thread {
            try {
                BackupHookLocal.init(this)
                android.util.Log.e("wxhook:rebuild", "Rebuild started via BackupService")
                appendLog("开始重建备份状态")
                updateNotification("正在重建...")
                val result = BackupHookLocal.rebuildDbState()
                android.util.Log.e("wxhook:rebuild", "Rebuild result: $result")
                appendLog(result)
                updateNotification("重建完成")
                sendBroadcast(Intent(ACTION_FINISH).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_OK, true)
                    putExtra(EXTRA_MSG, result)
                })
            } catch (e: Exception) {
                android.util.Log.e("wxhook:rebuild", "Rebuild crashed", e)
                appendLog("重建异常: ${e.message}")
                updateNotification("重建异常: ${e.message}")
            }
            finishNotification()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
        }.start()
    }

    private fun startRestore() {
        try {
            startForeground(NOTIFICATION_ID, createNotification("准备从备份恢复..."))
        } catch (e: Exception) {
            android.util.Log.e("wxhook:restore", "Failed to start foreground: ${e.message}")
        }
        Thread {
            try {
                val gateway = RootGateways.gateway as? RootGatewayImpl
                if (gateway == null || !runBlocking { gateway.ensureRootService() }) {
                    appendLog("失败: RootService 未连接")
                    updateNotification("RootService 未连接")
                    return@Thread
                }
                BackupHookLocal.init(this)
                appendLog("开始从备份恢复")
                updateNotification("正在恢复...")
                val cb = object : BackupHookLocal.ProgressCallback {
                    override fun onProgress(current: String, fileCount: Long, totalSize: Long) {
                        updateNotification(current, currentPercent())
                        sendProgress(currentPercent(), current)
                        appendLog(current)
                    }
                }
                val stagePolling = startStagePolling()
                val result = try {
                    BackupHookLocal.doRestore(cb)
                } finally {
                    stagePolling.set(true)
                    com.nous.wxhook.backup.BackupOrchestrator.ProgressStage.clear()
                }
                appendLog(if (result.success) "✅ 恢复成功" else "❌ 恢复失败: ${result.message}")
                sendProgress(100, result.message)
                sendBroadcast(Intent(ACTION_FINISH).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_OK, result.success)
                    putExtra(EXTRA_MSG, result.message)
                })
            } catch (e: Exception) {
                android.util.Log.e("wxhook:restore", "Restore crashed", e)
                appendLog("恢复异常: ${e.message}")
                updateNotification("恢复异常: ${e.message}")
            }
            finishNotification()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
        }.start()
    }

    private val logLock = Any()
    private val pendingLogs = mutableListOf<String>()
    private var logFlushScheduled = false

    /**
     * 追加备份日志：先写内存缓冲，2 秒批量刷一次到文件（避免每条日志都走一次
     * root Binder 调用导致日志进度远远落后于前台服务通知）。
     */
    private fun appendLog(msg: String) {
        val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "] " + msg
        synchronized(logLock) {
            pendingLogs.add(line)
            if (logFlushScheduled) return
            logFlushScheduled = true
            val flush = Thread {
                try {
                    Thread.sleep(2000)
                    val batch: String
                    synchronized(logLock) {
                        batch = pendingLogs.joinToString("\n")
                        pendingLogs.clear()
                        logFlushScheduled = false
                    }
                    val tmp = File(filesDir, "backup_live.log")
                    tmp.appendText(batch + "\n")
                    RootGateways.run("mkdir -p /sdcard/Download/wxhook_backup && cat \"${tmp.absolutePath}\" >> /sdcard/Download/wxhook_backup/backup_live.log && chmod 644 /sdcard/Download/wxhook_backup/backup_live.log")
                    tmp.writeText("")
                } catch (_: Exception) {
                    synchronized(logLock) { logFlushScheduled = false }
                }
            }.apply { isDaemon = true }
            flush.start()
        }
    }

    private fun runSu(cmd: String): String {
        return RootGateways.runQuiet(cmd)
    }

    private fun createNotification(text: String, percent: Int = -1): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "备份服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("wxhook 备份")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
        // percent < 0 时不显示进度条（以前的实现一律带不确定进度条 → 一直转圈）
        if (percent >= 0) builder.setProgress(100, percent, false)
        return builder.build()
    }

    private fun updateNotification(text: String, percent: Int = -1) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(text, percent))
    }

    /**
     * 备份结束：撤掉前台状态并**取消通知**。
     * 以前只 stopSelf()，而 notify() 单独发过的通知不归前台服务管，会一直挂在通知栏闪。
     */
    private fun finishNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    /** 当前阶段百分比（0..99，完成前不显示 100）。 */
    private fun currentPercent(): Int {
        val stage = com.nous.wxhook.backup.BackupOrchestrator.ProgressStage
        val pct = stage.percent()
        return if (pct < 0) -1 else pct.coerceIn(0, 99)
    }

    private fun stageDetail(): String {
        val stage = com.nous.wxhook.backup.BackupOrchestrator.ProgressStage
        if (stage.label.isEmpty() || stage.total <= 0) return ""
        val secs = if (stage.startAt > 0) (System.currentTimeMillis() - stage.startAt) / 1000 else 0
        val done = com.nous.wxhook.backup.BackupManifest.formatSize(stage.done)
        val total = com.nous.wxhook.backup.BackupManifest.formatSize(stage.total)
        return if (stage.unit == "entry" || stage.unit == "file") {
            "${stage.label} ${stage.done}/${stage.total} 个 · ${secs}s"
        } else if (stage.unit == "dir") {
            "${stage.label} ${stage.done}/${stage.total} 个目录 · ${secs}s"
        } else {
            "${stage.label} $done/$total · ${secs}s"
        }
    }

    /**
     * 每秒把 ProgressStage（真实产物大小/打包条目数）刷进通知和 UI 广播。
     * 数据库基线 dump 和 native 打包这两步都没有回调，只能靠轮询真实信号。
     */
    private fun startStagePolling(): java.util.concurrent.atomic.AtomicBoolean {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            while (!stop.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (stop.get()) break
                val pct = currentPercent()
                val detail = stageDetail()
                if (pct >= 0 && detail.isNotEmpty()) {
                    updateNotification("$pct% · $detail", pct)
                    sendProgress(pct, detail)
                }
            }
        }.apply {
            isDaemon = true
            name = "wxhook-stage-poll"
            start()
        }
        return stop
    }

    private fun sendProgress(percent: Int, detail: String) {
        try {
            sendBroadcast(Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_PERCENT, percent)
                putExtra(EXTRA_DETAIL, detail)
            })
        } catch (_: Exception) {
        }
    }
}
