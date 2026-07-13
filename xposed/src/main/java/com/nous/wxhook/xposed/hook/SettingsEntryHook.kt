package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy

object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private val handler = Handler(Looper.getMainLooper())
    private var injected = false

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        XposedHelpers.findAndHookMethod(android.app.Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as? Activity ?: return
                val n = a.javaClass.name
                if (n.contains("Settings") || n.contains("settings")) {
                    injected = false
                    handler.postDelayed({ inject(a) }, 600)
                }
            }
        })
        XposedBridge.log("$TAG hook installed")
    }

    private fun inject(a: Activity) {
        if (injected) return
        try {
            val root = a.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            val rv = findByClassName(root, "RecyclerView") ?: run { addFallback(a, root); return }

            // Try getAdapter via method
            var adapter: Any? = null
            try {
                adapter = rv.javaClass.getMethod("getAdapter").invoke(rv)
            } catch (_: Exception) {
                // Try reflection on fields
                for (fn in listOf("mAdapter", "adapter", "mWrapAdapter")) {
                    try { adapter = findField(rv.javaClass, fn)?.also { it.isAccessible = true }?.get(rv); if (adapter != null) break } catch (_: Exception) {}
                }
            }
            if (adapter == null) { addFallback(a, root); return }
            if (adapter.javaClass.name.contains("Wxhook")) return

            // Wrap via Proxy
            val orig = adapter
            val wrapped = Proxy.newProxyInstance(orig.javaClass.classLoader, orig.javaClass.interfaces) { _, method, args ->
                when (method.name) {
                    "getItemCount" -> (method.invoke(orig, *(args ?: emptyArray())) as Int) + 1
                    "getItemViewType" -> { val p = args?.get(0) as? Int ?: 0; if (p == 0) 99999 else method.invoke(orig, *(args ?: emptyArray())) }
                    else -> method.invoke(orig, *(args ?: emptyArray()))
                }
            }
            val setM = rv.javaClass.getMethod("setAdapter", Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter"))
            setM.invoke(rv, wrapped)
            injected = true
            XposedBridge.log("$TAG adapter wrapped!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG wrap failed: ${e.message}")
            addFallback(a, a.findViewById(android.R.id.content) as? ViewGroup)
        }
    }

    private fun addFallback(a: Activity, root: ViewGroup?) {
        if (root == null || root.findViewWithTag<View>("wxhook_item") != null) return
        val rv = findByClassName(root, "RecyclerView") ?: return
        val p = rv.parent as? ViewGroup ?: return
        p.addView(createItem(a), p.indexOfChild(rv) + 1)
        injected = true
        XposedBridge.log("$TAG fallback added")
    }

    private fun findByClassName(v: ViewGroup, n: String): View? {
        for (i in 0 until v.childCount) { val c = v.getChildAt(i); if (c.javaClass.simpleName.contains(n)) return c; if (c is ViewGroup) { val r = findByClassName(c, n); if (r != null) return r } }; return null
    }
    private fun findField(c: Class<*>, n: String): java.lang.reflect.Field? { var cl: Class<*>? = c; while (cl != null) { try { return cl.getDeclaredField(n) } catch (_: Exception) { cl = cl.superclass } }; return null }

    private fun createItem(a: Activity): View {
        val dark = isDarkMode(a)
        val bg = if (dark) 0xFF2C2C2C.toInt() else Color.WHITE
        val tc = if (dark) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
        val sc = 0xFF999999.toInt()
        val ac = if (dark) 0xFF666666.toInt() else 0xFFCCCCCC.toInt()
        val row = LinearLayout(a).apply { tag = "wxhook_item"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(bg); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val ib = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6200EE.toInt()); setSize(dp(36), dp(36)) }
        row.addView(TextView(a).apply { text = "⚙"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ib; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })
        val ta = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(14), 0, 0, 0) } }
        ta.addView(TextView(a).apply { text = "wxhook 模块"; textSize = 16f; setTextColor(tc); typeface = Typeface.DEFAULT_BOLD })
        ta.addView(TextView(a).apply { text = "备份 · 定时备份 · 模块状态"; textSize = 12f; setTextColor(sc) })
        row.addView(ta)
        row.addView(TextView(a).apply { text = "›"; textSize = 20f; setTextColor(ac); gravity = Gravity.CENTER })
        row.setOnClickListener { try { it.context.startActivity(Intent().apply { component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) { XposedBridge.log("$TAG err: $e") } }
        return row
    }
    private fun isDarkMode(c: android.content.Context): Boolean = (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun dp(v: Int): Int = (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
