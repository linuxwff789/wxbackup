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
                tag = "备份",
                // 「全量备份」开关：以前只镜像到 /data/local/tmp 给 Xposed 看，触发时永远走增量
                fullBackup = cfg.optBoolean("backup_full_enabled", false),
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
        fullBackup: Boolean = false,
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
        // 定时备份的类型（全量/增量）由设置页开关决定，ScheduleReceiver 读这个 extra
        if (action == ACTION_ALARM_BACKUP) {
            intent.putExtra("type", if (fullBackup) "full" else "incremental")
        }
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

        // setAlarmClock：系统最高优先级闹钟，精确到分钟，Doze 深休眠也准时，
        // 且闹钟触发广播带前台服务启动豁免（FGS）——setRepeating 在凌晨深度休眠
        // 时会被 Doze 延迟、且非精确闹钟广播无 FGS 豁免，备份服务起不来
        // （2026-08-27 实测 02:00 自动备份未执行）。
        // 它是一次性的，靠 ScheduleReceiver 里 try/finally 的 updateAll 重设链续期。
        //
        // Android 12+ 起 setAlarmClock 需要 SCHEDULE_EXACT_ALARM（或 USE_EXACT_ALARM）：
        // 声明了权限也不够，targetSdk 33+ 默认**不授予**，要在系统里开「闹钟与提醒」，
        // 否则每次 setAlarmClock 都抛 SecurityException，整段 updateAll 失败 →
        // 闹钟永远是空的（2026-09-18 实测：SecurityException 后 dumpsys alarm 里没有
        // 任何 wxhook 闹钟，用户看到的就是"又没有自动备份"）。
        // 这里做降级：没有精确闹钟权限时用 setAndAllowWhileIdle（非精确，可能晚几分钟），
        // 保证"自动备份会跑"这件事不依赖于用户去开特殊权限。
        val canExact = try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: Throwable) {
            true // 旧版本没有这个 API
        }
        if (canExact) {
            try {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pi)
                Log.i(
                    TAG,
                    "$tag 定时已设(setAlarmClock): ${"%02d:%02d".format(hour, minute)}, 首次 ${
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerAt))
                    }"
                )
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "$tag 精确闹钟被拒(${e.message})，降级为非精确闹钟")
            }
        } else {
            Log.w(TAG, "$tag 无「闹钟与提醒」权限，降级为非精确闹钟（可能延迟几分钟）")
        }
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.i(
                TAG,
                "$tag 定时已设(setAndAllowWhileIdle, 非精确): ${"%02d:%02d".format(hour, minute)}, 首次 ${
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerAt))
                }"
            )
        } catch (e: Exception) {
            Log.e(TAG, "$tag 定时设置失败", e)
        }
    }

    /** 是否具备精确闹钟权限（供 UI 引导用户去系统里开「闹钟与提醒」）。 */
    fun canScheduleExactAlarms(context: Context): Boolean = try {
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() ?: false
    } catch (_: Throwable) {
        true
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
