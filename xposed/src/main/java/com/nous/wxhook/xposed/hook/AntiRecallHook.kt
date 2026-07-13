package com.nous.wxhook.xposed.hook

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AntiRecallHook {

    private const val TAG = "[wxhook:AntiRecall]"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Strategy 1: Try known class names
        val candidates = listOf(
            "com.tencent.mm.plugin.messenger.foundation.a.a",
            "com.tencent.mm.plugin.messenger.foundation.a.s",
            "com.tencent.mm.plugin.messenger.foundation.a.y",
            "com.tencent.mm.plugin.messenger.foundation.b.a",
        )

        var found = false
        for (clsName in candidates) {
            try {
                val clazz = XposedHelpers.findClass(clsName, loader)
                hookRecallMethods(clazz, clsName)
                found = true
                break
            } catch (e: Throwable) {
                // Class not found, try next
            }
        }

        // Strategy 2: Search by method pattern if no class found
        if (!found) {
            XposedBridge.log("$TAG known classes not found, searching by pattern...")
            searchAndHookRecall(loader)
        }
    }

    private fun hookRecallMethods(clazz: Class<*>, className: String) {
        for (method in clazz.declaredMethods) {
            if (method.name.contains("revoke", ignoreCase = true) ||
                method.name.contains("recall", ignoreCase = true)) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG blocked recall: ${className}.${method.name}")
                        param.result = null
                    }
                })
                XposedBridge.log("$TAG hooked: ${className}.${method.name}")
            }
        }
    }

    private fun searchAndHookRecall(loader: ClassLoader) {
        // Search through loaded classes for recall/revoke methods
        val recallClasses = mutableListOf<String>()

        try {
            // Use Java.enumerateLoadedClasses equivalent via Xposed
            // Hook the event bus for RevokeMsgEvent instead
            val revokeEvent = XposedHelpers.findClass(
                "com.tencent.mm.autogen.events.RevokeMsgEvent", loader
            )
            // Block the revoke event by hooking its constructor
            for (constructor in revokeEvent.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG blocked RevokeMsgEvent")
                        // Don't call original - block the revoke
                        param.result = null
                    }
                })
            }
            XposedBridge.log("$TAG hooked RevokeMsgEvent to block recall")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG RevokeMsgEvent not found: ${e.message}")
        }

        // Also try RevokeNativeMsgEvent
        try {
            val revokeNative = XposedHelpers.findClass(
                "com.tencent.mm.autogen.events.RevokeNativeMsgEvent", loader
            )
            for (constructor in revokeNative.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG blocked RevokeNativeMsgEvent")
                        param.result = null
                    }
                })
            }
            XposedBridge.log("$TAG hooked RevokeNativeMsgEvent")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG RevokeNativeMsgEvent not found: ${e.message}")
        }
    }
}
