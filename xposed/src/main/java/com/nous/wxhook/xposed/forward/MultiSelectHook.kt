package com.nous.wxhook.xposed.forward

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 多选模式：在微信底部操作栏（ChattingFooterMoreBtnBar）里加一个「笔记」按钮。
 * 点击后把当前选中的消息批量保存，不占用/不改变微信原有的转发等按钮。
 */
object MultiSelectHook {

    private const val TAG = "[wxhook:Multi]"
    private const val BTN_TAG = "wxnotes_save_selected"

    @Volatile
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        hookAdapter(loader)
        XposedBridge.log("$TAG install(): adapter hook step done")
        try {
            val cls = Class.forName(
                "com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar",
                false,
                loader
            )
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val group = param.thisObject as? ViewGroup ?: return
                    group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> inject(group) }
                    group.post { inject(group) }
                }
            })
            // 兜底：已有实例可能在我们 hook 之前就创建了
            XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val group = param.thisObject as? ViewGroup ?: return
                    if (group.javaClass.name.contains("ChattingFooterMoreBtnBar")) inject(group)
                }
            })
            XposedBridge.log("$TAG installed on ${cls.name}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG install failed: ${e.message}")
        }
    }

    private fun inject(group: ViewGroup) {
        try {
            val parent = group.parent as? ViewGroup
            val holder = parent ?: group
            if (holder.findViewWithTag<View>(BTN_TAG) != null) return

            val ctx = group.context
            val sample = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<TextView>()
                .firstOrNull()
            val textColor = sample?.currentTextColor ?: Color.WHITE

            val row = LinearLayout(ctx).apply {
                tag = BTN_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xF0202020.toInt())
            }
            val btn = TextView(ctx).apply {
                text = "保存所选到笔记"
                gravity = Gravity.CENTER
                setTextColor(textColor)
                textSize = 14f
                isClickable = true
                isFocusable = true
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                setOnClickListener { v ->
                    XposedBridge.log("$TAG row button clicked")
                    val act = findActivity(ctx)
                    if (act == null) {
                        toast(ctx, "找不到聊天界面")
                    } else {
                        saveSelected(act)
                    }
                }
            }
            row.addView(
                btn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            val rowH = dp(ctx, 42)
            val index = parent?.indexOfChild(group) ?: -1
            val lp: ViewGroup.LayoutParams = when (holder) {
                is LinearLayout -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH
                )
                is android.widget.FrameLayout -> android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH, Gravity.BOTTOM
                ).apply { bottomMargin = group.height }
                else -> ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
            }
            if (index >= 0) holder.addView(row, index, lp) else holder.addView(row, lp)

            // 跟随多选栏的可见性
            row.visibility = group.visibility
            group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                row.visibility = group.visibility
                if (holder is android.widget.FrameLayout) {
                    (row.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                        it.bottomMargin = group.height
                        row.layoutParams = it
                    }
                }
            }
            group.post {
                XposedBridge.log(
                    "$TAG row added parent=${parent?.javaClass?.name} groupH=${group.height} " +
                        "row=${row.width}x${row.height} vis=${row.visibility}"
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG inject failed: ${e.message}")
        }
    }

    private fun saveSelected(act: Activity) {
        // 选中列表/文件读取/广播全部放后台，避免卡微信 UI
        Thread({
            try {
                doSaveSelected(act)
            } catch (e: Throwable) {
                XposedBridge.log("$TAG saveSelected error: ${e.message}")
                toast(act, "保存失败：${e.message}")
            }
        }, "wxnotes-save").start()
    }

    private fun doSaveSelected(act: Activity) {
        try {
            val quick = findQuickComponent(act)
            val list = resolveSelected(quick)
            XposedBridge.log("$TAG saveSelected size=${list.size} quick=${quick?.javaClass?.name}")
            if (list.isEmpty()) {
                toast(act, "没有选中消息")
                return
            }
            val title = act.title?.toString()?.trim().orEmpty()
            val n = ForwardSender.forwardList(act, list, title)
            toast(act, "已保存 $n 条到笔记")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG saveSelected error: ${e.message}")
            toast(act, "保存失败：${e.message}")
        }
    }

    /**
     * 微信 8.0.79 的选中状态在 component.a1.z（HashSet<msgId>），
     * q/r/s/t/u 只是几个"最近消息"引用，会多出/重复。
     * 所以以 z 为准，用 z 过滤 q..u 并去重。
     */
    private fun resolveSelected(quick: Any?): List<Any> {
        if (quick == null) return emptyList()
        val ctxObj = readField(quick, "d")
        val z = readField(quick, "z")
        val zSize = (z as? Collection<*>)?.size ?: -1
        val zFirst = (z as? Collection<*>)?.firstOrNull()?.javaClass?.name
        XposedBridge.log("$TAG resolve z=$zSize first=$zFirst ctx=${ctxObj?.javaClass?.name}")

        // z 直接存 MsgInfo
        if (z is Collection<*>) {
            val msgs = z.filterNotNull().filter { MessageExtractor.looksLikeMsgInfo(it) }
            if (msgs.isNotEmpty()) {
                val out = dedupe(msgs)
                XposedBridge.log("$TAG selected from z(MsgInfo) size=${out.size}")
                return out
            }
        }

        // z 存 msgId
        val ids: Set<Long> = (z as? Collection<*>)
            ?.mapNotNull { (it as? Number)?.toLong() }
            ?.toSet()
            .orEmpty()

        val adapterIds = selectedMsgIds(ctxObj)
        if (adapterIds.isNotEmpty()) {
            val byId = adapterIds.mapNotNull { msgById(ctxObj, it) }
            if (byId.isNotEmpty()) {
                val out = dedupe(byId)
                XposedBridge.log("$TAG selected from adapter.W size=${out.size} ids=$adapterIds")
                return out
            }
        }

        // q/r/s/t/u 里筛出真正被选中的
        val singles = listOf("q", "r", "s", "t", "u")
            .mapNotNull { readField(quick, it) }
            .filter { MessageExtractor.looksLikeMsgInfo(it) }
        dumpFields(quick)
        dumpFields(ctxObj)
        if (singles.isNotEmpty()) {
            val detail = singles.mapNotNull { m ->
                runCatching {
                    MessageExtractor.extract(m)?.let { "${it.msgId}/${it.createTime}/${it.type}" }
                }.getOrNull()
            }
            XposedBridge.log("$TAG singles detail=$detail")
        }
        val filtered = if (ids.isNotEmpty()) {
            singles.filter { m -> ids.contains(MessageExtractor.extract(m)?.msgId) }
        } else {
            singles
        }
        val deduped = dedupe(filtered)
        XposedBridge.log("$TAG singles=${singles.size} ids=${ids.size} filtered=${deduped.size}")

        if (deduped.isNotEmpty()) return deduped

        // 按 id 查
        val byId = ids.mapNotNull { msgById(ctxObj, it) }
        val out = dedupe(byId)
        XposedBridge.log("$TAG selected by id size=${out.size}")
        return out
    }

    private fun dumpFields(o: Any?) {
        if (o == null) {
            XposedBridge.log("$TAG dumpFields quick=null")
            return
        }
        try {
            var c: Class<*>? = o.javaClass
            while (c != null && c.name != "java.lang.Object") {
                c.declaredFields.forEach { f ->
                    val t = f.type.simpleName
                    if (t.contains("Set") || t.contains("List") || t.contains("Map") || t.contains("Array")) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o)
                            val sz = when (v) {
                                is Collection<*> -> v.size
                                is Map<*, *> -> v.size
                                is Array<*> -> v.size
                                else -> -1
                            }
                            XposedBridge.log("$TAG field ${f.name}:$t size=$sz")
                        } catch (_: Throwable) {
                        }
                    }
                }
                c = c.superclass
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG dumpFields failed: ${e.message}")
        }
    }

    private fun dedupe(list: List<Any>): List<Any> {
        val seen = HashSet<Long>()
        val out = ArrayList<Any>(list.size)
        list.forEach { m ->
            val id = MessageExtractor.extract(m)?.msgId ?: 0L
            if (id == 0L) {
                out.add(m)
            } else if (seen.add(id)) {
                out.add(m)
            }
        }
        return out
    }

    /** 用 ChattingContext 里 (long)->MsgInfo 的方法按 msgId 查消息。 */
    /** 微信真正维护多选状态的是 adapter.k.W（CopyOnWriteArraySet<MsgIdTalker>）。 */
    private fun selectedMsgIds(ctx: Any?): Set<Long> {
        val adapter = cachedAdapter
        if (adapter != null) {
            runCatching {
                val w = de.robv.android.xposed.XposedHelpers.getObjectField(adapter, "W") as? Collection<*>
                val ids = w?.mapNotNull { (readField(it, "f149692d") as? Number)?.toLong() }?.toSet().orEmpty()
                XposedBridge.log("$TAG cachedAdapter.W size=${w?.size} ids=$ids")
                if (ids.isNotEmpty()) return ids
            }.onFailure { XposedBridge.log("$TAG cachedAdapter.W failed: ${it.message}") }
        }
        if (ctx == null) {
            XposedBridge.log("$TAG ids ctx=null")
            return emptySet()
        }
        return try {
            val cls = Class.forName("wl5.z", false, ctx.javaClass.classLoader)
            // cm5.d 有公开方法 a(Class)（内部转发给 manager），避免读私有字段
            val comp = de.robv.android.xposed.XposedHelpers.callMethod(ctx, "a", cls)
            XposedBridge.log("$TAG ids comp=${comp?.javaClass?.name}")
            if (comp == null) return emptySet()
            val w = de.robv.android.xposed.XposedHelpers.getObjectField(comp, "W")
            XposedBridge.log("$TAG ids w=${w?.javaClass?.name} size=${(w as? Collection<*>)?.size}")
            val col = w as? Collection<*> ?: return emptySet()
            val ids = col.mapNotNull { (readField(it, "f149692d") as? Number)?.toLong() }.toSet()
            XposedBridge.log("$TAG adapter.W ids=$ids")
            ids
        } catch (e: Throwable) {
            XposedBridge.log("$TAG ids failed: ${e.message}")
            emptySet()
        }
    }

    private fun msgById(ctxObj: Any?, id: Long): Any? {
        // 首选 adapter.X0(long)：微信自己按 msgId 取 MsgInfo
        runCatching {
            val adapter = cachedAdapter
            if (adapter != null) {
                val m = adapter.javaClass.methods.firstOrNull {
                    it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
                } ?: return@runCatching
                val v = m.invoke(adapter, id)
                if (MessageExtractor.looksLikeMsgInfo(v)) {
                    val got = MessageExtractor.extract(v)
                    XposedBridge.log("$TAG adapter.X0 want=$id got=${got?.msgId} type=${got?.type}")
                    return v
                }
            }
        }
        // 其次微信自己的查询：oy0.k0.Zi(talker, msgId)
        runCatching {
            val talker = ctxObj?.javaClass?.getMethod("x")?.invoke(ctxObj) as? String
            if (!talker.isNullOrBlank()) {
                val mi = Class.forName("oy0.k0", false, ctxObj.javaClass.classLoader)
                    .getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                    .invoke(null, talker, id)
                if (MessageExtractor.looksLikeMsgInfo(mi)) {
                    val got = MessageExtractor.extract(mi)
                    XposedBridge.log("$TAG msgById want=$id got=${got?.msgId} svr=${got?.msgSvrId} type=${got?.type} talker=$talker")
                    return mi
                }
            }
        }
        if (ctxObj == null) return null
        val methods = ctxObj.javaClass.methods ?: return null
        for (m in methods) {
            if (m.parameterCount != 1) continue
            val pt = m.parameterTypes[0]
            if (pt != java.lang.Long.TYPE && pt != java.lang.Integer.TYPE) continue
            val rt = m.returnType.name
            if (rt != "com.tencent.mm.storage.e9") continue
            try {
                val arg: Any = if (pt == java.lang.Integer.TYPE) id.toInt() else id
                val v = m.invoke(ctxObj, arg)
                if (MessageExtractor.looksLikeMsgInfo(v)) {
                    XposedBridge.log("$TAG msgById via ${m.name}(${pt.simpleName})")
                    return v
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun readField(obj: Any?, name: String): Any? {
        var k: Class<*>? = obj?.javaClass ?: return null
        while (k != null && k != Any::class.java) {
            try {
                val f = k.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
                k = k.superclass
            }
        }
        return null
    }

    @Volatile
    private var cachedAdapter: Any? = null

    private fun hookAdapter(loader: ClassLoader) {
        try {
            val cls = Class.forName("com.tencent.mm.ui.chatting.adapter.k", false, loader)
            val m = cls.getMethod("getView", Int::class.javaPrimitiveType, android.view.View::class.java, android.view.ViewGroup::class.java)
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cachedAdapter = param.thisObject
                }
            })
            XposedBridge.log("$TAG adapter hook installed")
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            XposedBridge.log("$TAG adapter hook failed: " + sw.toString().take(800))
        }
    }

    private fun findQuickComponent(act: Activity): Any? {
        // 优先从当前会话的 ChattingContext 里取组件实例（缓存实例可能是旧会话的，z 为空）
        try {
            val ctx = findObjectByClass(act, "cm5.d", 3, HashSet())
            if (ctx != null) {
                val mgr = readField(ctx, "f35820c")
                if (mgr != null) {
                    val a1Cls = Class.forName("com.tencent.mm.ui.chatting.component.a1", false, act.classLoader)
                    val a = mgr.javaClass.methods.firstOrNull {
                        it.name == "a" && it.parameterCount == 1 && it.parameterTypes[0] == Class::class.java
                    }
                    val inst = a?.invoke(mgr, a1Cls)
                    if (inst != null) {
                        XposedBridge.log("$TAG quick from context: ${inst.javaClass.name}")
                        return inst
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG quick from context failed: ${e.message}")
        }
        return ForwardProbe.quickInstance
    }

    private fun findObjectByClass(root: Any?, target: String, depth: Int, seen: MutableSet<Int>): Any? {
        if (root == null || depth < 0) return null
        if (!seen.add(System.identityHashCode(root))) return null
        if (root.javaClass.name == target) return root
        var k: Class<*>? = root.javaClass
        var d = 0
        while (k != null && k != Any::class.java && d < 4) {
            for (f in k.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(root) ?: continue
                    if (v is String || v is Number || v is Boolean) continue
                    if (v.javaClass.name.startsWith("java.") || v.javaClass.name.startsWith("android.")) continue
                    findObjectByClass(v, target, depth - 1, seen)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            k = k.superclass
            d++
        }
        return null
    }

    private fun selectedFrom(quick: Any?): List<Any> {
        if (quick == null) return emptyList()
        var k: Class<*>? = quick.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 4) {
            for (f in k.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(quick) ?: continue
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

    private fun findActivity(context: Context?): Activity? {
        var c = context
        var guard = 0
        while (c is ContextWrapper && guard++ < 8) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun toast(ctx: Context, text: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
        }
    }
}
