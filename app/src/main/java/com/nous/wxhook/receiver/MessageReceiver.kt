package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val talker = intent.getStringExtra("talker") ?: return
        val content = intent.getStringExtra("content") ?: ""
        val type = intent.getIntExtra("type", 0)
        val createTime = intent.getLongExtra("createTime", 0)
        val isSend = intent.getBooleanExtra("isSend", false)
        val msgSvrId = intent.getLongExtra("msgSvrId", 0)

        android.util.Log.d("wxhook", "MSG from $talker: ${content.take(50)} (type=$type isSend=$isSend)")
        showNotification(context, talker, content, type, isSend)
    }

    private fun showNotification(ctx: Context, talker: String, content: String, type: Int, isSend: Boolean) {
        try {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "wxhook_msg"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val ch = android.app.NotificationChannel(channelId, "wxhook消息", android.app.NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannel(ch)
            }
            val typeDesc = when (type) { 1 -> "文本"; 3 -> "图片"; 34 -> "语音"; 43 -> "视频"; 49 -> "链接"; else -> "type=$type" }
            val dir = if (isSend) "→" else "←"
            val notif = android.app.Notification.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$dir $talker [$typeDesc]")
                .setContentText(content.take(100))
                .setAutoCancel(true)
                .build()
            mgr.notify(System.currentTimeMillis().toInt(), notif)
        } catch (_: Throwable) {}
    }
}