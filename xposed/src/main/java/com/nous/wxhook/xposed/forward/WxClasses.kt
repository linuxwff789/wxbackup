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
     * MsgInfoStorage 的查询：8.0.79 是静态门面 oy0.k0.Zi(talker, msgId)，
     * 8.0.80 变成实例方法 g9.K3(String, long) -> f9（f9 即 MsgInfo）。
     * 这里不依赖具体名字：拿 MMKernel.v(MsgInfoStorage) 的实例，
     * 再按签名 (String, long) -> MsgInfo 反射调用。
     */
    fun msgInfo(ctx0: Context?, talker: String, msgId: Long): Any? {
        val ctx = ctxOf(ctx0)
        if (ctx == null || talker.isBlank() || msgId <= 0) return null
        val store = findClass(ctx, "MsgInfoStorage", "MicroMsg.MsgInfoStorage") ?: return null

        // 先拿 MsgInfo 类型（静态方法或实例方法的返回类型都行）
        val msgClsName = "com.tencent.mm.storage.f9"
        val candidates = store.methods.filter { m ->
            m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == java.lang.Long.TYPE &&
                (m.returnType.name == msgClsName ||
                    store.methods.any { it.returnType == m.returnType } && m.returnType.name.startsWith("com.tencent.mm.storage."))
        }
        val m = candidates.firstOrNull { it.name == "K3" } ?: candidates.firstOrNull() ?: return null

        val kernelName = WeDb.kernelName
            ?: findClassName(ctx, "MMKernel", "MicroMsg.MMKernel", "Kernel not null, has initialized.")
            ?: return null
        val kernel = runCatching { Class.forName(kernelName, false, ctx.classLoader) }.getOrNull() ?: return null
        val v = kernel.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 1 &&
                it.parameterTypes[0] == Class::class.java
        } ?: return null

        // MsgInfoStorage 是 CoreStorage 的成员（不是 MMKernel 服务）：先用 WeDb.bind 同款方式
        // 取 CoreStorage 实例（无参静态方法，返回类型就是 CoreStorage），再取它的字段/方法。
        var storeInst: Any? = runCatching { v.invoke(null, store) }.getOrNull()
        if (storeInst == null || !store.isInstance(storeInst)) {
            val coreName = WeDb.coreName
            if (coreName != null) {
                val coreCls = runCatching { Class.forName(coreName, false, ctx.classLoader) }.getOrNull()
                val coreInst = coreCls?.let {
                    runCatching {
                        kernel.methods.firstOrNull { m2 ->
                            java.lang.reflect.Modifier.isStatic(m2.modifiers) &&
                                m2.parameterCount == 0 && m2.returnType == it
                        }?.invoke(null)
                    }.getOrNull()
                }
                if (coreInst != null) {
                    storeInst = runCatching {
                        coreInst.javaClass.fields.firstOrNull { f ->
                            f.type == store || store.isAssignableFrom(f.type) ||
                                f.type.isAssignableFrom(store)
                        }?.apply { isAccessible = true }?.get(coreInst)
                    }.getOrNull()
                    if (storeInst == null) {
                        storeInst = runCatching {
                            coreInst.javaClass.methods.firstOrNull { m2 ->
                                m2.parameterCount == 0 &&
                                    (m2.returnType == store || m2.returnType.isAssignableFrom(store))
                            }?.invoke(coreInst)
                        }.getOrNull()
                    }
                }
            }
        }
        WxDownloader.fileLog(
            ctx,
            "msgInfo store=${store.name} method=${m.name} inst=${storeInst != null} " +
                "static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}"
        )

        val target = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else storeInst
        if (target == null && !java.lang.reflect.Modifier.isStatic(m.modifiers)) return null
        return runCatching { m.invoke(target, talker, msgId) }
            .onFailure { WxDownloader.fileLog(ctx, "msgInfo invoke failed: ${it.cause?.message ?: it.message}") }
            .getOrNull()
    }

    /** 聊天上下文（8.0.79 的 cm5.d）。 */
    fun chattingContext(ctx: Context?): Class<*>? =
        findClass(ctx, "ChattingContext", "MicroMsg.ChattingContext", "ChattingContext")

    /** 消息 adapter（8.0.79 的 wl5.z，真实类型是 adapter.k）。 */
    fun chatAdapter(ctx: Context?): Class<*>? =
        findClass(ctx, "ChatAdapter", "MicroMsg.ChattingAdapter", "ChattingItemAdapter")
}
