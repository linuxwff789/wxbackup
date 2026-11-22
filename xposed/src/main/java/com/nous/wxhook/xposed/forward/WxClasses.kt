package com.nous.wxhook.xposed.forward

import android.content.Context
import org.luckypray.dexkit.DexKitBridge

/**
 * 微信混淆类名的运行时解析（DexKit 按字符串常量定位 + 缓存）。
 * 微信升级后类名会整体重排（8.0.79 -> 8.0.80 就全换了），只有字符串常量相对稳定，
 * 所以这里统一用 DexKit 找类，再反射取方法，避免到处写死 `oy0.k0` 这类名字。
 */
object WxClasses {

    @Volatile private var appCtx: Context? = null

    /** 尽早调用一次，后续没有 Context 的调用点也能用。 */
    fun init(ctx: Context?) {
        if (appCtx == null && ctx != null) appCtx = ctx.applicationContext ?: ctx
    }

    private fun ctxOf(ctx: Context?): Context? = ctx ?: appCtx

    @Volatile private var bridge: DexKitBridge? = null
    @Volatile private var bridgeApk: String? = null

    private val cache = HashMap<String, Class<*>?>()

    private fun loader(ctx: Context): ClassLoader = ctx.classLoader

    @Synchronized
    private fun bridge(ctx: Context): DexKitBridge? {
        val apk = ctx.applicationInfo.sourceDir
        bridge?.let { if (bridgeApk == apk) return it }
        runCatching { bridge?.close() }
        bridge = null
        WeDb.ensureDexKit(ctx)
        return runCatching {
            DexKitBridge.create(apk)?.also {
                bridge = it
                bridgeApk = apk
            }
        }.getOrNull()
    }

    /** 按字符串常量找类名（结果按 key 缓存）。 */
    fun findClassName(ctx0: Context?, key: String, vararg strings: String): String? {
        val ctx = ctxOf(ctx0) ?: return null
        synchronized(cache) {
            if (cache.containsKey(key)) {
                return cache[key]?.name
            }
        }
        val b = bridge(ctx) ?: return null
        // 同一个字符串常量可能出现在多个类里（比如只有日志 tag 的辅助类），
        // 取方法数最多的那个——真正的实现类（storage/helper）方法数远多于日志壳子。
        val name = runCatching {
            b.findClass { matcher { usingEqStrings(*strings) } }
                .maxByOrNull { it.methods.size }?.name
        }.getOrNull()
        val cls = name?.let { runCatching { Class.forName(it, false, loader(ctx)) }.getOrNull() }
        synchronized(cache) { cache[key] = cls }
        WxDownloader.fileLog(ctx, "WxClasses[$key] = $name")
        return cls?.name
    }

    fun findClass(ctx: Context?, key: String, vararg strings: String): Class<*>? {
        findClassName(ctx, key, *strings)
        synchronized(cache) { return cache[key] }
    }

    // ── 常用类 ─────────────────────────────────────────────────────

    /** MsgInfo（com.tencent.mm.storage.e9 的等价物）：从 MsgInfoStorage 的静态查询方法返回类型拿。 */
    fun msgInfoClass(ctx: Context?): Class<*>? = runCatching {
        val store = findClass(ctx, "MsgInfoStorage", "MicroMsg.MsgInfoStorage")
            ?: return null
        store.methods.firstOrNull { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                (m.parameterTypes[1] == java.lang.Long.TYPE || m.parameterTypes[1] == java.lang.Integer.TYPE) &&
                m.returnType.name != "void" &&
                !m.returnType.isPrimitive
        }?.returnType
    }.getOrNull()

    /**
     * MsgInfoStorage.get(talker, msgId)：wx 内部按会话+本地 id 取消息。
     * 方法名在 8.0.80 里依然可能是 `Zi`，但这里只用签名筛选，名字变了也能用。
     */
    fun msgInfo(ctx0: Context?, talker: String, msgId: Long): Any? {
        val ctx = ctxOf(ctx0)
        if (ctx == null || talker.isBlank() || msgId <= 0) return null
        val store = findClass(ctx, "MsgInfoStorage", "MicroMsg.MsgInfoStorage") ?: return null
        // 优先叫 Zi 的（历史名字），否则退化为签名匹配
        val candidates = store.methods.filter { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                (m.parameterTypes[1] == java.lang.Long.TYPE || m.parameterTypes[1] == java.lang.Integer.TYPE) &&
                m.returnType.name != "void" &&
                !m.returnType.isPrimitive
        }
        val m = candidates.firstOrNull { it.name == "Zi" } ?: candidates.firstOrNull() ?: return null
        val arg = if (m.parameterTypes[1] == java.lang.Integer.TYPE) msgId.toInt() else msgId
        return runCatching { m.invoke(null, talker, arg) }.getOrNull()
    }

    /** 聊天上下文（8.0.79 的 cm5.d）。 */
    fun chattingContext(ctx: Context?): Class<*>? =
        findClass(ctx, "ChattingContext", "MicroMsg.ChattingContext", "ChattingContext")

    /** 消息 adapter（8.0.79 的 wl5.z，真实类型是 adapter.k）。 */
    fun chatAdapter(ctx: Context?): Class<*>? =
        findClass(ctx, "ChatAdapter", "MicroMsg.ChattingAdapter", "ChattingItemAdapter")
}
