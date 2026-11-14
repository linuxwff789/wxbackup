package com.nous.wxhook.xposed.forward

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 转发模块入口：拿 Application Context → 启动本地媒体服务 → 安装菜单 hook。
 */
object ForwardHook {

    private const val TAG = "[wxhook:FwdHook]"

    @Volatile
    private var context: Context? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(
            Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return
                    context = ctx
                    MediaServer.ensureStarted()
                    // 等微信主线程把聊天相关类加载完
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            ForwardProbe.hook(lpparam, context)
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG probe error: $e")
                        }
                    }, 2500)
                }
            }
        )
    }

    fun appContext(): Context? = context
}
