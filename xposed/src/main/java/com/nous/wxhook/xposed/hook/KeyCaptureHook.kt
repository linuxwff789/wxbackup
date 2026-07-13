package com.nous.wxhook.xposed.hook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private const val PROVIDER_URI = "content://com.nous.wxhook.provider/key"
    private var keyCaptured = false

    private external fun nativeHello(): String
    private external fun nativeInstallHooks(): String

    fun hook(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        de.robv.android.xposed.XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // Load native library
                    try {
                        val moduleInfo = ctx.packageManager.getApplicationInfo("com.nous.wxhook.xposed", 0)
                        val loadPath = moduleInfo.nativeLibraryDir + "/libwcdbhook.so"
                        if (File(loadPath).exists()) {
                            System.load(loadPath)
                            XposedBridge.log("$TAG NATIVE loaded: ${nativeHello()}")
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG NATIVE load failed: ${e.message}")
                    }

                    // Hook setCipherKey
                    try {
                        val dbClass = ctx.classLoader!!.loadClass("com.tencent.wcdb.core.Database")
                        var hooked = 0
                        for (m in dbClass.declaredMethods) {
                            if (m.name == "setCipherKey") {
                                de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                                    override fun beforeHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                                        if (keyCaptured) return
                                        try {
                                            val args = param.args
                                            val keyHex = if (args.size >= 1 && args[0] is ByteArray) {
                                                (args[0] as ByteArray).joinToString("") { "%02x".format(it) }
                                            } else "null"
                                            val pageSize = args.getOrNull(1)?.toString() ?: "?"
                                            val version = args.getOrNull(2)?.toString() ?: "?"

                                            pushKey(ctx, keyHex, pageSize, version)
                                            keyCaptured = true
                                        } catch (e: Throwable) {
                                            XposedBridge.log("$TAG ERR ${e.message}")
                                        }
                                    }
                                })
                                hooked++
                            }
                        }
                        XposedBridge.log("$TAG Java hooks: $hooked overloads")
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG Database class not found: ${e.message}")
                    }
                }
            }
        )
    }

    private fun pushKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        // Write to .wechat_key
        try {
            File("/data/local/tmp/.wechat_key").writeText(
                "key=$keyHex\npageSize=$pageSize\nversion=$version\ntime=$now\n"
            )
            XposedBridge.log("$TAG wrote .wechat_key")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG write .wechat_key failed: ${e.message}")
        }

        // ContentProvider
        try {
            val values = ContentValues().apply {
                put("key", keyHex)
                put("page_size", pageSize)
                put("version", version)
                put("time", now)
            }
            ctx.contentResolver.insert(Uri.parse(PROVIDER_URI), values)
            XposedBridge.log("$TAG key pushed via ContentProvider")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG ContentProvider failed: ${e.message}")
        }

        XposedBridge.log("$TAG KEY_CAPTURED key=$keyHex pageSize=$pageSize version=$version")
    }

    fun getLastKey(): String? = if (keyCaptured) "captured" else null
}
