package com.nous.wxhook.xposed.forward

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import dalvik.system.DexFile
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 微信长按菜单探针 + 注入点。
 *
 * 微信 8.0.79 的菜单实现类名被混淆，无法靠固定类名 hook。
 * 这里在运行时枚举 dex 里的类，找出声明了 onCreateMMMenu /
 * onMMMenuItemSelected 的实现类，再统一 hook：
 *   - onCreateMMMenu：往菜单里追加「保存到笔记」
 *   - onMMMenuItemSelected：拦截点击，把当前 MsgInfo 转发给笔记 App
 *
 * 首次遇到每个类时把参数对象 dump 到 LSPosed 日志，用于适配新版本。
 */
object ForwardProbe {

    private const val TAG = "[wxhook:Probe]"
    private const val ITEM_ID = 0x7F0E0001
    private const val TITLE = "保存到笔记"

    private val METHOD_NAMES = setOf(
        "onCreateMMMenu",
        "onMMMenuItemSelected",
        "onMenuMultiTaskSelected",
        "doAddCustomMenuItems"
    )

    private val dumped = HashSet<String>()
    private var currentMsg: Any? = null
    private var ctx: Context? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam, context: Context?) {
        ctx = context
        val loader = lpparam.classLoader
        val t0 = System.currentTimeMillis()
        val names = scanNames(loader)
        XposedBridge.log("$TAG dex classes=${names.size} scan=${System.currentTimeMillis() - t0}ms")

        val candidates = names.filter { n ->
            n.startsWith("com.tencent.mm.ui.chatting") ||
                (n.startsWith("com.tencent.mm.ui.base") && n.contains("Menu", true)) ||
                n.contains("ChattingMenu") ||
                n.contains("MenuItem")
        }
        XposedBridge.log("$TAG candidate classes=${candidates.size}")

        var hookedClasses = 0
        var hookedMethods = 0
        candidates.forEach { name ->
            val c = try {
                Class.forName(name, false, loader)
            } catch (_: Throwable) {
                null
            } ?: return@forEach
            val methods = try {
                c.declaredMethods
            } catch (_: Throwable) {
                return@forEach
            }
            val interesting = methods.filter { it.name in METHOD_NAMES }
            if (interesting.isEmpty()) return@forEach
            hookedClasses++
            interesting.forEach { m ->
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            handle(c, m, param)
                        }
                    })
                    hookedMethods++
                    XposedBridge.log(
                        "$TAG hooked ${c.name}.${m.name}(" +
                            m.parameterTypes.joinToString { it.simpleName } + ")"
                    )
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG hook fail ${c.name}.${m.name}: ${e.message}")
                }
            }
        }
        XposedBridge.log("$TAG done classes=$hookedClasses methods=$hookedMethods")
    }

    private fun handle(clazz: Class<*>, method: Method, param: XC_MethodHook.MethodHookParam) {
        val key = "${clazz.name}#${method.name}"
        if (dumped.add(key)) {
            XposedBridge.log(
                "$TAG HIT $key args=" + param.args.joinToString(", ") { it?.javaClass?.name ?: "null" }
            )
            param.args.forEach { a ->
                if (MessageExtractor.looksLikeMsgInfo(a)) {
                    val dump = MessageExtractor.dump(a)
                    dump.lineSequence().chunked(12).forEach { lines ->
                        XposedBridge.log("$TAG DUMP " + lines.joinToString("\n"))
                    }
                }
            }
        }

        when (method.name) {
            "onCreateMMMenu" -> {
                val menu = param.args.firstOrNull { it is Menu } as? Menu ?: return
                val msg = param.args.firstOrNull { MessageExtractor.looksLikeMsgInfo(it) } ?: currentMsg
                if (msg != null) {
                    currentMsg = msg
                    if (menu.findItem(ITEM_ID) == null) {
                        try {
                            menu.add(Menu.NONE, ITEM_ID, 0, TITLE)
                            XposedBridge.log("$TAG menu item added by ${clazz.name}")
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG menu.add failed: ${e.message}")
                        }
                    }
                }
            }

            "onMMMenuItemSelected" -> {
                val item = param.args.firstOrNull { it is MenuItem } as? MenuItem ?: return
                val title = item.title?.toString().orEmpty()
                if (item.itemId == ITEM_ID || title == TITLE) {
                    val msg = currentMsg
                        ?: param.args.firstOrNull { MessageExtractor.looksLikeMsgInfo(it) }
                    if (msg != null) {
                        ForwardSender.forward(ctx, msg)
                    } else {
                        XposedBridge.log("$TAG menu clicked but no MsgInfo")
                    }
                    param.result = true
                }
            }
        }
    }

    /** 枚举 BaseDexClassLoader 里所有 dex 的类名。 */
    private fun scanNames(loader: ClassLoader): List<String> {
        val out = ArrayList<String>()
        try {
            val pathList = XposedHelpers.getObjectField(loader, "pathList") ?: return out
            val elements = XposedHelpers.getObjectField(pathList, "dexElements") as? Array<*> ?: return out
            for (el in elements) {
                val dexFile = try {
                    XposedHelpers.getObjectField(el, "dexFile") as? DexFile
                } catch (_: Throwable) {
                    null
                } ?: continue
                try {
                    val en = dexFile.entries()
                    while (en.hasMoreElements()) {
                        val n = en.nextElement()
                        if (n.startsWith("com.tencent.mm") && !n.startsWith("[")) out.add(n)
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG scan failed: ${e.message}")
        }
        return out
    }
}
