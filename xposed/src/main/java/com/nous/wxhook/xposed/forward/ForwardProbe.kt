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
                            m.parameterTypes.joinToString { it.name } + ")"
                    )
                    if (m.name == "onCreateMMMenu") {
                        m.parameterTypes.forEach { inspectMenuType(it) }
                    }
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
            param.args.forEach { a -> dumpMsgIfAny("ARG", a) }
            dumpMsgIfAny("OWNER", param.thisObject)
            dumpOwnerFields(key, param.thisObject)
            dumpFields("MENU", param.args.firstOrNull())
            val foundCtx = findChattingContext(param.thisObject)
            XposedBridge.log("$TAG ctx[$key]=${foundCtx?.javaClass?.name}")
            if (method.name == "onCreateMMMenu") {
                inspectMenuInstance(param.args.firstOrNull())
            }
        }

        when (method.name) {
            "onCreateMMMenu" -> {
                val menuObj = param.args.firstOrNull() ?: return
                val msg = param.args.firstOrNull { MessageExtractor.looksLikeMsgInfo(it) }
                    ?: findMsgIn(param.thisObject)
                    ?: findCurrentMsg(param.thisObject)
                    ?: currentMsg
                if (msg != null) currentMsg = msg
                addMenuItem(menuObj, clazz, msg)
            }

            "onMMMenuItemSelected" -> {
                val item = param.args.firstOrNull { it is MenuItem } as? MenuItem
                val title = item?.title?.toString().orEmpty()
                val index = (param.args.getOrNull(1) as? Number)?.toInt()
                if (item?.itemId == ITEM_ID || title == TITLE) {
                    val msg = currentMsg
                        ?: findMsgIn(param.thisObject)
                        ?: findCurrentMsg(param.thisObject)
                        ?: param.args.firstOrNull { MessageExtractor.looksLikeMsgInfo(it) }
                    if (msg != null) {
                        ForwardSender.forward(ctx, msg)
                    } else {
                        XposedBridge.log("$TAG menu clicked but no MsgInfo (owner=${param.thisObject?.javaClass?.name})")
                    }
                    param.result = true
                }
            }
        }
    }

    private fun inspectMenuType(type: Class<*>) {
        try {
            XposedBridge.log(
                "$TAG menuType=${type.name} isMenu=${Menu::class.java.isAssignableFrom(type)} " +
                    "super=${type.superclass?.name} ifaces=${type.interfaces.joinToString { it.name }}"
            )
            val methods = type.declaredMethods
                .filter { it.name.contains("add", true) || it.name.contains("item", true) || it.name.contains("menu", true) }
                .joinToString(", ") { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
            XposedBridge.log("$TAG menuMethods: ${methods.take(1800)}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG inspectMenuType fail: ${e.message}")
        }
    }

    private fun inspectMenuInstance(obj: Any?) {
        if (obj == null) return
        try {
            val methods = obj.javaClass.declaredMethods
                .filter { it.name.contains("add", true) || it.name.contains("item", true) }
                .joinToString(", ") { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
            XposedBridge.log("$TAG menuInstance=${obj.javaClass.name} addMethods: ${methods.take(1800)}")
        } catch (_: Throwable) {
        }
    }

    private fun addMenuItem(menuObj: Any, clazz: Class<*>, msg: Any? = null) {
        // 1. 标准 Menu 接口
        if (menuObj is Menu) {
            try {
                var item = menuObj.findItem(ITEM_ID)
                if (item == null) {
                    item = menuObj.add(Menu.NONE, ITEM_ID, 0, TITLE)
                    XposedBridge.log("$TAG menu item added (Menu) by ${clazz.name} msg=${msg != null}")
                }
                // 直接设置点击监听，避免依赖微信的 id 分发
                if (msg != null && item != null) {
                    item.setOnMenuItemClickListener {
                        XposedBridge.log("$TAG menu item clicked (listener)")
                        ForwardSender.forward(ctx, msg)
                        true
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG Menu.add failed: ${e.message}")
            }
            return
        }
        // 2. 微信自定义菜单：反射查找 add 类方法
        val candidates = listOf(
            Triple("add", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, CharSequence::class.java), arrayOf<Any>(0, ITEM_ID, 0, TITLE)),
            Triple("add", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java), arrayOf<Any>(0, ITEM_ID, 0, TITLE)),
            Triple("add", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, CharSequence::class.java), arrayOf<Any>(ITEM_ID, TITLE)),
            Triple("add", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, String::class.java), arrayOf<Any>(ITEM_ID, TITLE)),
            Triple("addTextOptionMenu", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, String::class.java), arrayOf<Any>(ITEM_ID, TITLE)),
            Triple("addTextOptionMenu", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, CharSequence::class.java, Any::class.java), arrayOfNulls<Any>(3).also { it[0] = ITEM_ID; it[1] = TITLE })
        )
        for ((name, types, args) in candidates) {
            try {
                val m = menuObj.javaClass.getMethod(name, *types)
                m.invoke(menuObj, *args)
                XposedBridge.log("$TAG menu item added via ${name}(${types.joinToString { it.simpleName }})")
                return
            } catch (_: Throwable) {
            }
        }
        // 3. 打印所有方法，便于下轮适配
        val all = menuObj.javaClass.declaredMethods.joinToString(", ") { m ->
            "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
        }
        XposedBridge.log("$TAG menu add failed, methods: ${all.take(1800)}")
    }

    private fun dumpFields(tag: String, obj: Any?) {
        if (obj == null) return
        try {
            val sb = StringBuilder("$TAG $tag ${obj.javaClass.name}: ")
            var c: Class<*>? = obj.javaClass
            var depth = 0
            while (c != null && c != Any::class.java && depth < 4) {
                c.declaredFields.forEach { f ->
                    sb.append(f.name).append(':').append(f.type.simpleName).append(' ')
                }
                c = c.superclass
                depth++
            }
            XposedBridge.log(sb.take(1600).toString())
        } catch (_: Throwable) {
        }
    }

    private fun dumpOwnerFields(key: String, owner: Any?) {
        if (owner == null) return
        val sb = StringBuilder("$TAG OWNER_FIELDS $key -> ${owner.javaClass.name}: ")
        try {
            var c: Class<*>? = owner.javaClass
            var depth = 0
            while (c != null && c != Any::class.java && depth < 3) {
                c.declaredFields.forEach { f ->
                    sb.append(f.name).append(':').append(f.type.simpleName)
                    try {
                        f.isAccessible = true
                        val v = f.get(owner)
                        if (v != null) {
                            sb.append('=').append(v.javaClass.name)
                            if (MessageExtractor.looksLikeMsgInfo(v)) sb.append("<<MSG>>")
                            if (v.javaClass.interfaces.any { it.name.contains("ChattingContext") }) sb.append("<<CTX>>")
                        }
                    } catch (_: Throwable) {
                    }
                    sb.append(' ')
                }
                c = c.superclass
                depth++
            }
        } catch (_: Throwable) {
        }
        XposedBridge.log(sb.take(2000).toString())
    }

    private fun findChattingContext(owner: Any?): Any? = findChattingContextDeep(owner, 0, HashSet())

    private fun findChattingContextDeep(owner: Any?, depth: Int, seen: MutableSet<Int>): Any? {
        if (owner == null || depth > 2) return null
        if (!seen.add(System.identityHashCode(owner))) return null
        val cls = owner.javaClass
        if (cls.name.contains("ChattingContext") ||
            cls.interfaces.any { it.name.contains("ChattingContext") }
        ) return owner
        // 方法
        try {
            cls.methods
                .filter {
                    it.parameterCount == 0 && (it.name == "getChattingContext" || it.name == "getChattingContextRef")
                }
                .forEach { m ->
                    val v = runCatching { m.invoke(owner) }.getOrNull()
                    if (v != null && (v.javaClass.name.contains("ChattingContext") ||
                            v.javaClass.interfaces.any { it.name.contains("ChattingContext") })
                    ) return v
                }
        } catch (_: Throwable) {
        }
        // 字段（递归）
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(owner) ?: continue
                    val found = findChattingContextDeep(v, depth + 1, seen)
                    if (found != null) return found
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun unusedFindChattingContext(owner: Any?): Any? {
        if (owner == null) return null
        if (owner.javaClass.name.contains("ChattingContext")) return owner
        // 方法
        try {
            owner.javaClass.methods
                .filter {
                    it.parameterCount == 0 && (it.name == "getChattingContext" || it.name == "getChattingContextRef")
                }
                .forEach { m ->
                    val v = runCatching { m.invoke(owner) }.getOrNull()
                    if (v != null && v.javaClass.name.contains("ChattingContext")) return v
                }
        } catch (_: Throwable) {
        }
        // 字段
        var c: Class<*>? = owner.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(owner) ?: continue
                    if (v.javaClass.name.contains("ChattingContext")) return v
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun findCurrentMsg(owner: Any?): Any? {
        val c = findChattingContext(owner) ?: owner ?: return null
        val names = listOf("getCurrentMsgInfo", "getMsgInfo", "getCurrentMsg", "getSelectedMsgInfo")
        for (name in names) {
            try {
                val m = c.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
                val v = runCatching { m.invoke(c) }.getOrNull()
                if (MessageExtractor.looksLikeMsgInfo(v)) {
                    XposedBridge.log("$TAG got msg via ${c.javaClass.name}.$name()")
                    return v
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun dumpMsgIfAny(tag: String, obj: Any?) {
        if (obj == null) return
        val found = findMsgIn(obj)
        if (found != null) {
            val dump = MessageExtractor.dump(found)
            dump.lineSequence().chunked(12).forEach { lines ->
                XposedBridge.log("$TAG DUMP[$tag] " + lines.joinToString("\n"))
            }
        }
    }

    private fun findMsgIn(obj: Any?): Any? {
        if (obj == null) return null
        if (MessageExtractor.looksLikeMsgInfo(obj)) return obj
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (MessageExtractor.looksLikeMsgInfo(v)) return v
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
        }
        return null
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
