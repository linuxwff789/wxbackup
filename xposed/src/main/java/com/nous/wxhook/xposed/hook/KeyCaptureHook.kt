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
    private const val PROVIDER_URI = "content://com.nous.wxhook.db_provider/key"
    private var keyCaptured = false

    fun hook(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        de.robv.android.xposed.XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // 注：原 libwcdbhook.so 的 native hook（inline hook sqlite3_rekey_v2）已移除：
                    // 1) 用 packageManager.getApplicationInfo 查模块包在 Android 11+ 被包可见性拦截；
                    // 2) 该 so 导出的是旧包名 Java_com_nous_wechatxposed_* 符号，与现包名不匹配；
                    // 3) 源码不在仓库（仅预编译 so），无法重编。
                    // WCDB 的 setCipherKey(Java 层) 已能稳定拿到密码，无需 native 兜底。

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

        // Write to .wechat_key in WeChat's own data dir (accessed via su later)
        try {
            File(ctx.filesDir, ".wechat_key").writeText(
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
