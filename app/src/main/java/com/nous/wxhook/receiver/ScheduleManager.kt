package com.nous.wxhook.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 基于 AlarmManager 的定时调度管理器。
 * 不依赖微信进程（Xposed），直接使用系统闹钟服务，
 * 进程被杀了也会到点唤醒。
 *
 * 配合 ScheduleReceiver 使用：
 * - ACTION_ALARM_BACKUP  → 触发定时备份
 * - ACTION_ALARM_SYNC   → 触发定时同步
 */
object ScheduleManager {

    private const val TAG = "wxhook:ScheduleMgr"
    private const val REQUEST_BACKUP = 2001
    private const val REQUEST_SYNC = 2002

    const val ACTION_ALARM_BACKUP = "com.nous.wxhook.ALARM_BACKUP"
    const val ACTION_ALARM_SYNC = "com.nous.wxhook.ALARM_SYNC"

    /**
     * 读取配置并设置/更新所有定时闹钟。
     * 在设置页面保存时调用。
     */
    fun updateAll(context: Context) {
        try {
            val cfg = JSONObject(File(context.filesDir, "settings_config.json").readText())

            scheduleAlarm(
                context = context,
                timeStr = cfg.optString("backup_schedule_time", ""),
                intervalDays = cfg.optInt("backup_schedule_interval_days", 1),
                action = ACTION_ALARM_BACKUP,
                requestCode = REQUEST_BACKUP,
                tag = "备份"
            )

            scheduleAlarm(
                context = context,
                timeStr = cfg.optString("sync_schedule_time", ""),
                intervalDays = cfg.optInt("sync_schedule_interval_days", 1),
                action = ACTION_ALARM_SYNC,
                requestCode = REQUEST_SYNC,
                tag = "同步"
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新调度失败", e)
        }
    }

    /**
     * 取消所有定时闹钟。
     */
    fun cancelAll(context: Context) {
        cancelAlarm(context, REQUEST_BACKUP)
        cancelAlarm(context, REQUEST_SYNC)
        Log.i(TAG, "所有定时已取消")
    }

    // ── 内部实现 ──

    private fun scheduleAlarm(
        context: Context,
        timeStr: String,
        intervalDays: Int,
        action: String,
        requestCode: Int,
        tag: String,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 先取消旧闹钟
        cancelAlarm(context, requestCode)

        // 时间为空 → 关闭
        if (timeStr.isBlank()) {
            Log.d(TAG, "$tag 定时未设置，已取消")
            return
        }

        // 解析 HH:mm
        val parts = timeStr.split(":")
        if (parts.size < 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        // 构建 Intent + PendingIntent
        val intent = Intent(action).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        // 计算首次触发时间：今天的 HH:mm，如果已过则明天
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var triggerAt = cal.timeInMillis
        if (triggerAt <= now) {
            triggerAt += 24 * 60 * 60 * 1000L  // 明天
        }

        // 设置重复闹钟（每天）
        val intervalMs = intervalDays * 24L * 60 * 60 * 1000L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6+ 用 setExactAndAllowWhileIdle 保证到点触发（省电模式下也可用）
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            // 注意：setRepeating 在 M+ 上不精确，所以我们只设第一个闹钟，
            // 触发后在 ScheduleReceiver 中重新设置下一个
            Log.i(TAG, "$tag 定时已设: ${"%02d:%02d".format(hour, minute)}, 首次 ${java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerAt))}")
        } else {
            // 旧版本可以用 setRepeating
            alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, intervalMs, pi)
            Log.i(TAG, "$tag 定时已设(重复): ${"%02d:%02d".format(hour, minute)}, 间隔${intervalDays}天")
        }
    }

    private fun cancelAlarm(context: Context, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context.applicationContext.packageName)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pi?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }
}
