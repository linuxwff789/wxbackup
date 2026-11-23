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

    @Volatile private var holderStore: Any? = null
    @Volatile private var hooksInstalled = false

    /**
     * 8.0.80 的 MsgInfoStorage（com.tencent.mm.storage.g9）是 Dagger 注入的，
     * MMKernel/CoreStorage 都拿不到实例，只能等它被创建时抓一份。
     * 同时 hook h2.Pj() 与 h2 的构造做兜底。
     */
    fun installHooks(ctx: Context?, cl0: ClassLoader? = null) {
        if (hooksInstalled) return
        val c = ctxOf(ctx)
        val cl = cl0 ?: c?.classLoader ?: return
        hooksInstalled = true

        fun log(msg: String) {
            if (c != null) WxDownloader.fileLog(c, msg) else de.robv.android.xposed.XposedBridge.log("[wxhook] $msg")
        }

        runCatching {
            val storeCls = Class.forName("com.tencent.mm.storage.g9", false, cl)
            de.robv.android.xposed.XposedBridge.hookAllConstructors(
                storeCls,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val obj = param?.thisObject ?: return
                        if (holderStore == null) {
                            holderStore = obj
                            log("WxClasses: captured MsgInfoStorage(g9) via <init>")
                        }
                    }
                }
            )
            log("WxClasses: hooked g9 constructors")

            // 构造可能早于 hook 生效，再挂几个高频实例方法：任何一次调用都能拿到 thisObject
            for (name in listOf("Gb", "P1", "Qd", "Sd", "be", "sc", "jb", "K3")) {
                runCatching {
                    de.robv.android.xposed.XposedBridge.hookAllMethods(
                        storeCls, name,
                        object : de.robv.android.xposed.XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                val obj = param?.thisObject ?: return
                                if (holderStore == null) {
                                    holderStore = obj
                                    log("WxClasses: captured MsgInfoStorage via $name()")
                                }
                            }
                        }
                    )
                }
            }
            log("WxClasses: hooked g9 sample methods")
        }.onFailure { log("WxClasses: hook g9 failed: $it") }

        runCatching {
            val holderCls = Class.forName(
                "com.tencent.mm.plugin.messenger.foundation.h2", false, cl
            )
            // 构造里读 g9 类型字段（字段名混淆，按类型匹配）
            de.robv.android.xposed.XposedBridge.hookAllConstructors(
                holderCls,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val obj = param?.thisObject ?: return
                        if (holderStore != null) return
                        runCatching {
                            obj.javaClass.fields.firstOrNull { f ->
                                f.type.name == "com.tencent.mm.storage.g9"
                            }?.apply { isAccessible = true }?.get(obj)
                        }.getOrNull()?.let {
                            holderStore = it
                            log("WxClasses: captured MsgInfoStorage via h2 field")
                        }
                    }
                }
            )
            // Pj() 兜底
            de.robv.android.xposed.XposedBridge.hookAllMethods(
                holderCls, "Pj",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val r = param?.result ?: return
                        if (holderStore == null) holderStore = r
                    }
                }
            )
            log("WxClasses: hooked h2 constructors + Pj")
        }.onFailure { log("WxClasses: hook h2 failed: $it") }
    }

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

    private val obfCache = HashMap<String, String?>()

    /**
     * 从候选类里挑「混淆包名」的那个（wk5.d / g61.k0 这种）。
     * 微信升级后包名会变，但框架类（com.tencent.mm.*）的名字是稳定的，
     * 真正的 storage/context 实现都是混淆短名，所以这个规则比「方法最多」更准。
     */
    fun findObfuscated(ctx: Context?, key: String, vararg strings: String): String? {
        synchronized(obfCache) { if (obfCache.containsKey(key)) return obfCache[key] }
        val obf = Regex("^[a-z][a-z0-9]{1,3}\\.[a-z0-9]{1,4}$")
        val hit = findClasses(ctx, *strings)
            .firstOrNull { obf.matches(it.first) }?.first
        synchronized(obfCache) { obfCache[key] = hit }
        return hit
    }

    /** 聊天上下文（8.0.79 cm5.d / 8.0.80 wk5.d）。 */
    fun chattingContextName(ctx: Context?): String? =
        findObfuscated(ctx, "ChattingContext", "MicroMsg.ChattingContext")

    /** ImgInfo2 storage（8.0.79 v61.b1 / 8.0.80 g61.k0）。 */
    fun imgInfoStorageName(ctx: Context?): String? =
        findObfuscated(ctx, "ImgInfoStorage", "MicroMsg.ImgInfoStorage")

    private val helperCache = HashMap<String, String?>()

    /**
     * 图片下载 helper（8.0.79 dn5.n / 8.0.80 xl5.n，tag MicroMsg.MsgHistoryGalleryMediaHelper）。
     * 候选里有多个同类 tag 的类，靠「有 (f9, boolean, Continuation) 方法」这个签名挑出真正的那个。
     */
    fun mediaHelperName(ctx0: Context?): String? {
        synchronized(helperCache) { if (helperCache.containsKey("helper")) return helperCache["helper"] }
        val ctx = ctxOf(ctx0)
        val hit = if (ctx == null) null else runCatching {
            findClasses(ctx, "MicroMsg.MsgHistoryGalleryMediaHelper").firstNotNullOfOrNull { (name, _) ->
                val cls = runCatching { Class.forName(name, false, ctx.classLoader) }.getOrNull()
                val ok = cls?.declaredMethods?.any { m ->
                    m.parameterCount == 3 &&
                        m.parameterTypes[0].name == "com.tencent.mm.storage.f9" &&
                        m.parameterTypes[1] == java.lang.Boolean.TYPE &&
                        m.parameterTypes[2].name == "kotlin.coroutines.Continuation"
                } == true
                if (ok) name else null
            }
        }.getOrNull()
        synchronized(helperCache) { helperCache["helper"] = hit }
        if (hit != null) WxDownloader.fileLog(ctx, "WxClasses: mediaHelper=$hit")
        return hit
    }

    private val ctxCache = HashMap<String, Any?>()

    /**
     * 微信的 kotlin.coroutines.EmptyCoroutineContext 被 R8 重命名（8.0.80 是 ua6.l）。
     * 传给微信 Continuation 的 getContext() 必须是它自己的实例，否则
     * "Couldn't convert result of type ... to ua6.l"。
     */
    fun emptyCoroutineContext(ctx0: Context?): Any? {
        synchronized(ctxCache) { if (ctxCache.containsKey("empty")) return ctxCache["empty"] }
        val ctx = ctxOf(ctx0)
        val inst = if (ctx == null) null else runCatching {
            // 微信 R8 把 kotlin.coroutines 的整套类都改名了（8.0.80）：
            //   CoroutineContext            -> ua6.l（接口）
            //   EmptyCoroutineContext       -> ua6.m（object，单例字段 d）
            //   EmptyCoroutineContext 也可能是 be6.c0 / ua6.d（带状态的其它实现）
            // 微信 ContinuationImpl.getContext() 会 CHECKCAST 到 ua6.l，
            // 所以必须返回微信自己的实例，模块的 kotlin 版本不行。
            val candidates = listOf(
                "ua6.m",      // EmptyCoroutineContext（static ua6.m d）
                "be6.c0",
                "ua6.d"
            )
            for (n in candidates) {
                val c = runCatching { Class.forName(n, false, ctx.classLoader) }.getOrNull() ?: continue
                for (f in c.declaredFields) {
                    if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull() ?: continue
                    if (c.isInstance(v)) return@runCatching v
                }
            }
            // 兜底：直接 new ua6.m()
            runCatching {
                val c = Class.forName("ua6.m", false, ctx.classLoader)
                val ctor = c.getDeclaredConstructor()
                ctor.isAccessible = true
                ctor.newInstance()
            }.getOrNull()
        }.getOrNull()
        synchronized(ctxCache) { ctxCache["empty"] = inst }
        if (ctx != null) WxDownloader.fileLog(ctx, "WxClasses: emptyContext=${inst?.javaClass?.name}")
        return inst
    }

    /**
     * 探针用：多字符串候选按 OR 合并。
     * 注意 DexKit 的 usingEqStrings(*strings) 是 AND（类必须同时包含全部字符串），
     * 所以这里逐个查询再合并，方法数降序。
     */
    fun candidates(ctx0: Context?, vararg strings: String): List<Pair<String, Int>> {
        val merged = LinkedHashMap<String, Int>()
        for (s in strings) {
            for ((name, count) in findClasses(ctx0, s)) {
                val old = merged[name]
                if (old == null || count > old) merged[name] = count
            }
        }
        return merged.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /** 按字符串常量列出所有候选类（name to 方法数），用于版本升级后人工确认。 */
    fun findClasses(ctx0: Context?, vararg strings: String): List<Pair<String, Int>> {
        val ctx = ctxOf(ctx0) ?: return emptyList()
        installHooks(ctx)
        val b = bridge(ctx) ?: return emptyList()
        return runCatching {
            b.findClass { matcher { usingEqStrings(*strings) } }
                .map { it.name to it.methods.size }
                .sortedByDescending { it.second }
        }.getOrElse { emptyList() }
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
        installHooks(ctx)
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
        // 8.0.80: MsgInfoStorage 由 com.tencent.mm.plugin.messenger.foundation.h2 持有（字段 g），
        // 它不是 MMKernel 服务也不是 CoreStorage 成员（Dagger 注入）。先用这个持有者拿实例。
        runCatching {
            val holderCls = Class.forName(
                "com.tencent.mm.plugin.messenger.foundation.h2", false, ctx.classLoader
            )
            val holderInst = runCatching { v.invoke(null, holderCls) }.getOrNull()
            var inst: Any? = null
            if (holderInst != null) {
                inst = holderInst.javaClass.fields.firstOrNull { f ->
                    store.isAssignableFrom(f.type) || f.type == store
                }?.apply { isAccessible = true }?.get(holderInst)
            }
            if (inst == null) {
                // 静态字段兜底
                inst = holderCls.fields.firstOrNull { f ->
                    java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                        (store.isAssignableFrom(f.type) || f.type == store)
                }?.apply { isAccessible = true }?.get(null)
            }
            if (inst != null) holderStore = inst
        }
        var storeInst: Any? = holderStore

        // MMKernel.v(Class) 是按接口注册的（g9 implements ip3.m0），直接传实现类拿不到，
        // 所以先试实现类，再试它的接口。
        if (storeInst == null) storeInst = runCatching { v.invoke(null, store) }.getOrNull()
        if (storeInst == null || !store.isInstance(storeInst)) {
            storeInst = store.interfaces.firstOrNull { i ->
                runCatching { v.invoke(null, i) }.getOrNull()?.let { store.isInstance(it) } == true
            }?.let { i -> runCatching { v.invoke(null, i) }.getOrNull() }
        }
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
        if (target == null && !java.lang.reflect.Modifier.isStatic(m.modifiers)) {
            // 8.0.80: MsgInfoStorage 实例拿不到，直接查库造等价对象
            return MessageExtractor.fromDb(ctx, talker, msgId).also {
                WxDownloader.fileLog(ctx, "msgInfo fallback db -> ${it != null}")
            }
        }
        return runCatching { m.invoke(target, talker, msgId) }
            .onFailure { WxDownloader.fileLog(ctx, "msgInfo invoke failed: ${it.cause?.message ?: it.message}") }
            .getOrNull()
            ?: MessageExtractor.fromDb(ctx, talker, msgId)
    }

    /** 聊天上下文（8.0.79 的 cm5.d）。 */
    fun chattingContext(ctx: Context?): Class<*>? =
        findClass(ctx, "ChattingContext", "MicroMsg.ChattingContext", "ChattingContext")

    /** 消息 adapter（8.0.79 的 wl5.z，真实类型是 adapter.k）。 */
    fun chatAdapter(ctx: Context?): Class<*>? =
        findClass(ctx, "ChatAdapter", "MicroMsg.ChattingAdapter", "ChattingItemAdapter")

    // ── 真实 MsgInfo（从当前聊天列表的 adapter 里取） ─────────────────

    private fun findViewByClass(root: android.view.View, key: String): android.view.View? {
        if (root.javaClass.name.contains(key)) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) findViewByClass(root.getChildAt(i), key)?.let { return it }
        }
        return null
    }

    private fun windowRoots(): List<android.view.View> = runCatching {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getInstance").invoke(null) ?: return emptyList()
        val f = wmg.javaClass.getDeclaredField("mRoots").apply { isAccessible = true }
        val list = f.get(wmg) as? List<*> ?: return emptyList()
        list.mapNotNull { r ->
            runCatching {
                r!!.javaClass.getDeclaredField("mView").apply { isAccessible = true }
                    .get(r) as? android.view.View
            }.getOrNull()
        }
    }.getOrElse { emptyList() }

    private fun digChatAdapter(o: Any?, depth: Int, seen: MutableSet<Int>): Any? {
        if (o == null || depth > 3) return null
        if (!seen.add(System.identityHashCode(o))) return null
        if (o.javaClass.name == "com.tencent.mm.ui.chatting.adapter.k") return o
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                if (f.type.isPrimitive) continue
                val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull() ?: continue
                digChatAdapter(v, depth + 1, seen)?.let { return it }
            }
            k = k.superclass
        }
        return null
    }

    /**
     * 拿真实的 f9(MsgInfo)：微信自己的图片/文件下载 API 只认这个类型，
     * SQL 造出来的 DbMsgInfo 会因为参数类型不匹配被拒。
     * 当前聊天列表 adapter 的 getItem(i) 返回的正是 f9。
     */
    fun realMsgInfo(ctx0: Context?, talker: String, msgId: Long): Any? {
        if (msgId <= 0) return null
        val roots = windowRoots()
        if (roots.isEmpty()) return null
        val rv = roots.firstNotNullOfOrNull { findViewByClass(it, "ScrollControlRecyclerView") }
            ?: roots.firstNotNullOfOrNull { findViewByClass(it, "MMChattingListView") }
                ?.let { findViewByClass(it, "RecyclerView") }
            ?: return null
        val wrapper = runCatching { rv.javaClass.getMethod("getAdapter").invoke(rv) }.getOrNull()
            ?: return null
        val ad = digChatAdapter(wrapper, 0, HashSet()) ?: return null
        val getItem = ad.javaClass.methods.firstOrNull {
            it.name == "getItem" && it.parameterCount == 1 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE
        } ?: return null
        val count = runCatching {
            ad.javaClass.getMethod("getItemCount").invoke(ad) as? Int
        }.getOrNull() ?: 0
        // 新消息在末尾，倒序找且限制范围，避免几千条全量反射（会卡主线程）
        val start = maxOf(0, count - 400)
        for (i in count - 1 downTo start) {
            val item = runCatching { getItem.invoke(ad, i) }.getOrNull() ?: continue
            val ex = MessageExtractor.extract(item) ?: continue
            if (ex.msgId == msgId) return item
        }
        return null
    }

}
