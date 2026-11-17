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

    @Volatile
    private var cachedRow: View? = null

    @Volatile
    private var cachedGroup: ViewGroup? = null

    /** 当前 adapter 里有没有选中的消息（adapter.k.W）。 */
    private fun hasSelection(): Boolean = runCatching {
        val a = cachedAdapter ?: return false
        val w = de.robv.android.xposed.XposedHelpers.getObjectField(a, "W") as? Collection<*>
        (w?.size ?: 0) > 0
    }.getOrDefault(false)

    private fun updateRowVisibility() {
        val r = cachedRow ?: return
        val g = cachedGroup ?: return
        r.visibility = if (g.visibility == View.VISIBLE && hasSelection()) View.VISIBLE else View.GONE
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

            // 只在"多选栏可见且确实有选中消息"时显示按钮
            cachedRow = row
            cachedGroup = group
            group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateRowVisibility()
                if (holder is android.widget.FrameLayout) {
                    (row.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                        it.bottomMargin = group.height
                        row.layoutParams = it
                    }
                }
            }
            updateRowVisibility()
            group.post {
                updateRowVisibility()
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
            exitMultiSelect(act)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG saveSelected error: ${e.message}")
            toast(act, "保存失败：${e.message}")
        }
    }

    /**
     * 保存后退出多选。
     *
     * 微信没有暴露"退出多选"的入口（a1.z0 只隐藏快捷选择视图，ChattingUIFragment 里也没有多选状态），
     * 用返回键又会在 W 清空后变成"退出聊天"（甚至回桌面）。
     * 所以这里做确定的三件事：清空选中集合、刷新列表、把微信多选栏和我们那行按钮都隐藏掉。
     */
    private fun exitMultiSelect(act: Activity) {
        try {
            val adapter = cachedAdapter
            if (adapter == null) {
                updateRowVisibility()
                return
            }
            val w = runCatching {
                de.robv.android.xposed.XposedHelpers.getObjectField(adapter, "W") as? MutableCollection<*>
            }.getOrNull()
            val had = (w?.size ?: 0)
            w?.clear()
            runCatching { adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter) }
            // 隐藏微信的多选操作栏 + 我们注入的那一行
            cachedGroup?.visibility = View.GONE
            cachedRow?.visibility = View.GONE
            XposedBridge.log("$TAG exitMultiSelect cleared=$had")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG exitMultiSelect failed: ${e.message}")
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

        val items = selectedItems(ctxObj)
        if (items.isNotEmpty()) {
            val byId = items.mapNotNull { (id, tk) -> msgById(ctxObj, id, tk) }
            if (byId.isNotEmpty()) {
                val out = dedupe(byId)
                XposedBridge.log("$TAG selected from adapter.W size=${out.size} ids=${items.map { it.first }}")
                return out
            }
        }

        // q/r/s/t/u 只是"最近消息引用"（同一会话里相邻的几条），不能当作选中集合：
        // 实测只选 2 条时会带出第 3 条。这里只在有 z(ids) 约束时用来补齐。
        if (ids.isNotEmpty()) {
            val singles = listOf("q", "r", "s", "t", "u")
                .mapNotNull { readField(quick, it) }
                .filter { MessageExtractor.looksLikeMsgInfo(it) }
                .filter { m -> ids.contains(MessageExtractor.extract(m)?.msgId) }
            val deduped = dedupe(singles)
            XposedBridge.log("$TAG singles(ids-constrained)=${deduped.size}")
            if (deduped.size == ids.size) return deduped
            val byId = ids.mapNotNull { msgById(ctxObj, it) }
            val out = dedupe(byId)
            XposedBridge.log("$TAG selected by id size=${out.size}/${ids.size}")
            if (out.isNotEmpty()) return out
        }
        dumpFields(quick)
        dumpFields(ctxObj)
        XposedBridge.log("$TAG no reliable selection (adapter.W empty)")
        return emptyList()
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

    /**
     * 微信真正维护多选状态的是 adapter.k.W（CopyOnWriteArraySet<MsgIdTalker>）。
     *
     * adapter 实例在 ChattingUIFragment.f201807s（cm5.d.f35829l / f35830m 指向它），
     * 不能靠 ctx.a(wl5.z)（那个接口有多个实现，拿到的不是 adapter）。
     */
    private fun selectedItems(ctx: Any?): List<Pair<Long, String>> {
        // 必须用"当前会话"的 adapter：cachedAdapter 可能是上一个聊天窗口的实例，
        // 它的 W 是空的（切换会话后不会清空缓存，会导致误判"没有选中消息"）。
        val adapter = findAdapter(ctx)?.also {
            if (cachedAdapter !== it) {
                cachedAdapter = it
                XposedBridge.log("$TAG adapter resolved via fragment: ${it.javaClass.name}")
            }
        } ?: cachedAdapter
        if (adapter == null) {
            XposedBridge.log("$TAG adapter not found (ctx=${ctx?.javaClass?.name})")
            return emptyList()
        }
        return runCatching {
            val w = de.robv.android.xposed.XposedHelpers.getObjectField(adapter, "W") as? Collection<*>
            val items = (w?.toList() ?: emptyList()).mapNotNull { e -> e?.let { msgIdTalkerOf(it) } }
            XposedBridge.log("$TAG adapter.W size=${w?.size} elems=${(w?.toList() ?: emptyList()).map { it?.toString() }}")
            XposedBridge.log("$TAG adapter.W ids=${items.map { it.first }} talkers=${items.map { it.second }}")
            items
        }.getOrElse {
            XposedBridge.log("$TAG adapter.W failed: ${it.message}")
            emptyList()
        }
    }

    /** 从 ChattingContext 的 fragment 字段里找出 adapter.k 实例（不依赖混淆字段名）。 */
    private fun findAdapter(ctx: Any?): Any? {
        if (ctx == null) return null
        val adapterCls = "com.tencent.mm.ui.chatting.adapter.k"
        var k: Class<*>? = ctx.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 5) {
            for (f in k.declaredFields) {
                val v = try {
                    f.isAccessible = true
                    f.get(ctx)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (v.javaClass.name == adapterCls) return v
                val tn = v.javaClass.name
                if (tn.contains("ChattingUIFragment") || tn == "com.tencent.mm.ui.chatting.hb") {
                    adapterOf(v)?.let { return it }
                }
            }
            k = k.superclass
            depth++
        }
        return null
    }

    private fun adapterOf(obj: Any): Any? {
        val adapterCls = "com.tencent.mm.ui.chatting.adapter.k"
        var k: Class<*>? = obj.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 6) {
            for (f in k.declaredFields) {
                // 字段声明类型可能是接口 wl5.z，实际对象才是 adapter.k
                if (f.type.name == adapterCls || f.type.name == "wl5.z") {
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v != null && v.javaClass.name == adapterCls) return v
                    } catch (_: Throwable) {
                    }
                }
            }
            k = k.superclass
            depth++
        }
        return null
    }

    /**
     * MsgIdTalker：jadx 显示 f149692d/f149693e，运行时字段名是 d(long msgId) / e(String talker)。
     */
    private fun msgIdTalkerOf(o: Any): Pair<Long, String>? {
        val id = (readField(o, "d") as? Number)?.toLong()
            ?: (readField(o, "f149692d") as? Number)?.toLong()
            ?: (readField(o, "msgId") as? Number)?.toLong()
            ?: return null
        var talker = (readField(o, "e") as? String)
            ?: (readField(o, "f149693e") as? String)
            ?: ""
        if (talker.isBlank()) {
            runCatching {
                o.javaClass.declaredFields.forEach { f ->
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        val s = f.get(o) as? String
                        if (!s.isNullOrBlank() && (s.contains("@") || s.startsWith("wxid_"))) talker = s
                    }
                }
            }
        }
        return id to talker
    }

    /**
     * 按 msgId 取 MsgInfo。
     *
     * 注意：不能盲信 adapter 上的单参 long 方法（可能是按 position 取值），
     * 否则会把选中的文字消息解析成相邻的其它消息。所有路径都必须校验 msgId。
     */
    private fun msgById(ctxObj: Any?, id: Long, hintTalker: String = ""): Any? {
        val loader = (ctxObj ?: cachedAdapter)?.javaClass?.classLoader ?: return null

        // talker：优先用调用方给的，其次从 ChattingContext 的无参 String 方法里找
        var talker = hintTalker
        if (talker.isBlank() && ctxObj != null) {
            runCatching {
                ctxObj.javaClass.methods.forEach { m ->
                    if (m.parameterCount != 0 || m.returnType != String::class.java) return@forEach
                    if (m.name == "toString" || m.name == "getClass") return@forEach
                    val s = runCatching { m.invoke(ctxObj) as? String }.getOrNull()
                    if (!s.isNullOrBlank() && (s.contains("@") || s.startsWith("wxid_"))) {
                        talker = s
                        return@runCatching
                    }
                }
            }
        }

        // 1) 微信自己的查询：oy0.k0.Zi(talker, msgId)——最可靠
        if (talker.isNotBlank()) {
            runCatching {
                val mi = Class.forName("oy0.k0", false, loader)
                    .getMethod("Zi", String::class.java, java.lang.Long.TYPE)
                    .invoke(null, talker, id)
                if (MessageExtractor.looksLikeMsgInfo(mi) && MessageExtractor.extract(mi)?.msgId == id) {
                    XposedBridge.log("$TAG msgById Zi ok want=$id talker=$talker")
                    return mi
                }
            }
        }

        // 2) adapter 上的单参 long 方法（可能按 position），必须校验 msgId
        val adapter = cachedAdapter
        if (adapter != null) {
            adapter.javaClass.methods.forEach { m ->
                if (m.parameterCount != 1 || m.parameterTypes[0] != java.lang.Long.TYPE) return@forEach
                runCatching {
                    val v = m.invoke(adapter, id)
                    if (MessageExtractor.looksLikeMsgInfo(v)) {
                        val got = MessageExtractor.extract(v)
                        if (got?.msgId == id) {
                            XposedBridge.log("$TAG msgById ${m.name}(long) ok want=$id")
                            return v
                        }
                        XposedBridge.log("$TAG msgById ${m.name}($id) mismatch got=${got?.msgId} type=${got?.type}")
                    }
                }
            }
        }

        // 3) ChattingContext 上返回 MsgInfo 的单参方法，同样校验
        if (ctxObj != null) {
            ctxObj.javaClass.methods.forEach { m ->
                if (m.parameterCount != 1) return@forEach
                val pt = m.parameterTypes[0]
                if (pt != java.lang.Long.TYPE && pt != java.lang.Integer.TYPE) return@forEach
                if (m.returnType.name != "com.tencent.mm.storage.e9") return@forEach
                runCatching {
                    val arg: Any = if (pt == java.lang.Integer.TYPE) id.toInt() else id
                    val v = m.invoke(ctxObj, arg)
                    if (MessageExtractor.looksLikeMsgInfo(v) && MessageExtractor.extract(v)?.msgId == id) {
                        XposedBridge.log("$TAG msgById ctx.${m.name} ok want=$id")
                        return v
                    }
                }
            }
        }
        XposedBridge.log("$TAG msgById NOT FOUND id=$id talker=$talker")
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
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cachedAdapter = param.thisObject
                }
            })
            // 选中/取消选中都会触发 notifyDataSetChanged，借它刷新按钮可见性
            XposedBridge.hookAllMethods(cls, "notifyDataSetChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cachedAdapter = param.thisObject
                    updateRowVisibility()
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
