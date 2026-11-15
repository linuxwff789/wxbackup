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
            if (group.findViewWithTag<View>(BTN_TAG) != null) return
            val ctx = group.context
            val sample = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<TextView>()
                .firstOrNull()
            val btn = TextView(ctx).apply {
                tag = BTN_TAG
                text = "笔记"
                gravity = Gravity.CENTER
                setTextColor(sample?.currentTextColor ?: Color.WHITE)
                textSize = sample?.textSize?.let {
                    it / ctx.resources.displayMetrics.scaledDensity
                } ?: 14f
                isClickable = true
                isFocusable = true
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
                sample?.background?.constantState?.newDrawable()?.let {
                    it.setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_ATOP)
                    background = it
                }
                setOnClickListener { v ->
                    val act = findActivity(ctx)
                    if (act == null) {
                        toast(ctx, "找不到聊天界面")
                        return@setOnClickListener
                    }
                    saveSelected(act)
                }
            }
            val lp: ViewGroup.LayoutParams = if (group is LinearLayout) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            group.addView(btn, lp)
            XposedBridge.log(
                "$TAG button injected, childCount=${group.childCount} children=" +
                    (0 until group.childCount).joinToString(",") { i ->
                        val c = group.getChildAt(i)
                        val t = (c as? TextView)?.text?.toString() ?: ""
                        val lpInfo = c.layoutParams?.let { "w=${it.width},wt=${(it as? LinearLayout.LayoutParams)?.weight}" } ?: "?"
                        "${c.javaClass.simpleName}($t;$lpInfo)"
                    }
            )
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

    /** 依次尝试：Collection<MsgInfo> → 单条 MsgInfo 字段 → ChattingContext.getSelectedList。 */
    private fun resolveSelected(quick: Any?): List<Any> {
        if (quick == null) return emptyList()

        // 1) 组件字段里的消息集合
        var k: Class<*>? = quick.javaClass
        var depth = 0
        while (k != null && k != Any::class.java && depth < 3) {
            for (f in k.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(quick) ?: continue
                    if (v is Collection<*>) {
                        val msgs = v.filterNotNull().filter { MessageExtractor.looksLikeMsgInfo(it) }
                        if (msgs.isNotEmpty()) {
                            XposedBridge.log("$TAG selected from field ${f.name} size=${msgs.size}")
                            return msgs
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            k = k.superclass
            depth++
        }

        // 2) 单个 MsgInfo 字段（q/r/s/t/u 是 e9 类型）
        val singles = ArrayList<Any>()
        listOf("q", "r", "s", "t", "u").forEach { name ->
            readField(quick, name)?.let { if (MessageExtractor.looksLikeMsgInfo(it)) singles.add(it) }
        }
        if (singles.isNotEmpty()) {
            XposedBridge.log("$TAG selected from single fields size=${singles.size}")
            return singles
        }

        // 3) ChattingContext.getSelectedList()
        val ctxObj = readField(quick, "d")
        XposedBridge.log("$TAG quick.d=${ctxObj?.javaClass?.name}")
        if (ctxObj != null) {
            listOf(
                "getSelectedList", "getSelectedMsgList", "getCheckedList",
                "getCheckedMsgList", "getMultiSelectList"
            ).forEach { name ->
                try {
                    val m = ctxObj.javaClass.methods.firstOrNull {
                        it.name == name && it.parameterCount == 0
                    } ?: return@forEach
                    val v = m.invoke(ctxObj)
                    if (v is Collection<*>) {
                        val msgs = v.filterNotNull().filter { MessageExtractor.looksLikeMsgInfo(it) }
                        XposedBridge.log("$TAG ${name} size=${v.size} msgs=${msgs.size}")
                        if (msgs.isNotEmpty()) return msgs
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return emptyList()
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

    private fun findQuickComponent(act: Activity): Any? {
        // 多选操作时 probe 已经缓存了组件实例，不要递归遍历 Activity 字段（很慢）
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
