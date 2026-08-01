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
import com.nous.wxhook.backup.ArchiveManager
import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.sync.Syncer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 前台服务：将云端存档下载到本地备份目录，并在通知栏显示实时进度。
 *
 * 通过 [start] 启动，EXTRA_JSON 传入 [{path, name}] 列表：
 * - path: 云端完整路径（传给 CloudClient.download）
 * - name: 本地文件名（含扩展名，存放到 backupdata/ 下）
 *
 * 下载完成（或失败）后发送 [ACTION_FINISH] 广播，界面可监听并刷新列表。
 */
class CloudDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "wxhook_download"
        private const val NOTIFICATION_ID = 1004
        private const val ACTION_DOWNLOAD = "com.nous.wxhook.DOWNLOAD_START"
        const val ACTION_FINISH = "com.nous.wxhook.DOWNLOAD_FINISH"
        const val EXTRA_JSON = "download_json"
        const val EXTRA_OK = "ok"
        const val EXTRA_FAIL = "fail"
        const val EXTRA_TAG = "lastTag"

        /**
         * 启动下载服务。
         * @param jobs 列表元素为 {"path": 云端路径, "name": 本地文件名(含扩展名)}
         */
        fun start(ctx: Context, jobs: List<Pair<String, String>>) {
            val arr = JSONArray()
            for ((path, name) in jobs) {
                arr.put(JSONObject().put("path", path).put("name", name))
            }
            val i = Intent(ctx, CloudDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_JSON, arr.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val json = intent.getStringExtra(EXTRA_JSON) ?: "[]"
            try {
                startForeground(NOTIFICATION_ID, createNotification("准备下载...", 0, 0))
            } catch (_: Exception) {}
            doDownload(JSONArray(json))
        }
        return START_NOT_STICKY
    }

    private fun doDownload(jobs: JSONArray) {
        Thread {
            var ok = 0
            var fail = 0
            var lastTag = ""
            val total = jobs.length()
            try {
                val config = Syncer.loadConfig()
                val client = Syncer.createClient(config)
                if (client == null) {
                    updateNotification("创建云存储客户端失败", 0, 0)
                    stopSelf()
                    return@Thread
                }
                for (i in 0 until total) {
                    val job = jobs.getJSONObject(i)
                    val path = job.optString("path", "")
                    val name = job.optString("name", "")
                    val localPath = "${BackupEnv.backupDataDir}/$name"
                    File(localPath).parentFile?.mkdirs()

                    updateNotification("下载中 [$i/${total}] $name", 0, 0)
                    val result = kotlinx.coroutines.runBlocking {
                        client.download(path, File(localPath)) { done, totalBytes ->
                            updateNotification("下载中 [$i/${total}] $name", done, totalBytes)
                        }
                    }
                    if (result.isSuccess) {
                        ok++
                        lastTag = name.removeSuffix(".tar.zst").removeSuffix(".tar.gz")
                    } else {
                        fail++
                    }
                }

                // 全部成功后选中最后一个成功项，便于直接对比/恢复
                if (lastTag.isNotBlank()) {
                    ArchiveManager.selectArchive(lastTag)
                }
                updateNotification("下载完成: $ok 成功, $fail 失败", 0, 0)
                sendFinish(ok, fail, lastTag)
            } catch (e: Exception) {
                updateNotification("下载异常: ${e.message}", 0, 0)
                sendFinish(ok, fail, lastTag)
            } finally {
                // 3 秒后自动停止（保留足够时间让用户看到结果通知）
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
            }
        }.start()
    }

    private fun sendFinish(ok: Int, fail: Int, lastTag: String) {
        sendBroadcast(Intent(ACTION_FINISH).apply {
            setPackage(packageName)
            putExtra(EXTRA_OK, ok)
            putExtra(EXTRA_FAIL, fail)
            putExtra(EXTRA_TAG, lastTag)
        })
    }

    private fun updateNotification(text: String, done: Long, total: Long) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, createNotification(text, done, total))
        } catch (_: Exception) {}
    }

    private fun createNotification(text: String, done: Long, total: Long): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "云端存档下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("wxhook 下载云端存档")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // 有确定进度时显示进度条
        if (total > 0 && done in 0..total) {
            builder.setProgress(total.toInt().coerceAtLeast(1), done.toInt(), false)
                .setContentText("$text  (${done * 100 / total}%)")
        } else if (total > 0) {
            builder.setProgress(total.toInt().coerceAtLeast(1), 0, false)
        }
        return builder.build()
    }
}
