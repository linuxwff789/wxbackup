package com.nous.wxhook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.File

class DecryptService : Service() {

    companion object {
        private const val TAG = "wxhook:Decrypt"
        private const val CHANNEL_ID = "wxhook_decrypt"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.nous.wxhook.DECRYPT_START"
        private const val ACTION_STOP = "com.nous.wxhook.DECRYPT_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDecrypt()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startDecrypt() {
        startForeground(NOTIFICATION_ID, createNotification("解密中..."))

        Thread {
            try {
                updateNotification("读取密钥...")
                val hex = File("/data/local/tmp/.wechat_key").readText()
                    .lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex == null) {
                    updateNotification("密钥未找到")
                    stopSelf()
                    return@Thread
                }
                val key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                updateNotification("密钥: $key")

                // Check local DB
                val localDb = "/sdcard/Download/EnMicroMsg.db"
                val localSize = runSu("stat -c %s $localDb 2>/dev/null")
                if (localSize.isEmpty()) {
                    updateNotification("数据库不存在，正在复制...")
                    val pid = runSu("pidof com.tencent.mm")
                    if (pid.isEmpty()) {
                        updateNotification("微信未运行")
                        stopSelf()
                        return@Thread
                    }
                    val srcDb = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                    runSu("cp $srcDb $localDb && chmod 666 $localDb")
                    updateNotification("数据库复制完成")
                }

                // Decrypt
                updateNotification("解密中...")
                val sql = "PRAGMA key = '$key';\n" +
                    "PRAGMA cipher_compatibility = 3;\n" +
                    "PRAGMA cipher_page_size = 1024;\n" +
                    "PRAGMA kdf_iter = 4000;\n" +
                    "PRAGMA cipher_use_hmac = OFF;\n" +
                    "SELECT 'message: ' || count(*) FROM message;\n" +
                    "SELECT 'rconversation: ' || count(*) FROM rconversation;\n" +
                    "SELECT 'chatroom: ' || count(*) FROM chatroom;\n"

                val sqlFile = "/sdcard/Download/wxhook_query.sql"
                File(sqlFile).writeText(sql)
                runSu("chmod 666 $sqlFile")

                val output = runSu("LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher $localDb < $sqlFile 2>&1")
                runSu("rm $sqlFile")

                // Parse results
                val results = output.lines().filter { it.contains(":") }.joinToString("\n")
                updateNotification("解密完成!\n$results")

                // Broadcast results
                sendBroadcast(Intent("com.nous.wxhook.DECRYPT_DONE").apply {
                    putExtra("results", results)
                    setPackage("com.nous.wxhook")
                })

                Log.i(TAG, "Decrypt done: $results")
            } catch (e: Exception) {
                updateNotification("错误: ${e.message}")
                Log.e(TAG, "Decrypt failed: ${e.message}")
            }

            // Stop after 5 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 5000)
        }.start()
    }

    private fun runSu(cmd: String): String {
        return RootCommandRunner.runSuQuiet(cmd)
    }

    private fun createNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "数据库解密", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("wxhook")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }
}
