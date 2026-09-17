package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nous.wxhook.root.RootGateways
import java.io.File

/**
 * 接收 Xposed 保活模块发来的定时备份/同步广播。
 *
 * Xposed 模块（微信进程）会定时发送 3 种广播：
 * - KEEPALIVE_PING: 纯保活
 * - SCHEDULED_BACKUP: 定时备份触发
 * - SCHEDULED_SYNC: 定时同步触发
 *
 * 每次收到 PING 后，将定时配置写入 /data/local/tmp/wxhook_schedule.json
 *（世界可读，供 Xposed 模块免 root 读取）
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "wxhook:ScheduleRcvr"
        private const val ACTION_PING = "com.nous.wxhook.KEEPALIVE_PING"
        private const val ACTION_BOOT = "android.intent.action.BOOT_COMPLETED"
        private const val ACTION_LOCKED_BOOT = "android.intent.action.LOCKED_BOOT_COMPLETED"
        private const val ACTION_PKG_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_SCHEDULED_BACKUP = "com.nous.wxhook.SCHEDULED_BACKUP"
        private const val ACTION_SCHEDULED_SYNC = "com.nous.wxhook.SCHEDULED_SYNC"
        private const val SCHEDULE_FILE = "/data/local/tmp/wxhook_schedule.json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PING -> handlePing(context)
            ACTION_BOOT, ACTION_LOCKED_BOOT, ACTION_PKG_REPLACED, ACTION_QUICKBOOT,
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                // 开机 / 覆盖安装后 AlarmManager 里的闹钟已经被清空，必须重新挂上，
                // 否则"定时备份"会在用户毫无察觉的情况下永久消失
                Log.i(TAG, "系统事件重新挂定时: ${intent.action}")
                try {
                    ScheduleManager.updateAll(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "重新挂定时失败", t)
                }
                writeSchedule(context)
            }
            ACTION_SCHEDULED_BACKUP -> handleScheduledBackup(context, intent)
            ACTION_SCHEDULED_SYNC -> handleScheduledSync(context)
            ScheduleManager.ACTION_ALARM_BACKUP -> {
                Log.i(TAG, "闹钟触发: 定时备份")
                try {
                    handleScheduledBackup(context, intent)
                } catch (t: Throwable) {
                    // 前台服务启动被拒(ForegroundServiceStartNotAllowedException)等
                    // 异常不得让 receiver 崩溃——否则下面的 updateAll 不会执行，闹钟链断裂
                    Log.e(TAG, "定时备份任务异常", t)
                } finally {
                    // 无论任务成败都重设闹钟，保证长期调度不断链
                    ScheduleManager.updateAll(context)
                }
            }
            ScheduleManager.ACTION_ALARM_SYNC -> {
                Log.i(TAG, "闹钟触发: 定时同步")
                try {
                    handleScheduledSync(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "定时同步任务异常", t)
                } finally {
                    ScheduleManager.updateAll(context)
                }
            }
        }
    }

    private fun handlePing(ctx: Context) {
        Log.d(TAG, "PING 收到")
        // 每次 PING 都把定时配置写到 /data/local/tmp/ 下供 Xposed 免 root 读取
        writeSchedule(ctx)
        // Xposed 模块每 2 分钟 PING 一次（微信在跑就一定会来）：顺手重挂一次闹钟，
        // 这样即使闹钟被系统/装包清掉，最多 2 分钟内自愈，不用用户手动开一次 App
        try {
            ScheduleManager.updateAll(ctx)
        } catch (_: Throwable) {
        }
    }

    private fun handleScheduledBackup(ctx: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: "incremental"
        Log.i(TAG, "定时备份触发: type=$type")
        com.nous.wxhook.service.BackupService.start(ctx, type != "full")
        // 更新执行时间
        writeSchedule(ctx, extraUpdates = mapOf("last_backup_time" to System.currentTimeMillis()))
    }

    private fun handleScheduledSync(ctx: Context) {
        Log.i(TAG, "定时同步触发")
        try {
            val cfg = org.json.JSONObject(File(ctx.filesDir, "settings_config.json").readText())
            if (cfg.optString("sync_driver", "").isBlank()) { Log.d(TAG, "同步未配置，跳过"); return }
        } catch (_: Exception) {}
        com.nous.wxhook.service.SyncService.start(ctx)
        writeSchedule(ctx, extraUpdates = mapOf("last_sync_time" to System.currentTimeMillis()))
    }

    /**
     * 读取 App 配置 + 写入 /data/local/tmp/ 供 Xposed 模块读取。
     * 这样 Xposed 模块不需要 root 也能获取定时配置。
     */
    private fun writeSchedule(ctx: Context, extraUpdates: Map<String, Long> = emptyMap()) {
        try {
            val cfg = runCatching {
                org.json.JSONObject(File(ctx.filesDir, "settings_config.json").readText())
            }.getOrDefault(org.json.JSONObject())

            // 只提取定时相关的字段
            val schedule = org.json.JSONObject().apply {
                // 定时备份
                put("backup_schedule_time", cfg.optString("backup_schedule_time", ""))
                put("backup_schedule_interval_days", cfg.optInt("backup_schedule_interval_days", 1))
                put("backup_full_enabled", cfg.optBoolean("backup_full_enabled", false))
                // 定时同步
                put("sync_schedule_time", cfg.optString("sync_schedule_time", ""))
                put("sync_schedule_interval_days", cfg.optInt("sync_schedule_interval_days", 1))
                // 上次执行时间
                put("last_backup_time", cfg.optLong("last_backup_time", 0L))
                put("last_sync_time", cfg.optLong("last_sync_time", 0L))
                // 用 extraUpdates 覆盖最新执行时间
                for ((k, v) in extraUpdates) put(k, v)
            }

            // 写入 /data/local/tmp/（世界可读，不需要 root）
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RootGateways.run("cat > $SCHEDULE_FILE << 'SCHED_EOF'\n${schedule.toString()}\nSCHED_EOF")
                    RootGateways.run("chmod 644 $SCHEDULE_FILE")
                }
            }
        } catch (_: Exception) {}
    }
}
