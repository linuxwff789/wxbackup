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

class WeChatHookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        XposedBridge.log("[wxhook] loaded in WeChat (pid=${android.os.Process.myPid()})")

        // Hook 密钥捕获
        KeyCaptureHook.hook(lpparam)

        // Hook 消息拦截
        MessageHook.hook(lpparam)

        // Hook 防撤回
        AntiRecallHook.hook(lpparam)

        // Hook 备份功能
        BackupHook.hook(lpparam)

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
