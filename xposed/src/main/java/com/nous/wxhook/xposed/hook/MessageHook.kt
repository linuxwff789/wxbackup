package com.nous.wxhook.xposed.hook

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MessageHook {

    private const val TAG = "[wxhook:Msg]"
    private var appContext: Context? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Capture context from Application.attach
        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    appContext = param.args[0] as? Context
                }
            }
        )

        hookEventBus(loader)
        hookNormsg(loader)
        hookMsgInfo(loader)
    }

    private fun hookEventBus(loader: ClassLoader) {
        // Hook SendMsgEvent constructors
        try {
            val sendMsgClass = XposedHelpers.findClass(
                "com.tencent.mm.autogen.events.SendMsgEvent", loader
            )
            for (constructor in sendMsgClass.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val event = param.thisObject
                            logFields(event, "SEND")
                            val talker = extractField(event, "talker")
                            val content = extractField(event, "content")
                            val type = extractInt(event, "type")
                            val createTime = extractLong(event, "createTime")
                            broadcastMessage(talker, content, type, createTime, true)
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG SendMsgEvent error: ${e.message}")
                        }
                    }
                })
            }
            XposedBridge.log("$TAG hooked SendMsgEvent")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG SendMsgEvent not found: ${e.message}")
        }

        // Hook ReceiveMsgEvent constructors
        try {
            val recvMsgClass = XposedHelpers.findClass(
                "com.tencent.mm.autogen.events.ReceiveMsgEvent", loader
            )
            for (constructor in recvMsgClass.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val event = param.thisObject
                            logFields(event, "RECV")
                            val talker = extractField(event, "talker")
                            val content = extractField(event, "content")
                            val type = extractInt(event, "type")
                            val createTime = extractLong(event, "createTime")
                            broadcastMessage(talker, content, type, createTime, false)
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG ReceiveMsgEvent error: ${e.message}")
                        }
                    }
                })
            }
            XposedBridge.log("$TAG hooked ReceiveMsgEvent")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG ReceiveMsgEvent not found: ${e.message}")
        }
    }

    private fun hookNormsg(loader: ClassLoader) {
        try {
            val gClass = XposedHelpers.findClass("com.tencent.mm.normsg.g", loader)
            for (constructor in gClass.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val obj = param.thisObject
                            logFields(obj, "normsg.g")
                        } catch (_: Throwable) {}
                    }
                })
            }
            XposedBridge.log("$TAG hooked normsg.g")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG normsg.g not found: ${e.message}")
        }
    }

    private fun hookMsgInfo(loader: ClassLoader) {
        try {
            val msgInfoClass = XposedHelpers.findClass("com.tencent.mm.storage.MsgInfo", loader)
            XposedBridge.log("$TAG found MsgInfo, hooking insert/add/send")
            for (method in msgInfoClass.declaredMethods) {
                val name = method.name
                if (name.contains("insert", true) || name.contains("add", true) || name.contains("send", true)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            XposedBridge.log("$TAG MsgInfo.$name() -> ${param.result}")
                        }
                    })
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG MsgInfo not found (Tinker, expected)")
        }
    }

    // --- Helpers ---

    private fun logFields(obj: Any, tag: String) {
        try {
            val sb = StringBuilder("$TAG $tag: ")
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                val v = field.get(obj) ?: continue
                val t = field.type.simpleName
                when (t) {
                    "String" -> sb.append("${field.name}=\"$v\" ")
                    "long", "int", "boolean" -> sb.append("${field.name}=$v ")
                    "[B" -> {
                        val bytes = v as ByteArray
                        sb.append("${field.name}=hex(${bytes.take(16).joinToString("") { "%02x".format(it) }}) ")
                    }
                    else -> {
                        // Try nested
                        try {
                            for (nf in v.javaClass.declaredFields) {
                                nf.isAccessible = true
                                val nv = nf.get(v) ?: continue
                                val nt = nf.type.simpleName
                                when (nt) {
                                    "String" -> sb.append("${field.name}.${nf.name}=\"${nv}\" ")
                                    "long", "int", "boolean" -> sb.append("${field.name}.${nf.name}=$nv ")
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            XposedBridge.log(sb.toString())
        } catch (_: Throwable) {}
    }

    private fun extractField(obj: Any, name: String): String {
        return try {
            val field = obj.javaClass.declaredFields.find { it.name == name || it.name.contains(name, true) }
            field?.isAccessible = true
            field?.get(obj)?.toString() ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun extractInt(obj: Any, name: String): Int {
        return try {
            val field = obj.javaClass.declaredFields.find { it.name == name || it.name.contains(name, true) }
            field?.isAccessible = true
            (field?.get(obj) as? Int) ?: 0
        } catch (_: Throwable) { 0 }
    }

    private fun extractLong(obj: Any, name: String): Long {
        return try {
            val field = obj.javaClass.declaredFields.find { it.name == name || it.name.contains(name, true) }
            field?.isAccessible = true
            (field?.get(obj) as? Long) ?: 0L
        } catch (_: Throwable) { 0L }
    }

    private fun broadcastMessage(talker: String, content: String, type: Int, createTime: Long, isSend: Boolean) {
        try {
            val ctx = appContext ?: return
            val intent = Intent("com.nous.wxhook.MESSAGE").apply {
                putExtra("talker", talker)
                putExtra("content", content)
                putExtra("type", type)
                putExtra("createTime", createTime)
                putExtra("isSend", isSend)
            }
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }
}