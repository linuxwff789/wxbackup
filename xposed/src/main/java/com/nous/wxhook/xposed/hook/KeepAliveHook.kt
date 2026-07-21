package com.nous.wxhook.xposed.hook

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 保活 + 定时备份/同步 Hook。
 *
 * 利用微信进程永不杀死的特性，在微信进程内运行定时器，
 * 定期向 wxhook App 发送心跳广播，触发定时备份和同步。
 *
 * 工作方式：
 *   1. 每 2 分钟发一次 PING 广播 → 保活（App 被杀了会拉起来）
 *   2. 每 30 分钟检查一次定时备份配置
 *   3. 每 15 分钟检查一次定时同步配置
 */
object KeepAliveHook {

    private const val TAG = "[wxhook:KeepAlive]"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private const val SCHEDULE_FILE = "/data/local/tmp/wxhook_schedule.json"

    // ── 广播 Action ──
    private const val ACTION_PING = "com.nous.wxhook.KEEPALIVE_PING"
    const val ACTION_SCHEDULED_BACKUP = "com.nous.wxhook.SCHEDULED_BACKUP"
    const val ACTION_SCHEDULED_SYNC = "com.nous.wxhook.SCHEDULED_SYNC"

    // ── Extra ──
    const val EXTRA_INTERVAL_MIN = "interval_min"

    private var handler: Handler? = null
    private var ctx: Context? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as? Context ?: return
                    ctx = context

                    // 微信启动后开始定时器
                    val app = context as android.app.Application
                    app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(activity: android.app.Activity, bundle: android.os.Bundle?) {
                            startScheduler(context)
                        }
                        override fun onActivityStarted(a: android.app.Activity) {}
                        override fun onActivityResumed(a: android.app.Activity) {}
                        override fun onActivityPaused(a: android.app.Activity) {}
                        override fun onActivityStopped(a: android.app.Activity) {}
                        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                        override fun onActivityDestroyed(a: android.app.Activity) {}
                    })
                }
            }
        )
    }

    private fun startScheduler(context: Context) {
        if (handler != null) return  // 防止重复启动

        // 延迟 10 秒启动，等微信完全加载
        handler = Handler(Looper.getMainLooper())
        handler?.postDelayed(object : Runnable {
            override fun run() {
                tick(context)
                handler?.postDelayed(this, 2 * 60 * 1000L) // 每 2 分钟一次
            }
        }, 10 * 1000L)

        XposedBridge.log("$TAG 定时器已启动（间隔 2 分钟）")
    }

    private fun tick(context: Context) {
        // 1. 发 PING（保活 + 触发 App 写 /data/local/tmp/ 下的配置）
        try {
            val pingIntent = Intent(ACTION_PING).apply { setPackage(WXHOOK_PKG) }
            context.sendBroadcast(pingIntent)
        } catch (e: Exception) {
            XposedBridge.log("$TAG PING 失败: ${e.message}")
        }

        // 2. 从 /data/local/tmp/ 读取定时配置（世界可读，免 root）
        val schedule = readScheduleFile() ?: return

        try {
            // 检查定时备份
            checkSchedule(context, schedule, "backup_schedule_time", "backup_schedule_interval_days",
                "last_backup_time", "backup_full_enabled", ACTION_SCHEDULED_BACKUP)

            // 检查定时同步
            checkSchedule(context, schedule, "sync_schedule_time", "sync_schedule_interval_days",
                "last_sync_time", null, ACTION_SCHEDULED_SYNC)

        } catch (e: Exception) {
            XposedBridge.log("$TAG 配置解析失败: ${e.message}")
        }
    }

    /**
     * 检查是否到了执行时间。
     * 规则：
     *   - scheduleTime 为空 → 跳过
     *   - lastTime 为 0（从未执行）→ 到点就触发
     *   - 否则判断：当前时间 >= 今天 scheduleTime，且距上次执行 >= intervalDays 天
     */
    private fun checkSchedule(
        context: Context,
        schedule: org.json.JSONObject,
        timeKey: String,
        intervalKey: String,
        lastKey: String,
        fullKey: String?,
        action: String,
    ) {
        val timeStr = schedule.optString(timeKey, "").trim()
        if (timeStr.isBlank()) return  // 未配置，跳过

        val intervalDays = schedule.optInt(intervalKey, 1).coerceAtLeast(1)
        val lastTime = schedule.optLong(lastKey, 0L)
        val now = System.currentTimeMillis()

        // 解析 HH:MM
        val parts = timeStr.split(":")
        if (parts.size < 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        // 计算今天计划时间点（毫秒）
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todaySchedule = cal.timeInMillis

        // 还没到今天的时间点 → 不触发
        if (now < todaySchedule) return

        // 从未执行过 → 触发
        if (lastTime == 0L) {
            doTrigger(context, action, fullKey, schedule)
            return
        }

        // 已经执行过了且距上次不足 intervalDays → 跳过
        val intervalMs = intervalDays * 86400_000L
        if (now - lastTime < intervalMs) return

        // 确定触发
        doTrigger(context, action, fullKey, schedule)
    }

    private fun doTrigger(
        context: Context,
        action: String,
        fullKey: String?,
        schedule: org.json.JSONObject,
    ) {
        if (action == ACTION_SCHEDULED_BACKUP) {
            val backupFull = fullKey != null && schedule.optBoolean(fullKey, false)
            val type = if (backupFull) "full" else "incremental"
            XposedBridge.log("$TAG 触发定时备份（$type）")
            context.sendBroadcast(Intent(action).apply {
                setPackage(WXHOOK_PKG); putExtra("type", type)
            })
        } else {
            XposedBridge.log("$TAG 触发定时同步")
            context.sendBroadcast(Intent(action).apply { setPackage(WXHOOK_PKG) })
        }
    }

    /** 读取 /data/local/tmp/wxhook_schedule.json（世界可读，免 root） */
    private fun readScheduleFile(): org.json.JSONObject? {
        return try {
            val content = java.io.File(SCHEDULE_FILE).readText().trim()
            if (content.isNotBlank()) org.json.JSONObject(content) else null
        } catch (_: Exception) { null }
    }


}
