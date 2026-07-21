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
                        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle?) {}
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
            val backupIntervalMin = schedule.optInt("backup_interval_min", 0)
            val backupFull = schedule.optBoolean("backup_full_enabled", false)
            if (backupIntervalMin > 0) {
                val lastBackup = schedule.optLong("last_backup_time", 0L)
                if (lastBackup == 0L || System.currentTimeMillis() - lastBackup >= backupIntervalMin * 60_000L) {
                    val type = if (backupFull) "full" else "incremental"
                    XposedBridge.log("$TAG 触发定时备份（$type）")
                    context.sendBroadcast(Intent(ACTION_SCHEDULED_BACKUP).apply {
                        setPackage(WXHOOK_PKG); putExtra("type", type)
                    })
                }
            }

            // 检查定时同步
            val syncIntervalMin = schedule.optInt("sync_interval_min", 0)
            if (syncIntervalMin > 0) {
                val lastSync = schedule.optLong("last_sync_time", 0L)
                if (lastSync == 0L || System.currentTimeMillis() - lastSync >= syncIntervalMin * 60_000L) {
                    XposedBridge.log("$TAG 触发定时同步")
                    context.sendBroadcast(Intent(ACTION_SCHEDULED_SYNC).apply { setPackage(WXHOOK_PKG) })
                }
            }

        } catch (e: Exception) {
            XposedBridge.log("$TAG 配置解析失败: ${e.message}")
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
