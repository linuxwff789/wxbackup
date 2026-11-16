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

    private fun hookImageDownload(loader: ClassLoader) {
        try {
            val l0 = de.robv.android.xposed.XposedHelpers.findClass("v61.l0", loader)
            XposedBridge.hookAllMethods(l0, "W2", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = param.result?.let {
                        runCatching { de.robv.android.xposed.XposedHelpers.getLongField(it, "f479445a") }
                            .getOrDefault(-1L)
                    } ?: -1L
                    XposedBridge.log("$TAG W2 hook talker=${param.args.getOrNull(0)} msgId=${param.args.getOrNull(1)} imgId=$id")
                }
            })
            XposedBridge.hookAllMethods(l0, "k3", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = param.result?.let {
                        runCatching { de.robv.android.xposed.XposedHelpers.getLongField(it, "f479445a") }
                            .getOrDefault(-1L)
                    } ?: -1L
                    XposedBridge.log("$TAG k3 hook talker=${param.args.getOrNull(0)} svr=${param.args.getOrNull(1)} imgId=$id")
                }
            })
            val ui = de.robv.android.xposed.XposedHelpers.findClass("com.tencent.mm.ui.chatting.ImageDownloadUI", loader)
            XposedBridge.hookAllMethods(ui, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? android.app.Activity ?: return
                    val i = a.intent ?: return
                    XposedBridge.log(
                        "$TAG ImageDownloadUI msgId=${i.getLongExtra("img_msg_id", 0)} " +
                            "svr=${i.getLongExtra("img_server_id", 0)} " +
                            "talker=${i.getStringExtra("img_download_username")} " +
                            "compress=${i.getIntExtra("img_download_compress_type", 0)}"
                    )
                }
            })
            XposedBridge.log("$TAG image download hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG image download hook fail: ${e.message}")
        }
    }
    private var currentMsg: Any? = null
    private var lastOwner: Any? = null
    private var lastView: android.view.View? = null
    private var lastChatTitle: String = ""
    @Volatile
    internal var quickInstance: Any? = null
    private var ctx: Context? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam, context: Context?) {
        ctx = context
        val loader = lpparam.classLoader
        hookImageDownload(loader)
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
        hookViewMenuBuilders(candidates, loader)
        OriginalFetcher.install()
        MultiSelectHook.install(loader)
        AutoViewOriginal.install(loader)
        try {
            context?.registerReceiver(
                WxCliReceiver(),
                android.content.IntentFilter(WxCliReceiver.ACTION)
            )
            XposedBridge.log("$TAG download receiver registered")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG receiver register failed: ${e.message}")
        }
        probeDownloadApi(loader)
        probeMultiSelect(loader)
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
            deepDump("OWNER", param.thisObject, 0)
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
                        ?: findMsgDeep(lastView?.tag, 0)
                        ?: findMsgIn(param.thisObject)
                        ?: findCurrentMsg(param.thisObject)
                        ?: param.args.firstOrNull { MessageExtractor.looksLikeMsgInfo(it) }
                    if (msg != null) {
                        ForwardSender.forward(ctx, msg, lastView, currentChatTitle(lastView).ifBlank { lastChatTitle })
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
                        ForwardSender.forward(ctx, msg, lastView, currentChatTitle(lastView).ifBlank { lastChatTitle })
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

    /** hook 形如 (菜单, View, ...) 的菜单构建方法，记录长按的 item view。 */
    private fun hookViewMenuBuilders(candidates: List<String>, loader: ClassLoader) {
        var n = 0
        candidates.forEach { name ->
            val c = try { Class.forName(name, false, loader) } catch (_: Throwable) { null } ?: return@forEach
            val ms = try { c.declaredMethods } catch (_: Throwable) { return@forEach }
            ms.forEach { m ->
                val ps = m.parameterTypes
                if (ps.size < 2) return@forEach
                if (!android.view.View::class.java.isAssignableFrom(ps[1])) return@forEach
                if (m.returnType != Void.TYPE && m.returnType != Boolean::class.javaPrimitiveType) return@forEach
                val canMenu = Menu::class.java.isAssignableFrom(ps[0]) ||
                    ps[0].name.contains("Menu") || ps[0].name.startsWith("gl5.")
                if (!canMenu) return@forEach
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val menu = param.args.getOrNull(0)
                                lastView = param.args.getOrNull(1) as? android.view.View
                                lastOwner = param.thisObject
                                val ctxArg = param.args.getOrNull(2)
                                val tag = lastView?.tag
                                val fromTag = findMsgDeep(tag, 0)
                                val fromOwner = findMsgIn(param.thisObject)
                                val fromCtx = findMsgDeep(ctxArg, 0) ?: findCurrentMsg(ctxArg)
                                val msg = fromTag ?: fromOwner ?: fromCtx ?: currentMsg
                                if (msg != null) currentMsg = msg
                                if (dumped.add("viewmenu:" + c.name + "#" + m.name)) {
                                    XposedBridge.log(
                                        "$TAG ViewMenuBuild ${c.name}.${m.name} view=${lastView?.javaClass?.name} " +
                                            "tag=${tag?.javaClass?.name} fromTag=${fromTag?.javaClass?.name} " +
                                            "fromOwner=${fromOwner?.javaClass?.name} fromCtx=${fromCtx?.javaClass?.name} " +
                                            "msg=${msg?.javaClass?.name}"
                                    )
                                    if (tag != null && msg == null) deepDump("TAG", tag, 0)
                                    if (ctxArg != null && msg == null) deepDump("CTX", ctxArg, 0)
                                }
                                if (menu != null) addMenuItem(menu, c, msg)
                                lastChatTitle = currentChatTitle(lastView)
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG ViewMenuBuild err: ${e.message}")
                            }
                        }
                    })
                    n++
                    XposedBridge.log("$TAG hookedView ${c.name}.${m.name}(${ps.joinToString { it.simpleName }})")
                } catch (_: Throwable) {
                }
            }
        }
        XposedBridge.log("$TAG viewMenuHooks=$n")
    }

    /** 多选模式探针：选中列表 + 底部「转发」入口。 */
    private fun probeMultiSelect(loader: ClassLoader) {
        // 8.0.79 anchors: chat_quick_select = component.a1
        try {
            val c = Class.forName("com.tencent.mm.ui.chatting.component.a1", false, loader)
            XposedBridge.log("$TAG QUICK class=${c.name}")
            dumpQuickFields(c)
            c.declaredMethods.forEach { m ->
                XposedBridge.log("$TAG QUICK_M ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                if (m.parameterCount == 0) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                quickInstance = p.thisObject
                                val lists = listFields(p.thisObject)
                                if (lists.isNotEmpty()) {
                                    XposedBridge.log("$TAG QUICK_LISTS ${m.name}: " + lists.joinToString { it.first + "=" + it.second })
                                }
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (m.parameterCount == 0 && java.util.List::class.java.isAssignableFrom(m.returnType)) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                val list = p.result as? List<*> ?: return
                                if (list.isNotEmpty()) {
                                    XposedBridge.log("$TAG QUICK_SELECTED ${m.name} size=${list.size} first=${list.firstOrNull()?.javaClass?.name}")
                                    if (dumped.add("quick:" + m.name)) {
                                        list.take(3).forEach { deepDump("QUICK", it, 0) }
                                    }
                                }
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG QUICK class not found: ${e.message}")
        }

        hookFooterClicks()

        // 多选底部操作栏：点「转发」时读取选中列表
        try {
            val bar = Class.forName("com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar", false, loader)
            XposedBridge.log("$TAG FOOTER class=${bar.name}")
            bar.declaredMethods
                .filter { it.name == "onClick" || it.name == "performClick" }
                .forEach { m ->
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) {
                                try {
                                    val v = p.args.firstOrNull() as? android.view.View
                                    val text = collectText(v).take(60)
                                    XposedBridge.log("$TAG FOOTER_CLICK ${m.name} text=$text")
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG FOOTER class not found: ${e.message}")
        }
    }

    private fun currentChatTitle(view: android.view.View?): String {
        try {
            var c: android.content.Context? = view?.context
            var guard = 0
            while (c is android.content.ContextWrapper && guard++ < 8) {
                if (c is android.app.Activity) {
                    val t = c.title?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty()) return t
                }
                c = c.baseContext
            }
        } catch (_: Throwable) {
        }
        return ""
    }

    /** 只读探针：打印微信内部跟图片/文件下载相关的方法。 */
    private fun probeDownloadApi(loader: ClassLoader) {
        val classes = listOf(
            "el5.d",                              // ChattingContext
            "com.tencent.mm.storage.e9",          // MsgInfo
            "gp0.j1"                              // MMKernel
        ) + listOf(
            "com.tencent.mm.ui.chatting.ImageDownloadUI",
            "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI",
            "com.tencent.mm.plugin.priority.model.c2c.img.c"
        )
        classes.forEach { cn ->
            try {
                val c = Class.forName(cn, false, loader)
                val ms = c.declaredMethods.filter {
                    it.name.contains("image", true) || it.name.contains("download", true) ||
                        it.name.contains("img", true) || it.name.contains("attach", true) ||
                        it.name.contains("file", true) || it.name.contains("media", true)
                }
                XposedBridge.log("$TAG DLCLASS $cn methods=${c.declaredMethods.size} hits=${ms.size}")
                ms.take(80).forEach { m ->
                    XposedBridge.log(
                        "$TAG DL ${c.name}.${m.name}(" +
                            m.parameterTypes.joinToString { it.name } + ") -> ${m.returnType.name}"
                    )
                }
                // MsgInfo 所有方法名（查找 getImgPath/getFileFullPath 等）
                if (cn == "com.tencent.mm.storage.e9") {
                    XposedBridge.log(
                        "$TAG MSGMETHODS " +
                            c.declaredMethods.joinToString(",") { it.name }.take(3000)
                    )
                }
                // MsgInfo 里的字段也打出来（文件路径字段）
                if (cn == "com.tencent.mm.storage.e9") {
                    var k: Class<*>? = c
                    var d = 0
                    while (k != null && k != Any::class.java && d < 4) {
                        k.declaredFields.filter {
                            it.name.contains("img", true) || it.name.contains("file", true) ||
                                it.name.contains("path", true)
                        }.forEach { f ->
                            XposedBridge.log("$TAG DLFIELD ${k!!.name}.${f.name}:${f.type.name}")
                        }
                        k = k.superclass
                        d++
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG DLCLASS $cn not found: ${e.message}")
            }
        }
    }

    /** 打印多选组件的所有字段类型（找 List 字段）。 */
    private fun dumpQuickFields(c: Class<*>) {
        val sb = StringBuilder("$TAG QUICK_FIELDS ${c.name}: ")
        var k: Class<*>? = c
        var depth = 0
        while (k != null && k != Any::class.java && depth < 3) {
            k.declaredFields.forEach { f ->
                sb.append(f.name).append(':').append(f.type.simpleName).append(' ')
            }
            k = k.superclass
            depth++
        }
        XposedBridge.log(sb.take(1600).toString())
    }

    /** 找出对象里 List/Set 字段的大小和元素类型。 */
    private fun listFields(obj: Any?): List<Pair<String, String>> {
        if (obj == null) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        var k: Class<*>? = obj.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 3) {
            k.declaredFields.forEach { f ->
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v is Collection<*>) {
                        val first = v.firstOrNull()
                        out.add(f.name to "size=${v.size},first=${first?.javaClass?.name}")
                    }
                } catch (_: Throwable) {
                }
            }
            k = k.superclass
            depth++
        }
        return out
    }

    /** 取多选组件里选中的消息列表（元素是 MsgInfo 的那个 List 字段）。 */
    internal fun selectedMessages(): List<Any> {
        val inst = quickInstance ?: return emptyList()
        var k: Class<*>? = inst.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 3) {
            k.declaredFields.forEach { f ->
                try {
                    f.isAccessible = true
                    val v = f.get(inst)
                    if (v is Collection<*>) {
                        val msgs = v.filterNotNull().filter { MessageExtractor.looksLikeMsgInfo(it) }
                        if (msgs.isNotEmpty()) return msgs
                    }
                } catch (_: Throwable) {
                }
            }
            k = k.superclass
            depth++
        }
        return emptyList()
    }

    /** 全局点击探针：点在多选底部栏「转发」上时，接管为保存所选消息。 */
    private fun hookFooterClicks() {
        try {
            XposedBridge.hookAllMethods(android.view.View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? android.view.View ?: return
                        if (!inFooter(v)) return
                        val text = collectText(v)
                        if (!text.contains("转发")) return
                        val list = selectedMessages()
                        XposedBridge.log("$TAG FOOTER_FORWARD text=$text selected=${list.size}")
                        if (list.isEmpty()) return
                        param.result = true
                        val title = currentChatTitle(v)
                        // 从 Activity 的会话名兜底
                        val chatTitle = title.ifBlank { lastChatTitle }
                        list.forEach { m ->
                            ForwardSender.forward(ctx, m, null, chatTitle, autoFetchOriginal = false)
                        }
                        XposedBridge.log("$TAG batch saved ${list.size} messages")
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun inFooter(v: android.view.View?): Boolean {
        var cur: android.view.View? = v
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.javaClass.name.contains("ChattingFooterMoreBtnBar")) return true
            val parent = cur.parent
            cur = if (parent is android.view.View) parent else null
            depth++
        }
        return false
    }

    private fun collectText(v: android.view.View?): String {
        if (v == null) return ""
        val sb = StringBuilder()
        fun walk(x: android.view.View, depth: Int) {
            if (depth > 3) return
            when (x) {
                is android.widget.TextView -> sb.append(x.text).append(' ')
                is android.view.ViewGroup -> for (i in 0 until x.childCount) walk(x.getChildAt(i), depth + 1)
            }
        }
        walk(v, 0)
        return sb.toString()
    }

    /** 递归 3 层找 MsgInfo（微信把消息塞在 View tag 或嵌套对象里）。 */
    private fun findMsgDeep(obj: Any?, depth: Int): Any? {
        if (obj == null || depth > 3) return null
        if (MessageExtractor.looksLikeMsgInfo(obj)) return obj
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    findMsgDeep(v, depth + 1)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 打印 2 层字段结构 + 尝试无参 getCurrentMsgInfo/getMsgInfo。 */
    private fun deepDump(tag: String, obj: Any?, depth: Int) {
        if (obj == null || depth > 1) return
        try {
            val cls = obj.javaClass
            if (MessageExtractor.looksLikeMsgInfo(obj)) {
                XposedBridge.log("$TAG DEEP[$tag] <<MSG>> ${cls.name}")
                val dump = MessageExtractor.dump(obj)
                dump.lineSequence().chunked(12).forEach { lines ->
                    XposedBridge.log("$TAG DUMP[$tag] " + lines.joinToString("\n"))
                }
                return
            }
            val calls = cls.methods.filter {
                it.parameterCount == 0 && (it.name == "getCurrentMsgInfo" || it.name == "getMsgInfo" || it.name == "getCurrentMsg")
            }
            calls.forEach { m ->
                val v = runCatching { m.invoke(obj) }.getOrNull()
                if (v != null) {
                    XposedBridge.log("$TAG DEEP[$tag] CALL ${cls.name}.${m.name} -> ${v.javaClass.name} msg=${MessageExtractor.looksLikeMsgInfo(v)}")
                    if (MessageExtractor.looksLikeMsgInfo(v)) currentMsg = v
                }
            }
            val sb = StringBuilder("$TAG DEEP[$tag] ${cls.name}: ")
            var c: Class<*>? = cls
            var d = 0
            var count = 0
            while (c != null && c != Any::class.java && d < 3 && count < 25) {
                for (f in c.declaredFields) {
                    if (count++ >= 25) break
                    sb.append(f.name).append(':').append(f.type.simpleName)
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v != null) {
                            sb.append('=').append(v.javaClass.name)
                            if (MessageExtractor.looksLikeMsgInfo(v)) sb.append("<<MSG>>")
                            if (v is android.view.View) sb.append("<<VIEW>>")
                        }
                    } catch (_: Throwable) {
                    }
                    sb.append("  ")
                }
                c = c.superclass
                d++
            }
            XposedBridge.log(sb.take(2200).toString())
            // 递归字段值（最多 15 个，跳过基础类型）
            var c2: Class<*>? = cls
            var cc = 0
            while (c2 != null && c2 != Any::class.java && cc < 15) {
                for (f in c2.declaredFields) {
                    if (cc >= 15) break
                    try {
                        f.isAccessible = true
                        val v = f.get(obj) ?: continue
                        if (v is String || v is Number || v is Boolean || v is android.content.Context) continue
                        if (v.javaClass.name.startsWith("java.")) continue
                        cc++
                        deepDump("$tag>${f.name}", v, depth + 1)
                    } catch (_: Throwable) {
                    }
                }
                c2 = c2.superclass
            }
        } catch (_: Throwable) {
        }
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
