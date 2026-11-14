package com.nous.wxnotes.receive

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nous.wxnotes.MainActivity
import com.nous.wxnotes.NotesApp
import com.nous.wxnotes.R

object Notifications {

    fun importFinished(context: Context, noteId: Long, noteTitle: String, added: Int, skipped: Int) {
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, noteId.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildString {
            append("新增 $added 条")
            if (skipped > 0) append("，跳过重复 $skipped 条")
            append(" → 《$noteTitle》")
        }
        val notification: Notification = NotificationCompat.Builder(context, NotesApp.CHANNEL_IMPORT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("微信消息已保存")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(noteId.toInt(), notification)
        } catch (_: SecurityException) {
            // 没有 POST_NOTIFICATIONS 权限时静默失败
        }
    }
}
