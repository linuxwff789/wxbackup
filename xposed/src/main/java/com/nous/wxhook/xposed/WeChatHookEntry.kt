package com.nous.wxhook.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.nous.wxhook.xposed.hook.KeyCaptureHook
import com.nous.wxhook.xposed.hook.MessageHook
import com.nous.wxhook.xposed.hook.AntiRecallHook
import com.nous.wxhook.xposed.hook.SettingsEntryHook
import com.nous.wxhook.xposed.hook.BackupHook
import com.nous.wxhook.xposed.hook.KeepAliveHook
import com.nous.wxhook.xposed.forward.ForwardHook

class WeChatHookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        if (lpparam.processName != "com.tencent.mm") return

        XposedBridge.log("[wxhook] loaded in WeChat (pid=${android.os.Process.myPid()})")

        // 尽早 hook WCDB 拿数据库密码（必须在微信打开 DB 之前）
        try {
            com.nous.wxhook.xposed.forward.WxProbe.installDbKeyHook(lpparam.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("[wxhook] installDbKeyHook error: $e")
        }

        // Hook 密钥捕获
        KeyCaptureHook.hook(lpparam)

        // Hook 消息拦截
        MessageHook.hook(lpparam)

        // 尽早 hook MsgInfoStorage 构造，抓 8.0.80 的实例（Dagger 注入，查表拿不到）
        try {
            com.nous.wxhook.xposed.forward.WxClasses.installHooks(
                android.app.AndroidAppHelper.currentApplication(),
                lpparam.classLoader
            )
        } catch (e: Throwable) {
            XposedBridge.log("[wxhook] WxClasses.installHooks error: $e")
        }

        // 转发到笔记 App（长按菜单 / 多选）
        try {
            ForwardHook.hook(lpparam)
            XposedBridge.log("[wxhook] ForwardHook done")
        } catch (e: Exception) {
            XposedBridge.log("[wxhook] ForwardHook error: $e")
        }

        // Hook 防撤回
        AntiRecallHook.hook(lpparam)

        // Hook 备份功能
        BackupHook.hook(lpparam)

        // Hook 保活 + 定时器
        try {
            KeepAliveHook.hook(lpparam)
            XposedBridge.log("[wxhook] KeepAliveHook done")
        } catch (e: Exception) {
            XposedBridge.log("[wxhook] KeepAliveHook error: $e")
        }

        // Hook 设置入口
        XposedBridge.log("[wxhook] calling SettingsEntryHook...")
        try {
            SettingsEntryHook.hook(lpparam)
            XposedBridge.log("[wxhook] SettingsEntryHook done")
        } catch (e: Exception) {
            XposedBridge.log("[wxhook] SettingsEntryHook error: $e")
        }
    }
}
