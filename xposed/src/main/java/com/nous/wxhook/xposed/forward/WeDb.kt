package com.nous.wxhook.xposed.forward

import android.content.Context
import android.database.Cursor
import org.luckypray.dexkit.DexKitBridge

import java.lang.reflect.Modifier

/**
 * 只读访问微信主库（EnMicroMsg.db）。
 * 类名用 DexKit 按日志字符串定位（WeKit 同款）：
 *   MMKernel -> CoreStorage -> SqliteDB wrapper -> com.tencent.wcdb SQLiteDatabase
 */
object WeDb {

    private const val TAG = "[wxhook:Db]"
    @Volatile
    private var db: Any? = null

    /**
     * 已知版本（微信 8.0.79）的混淆类名：
     * kernel=MMKernel, core=CoreStorage, wrapper=SqliteDB。
     * 命中时完全不加载 DexKit（省掉扫描 200MB dex 的 10s 延迟和内存峰值）。
     */
    private val KNOWN_CLASSES = listOf(
        Triple("pq0.i1", "pq0.a0", "fj5.b0")
    )

    /** 只走已知类名的快速初始化，不触发 DexKit。 */
    @Volatile
    private var dexKitReady = false

    /**
     * 加载 DexKit 的 native 库。
     * 微信进程的 java.library.path 里没有 libdexkit.so，必须从模块 APK 的 nativeLibraryDir 按绝对路径加载。
     * fastInit 走已知类名时不会加载它，用到 DexKit 的路径（如附件无感下载）要先调这里。
     */
    /**
     * 模块 APK 的 nativeLibraryDir。
     * 微信进程受 Android 11+ 包可见性限制，普通 PackageManager 查不到模块包；
     * 依次尝试普通 PM -> AppGlobals（系统 PM）-> /proc/self/maps 里的模块 APK 路径。
     */
    private fun moduleLibDir(ctx: Context): String? {
        val pkgs = arrayOf("com.nous.wxhook.xposed", "com.nous.wxhook")
        for (pkg in pkgs) {
            val r = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir }
            WxDownloader.fileLog(
                ctx,
                "libdir pm $pkg -> ${r.getOrNull()} err=${r.exceptionOrNull()?.javaClass?.simpleName}"
            )
            r.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        runCatching {
            val pm = Class.forName("android.app.AppGlobals")
                .getMethod("getPackageManager").invoke(null) ?: return@runCatching
            val m = pm.javaClass.getMethod(
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            for (pkg in pkgs) {
                val info = runCatching { m.invoke(pm, pkg, 0, 0) as? android.content.pm.ApplicationInfo }
                WxDownloader.fileLog(
                    ctx,
                    "libdir ag $pkg -> ${info.getOrNull()?.nativeLibraryDir} err=${info.exceptionOrNull()?.javaClass?.simpleName}"
                )
                info.getOrNull()?.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }.onFailure {
            WxDownloader.fileLog(ctx, "libdir AppGlobals failed: ${it.javaClass.simpleName} ${it.message}")
        }
        // 最后：从 /proc/self/maps 找模块 APK（LSPosed 可能把模块 apk 映射进进程）
        runCatching {
            java.io.File("/proc/self/maps").readLines().forEach { line ->
                if (line.contains("com.nous.wxhook") && line.contains(".apk")) {
                    val p = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
                    WxDownloader.fileLog(ctx, "libdir maps apk=$p")
                    val apk = java.io.File(p)
                    if (apk.isFile) {
                        return@runCatching java.io.File(apk.parentFile, "lib/arm64").absolutePath
                    }
                }
            }
        }
        return null
    }

    fun ensureDexKit(ctx: Context?) {
        if (dexKitReady || ctx == null) return
        synchronized(this) {
            if (dexKitReady) return
            runCatching {
                System.loadLibrary("dexkit")
                dexKitReady = true
            }
            if (!dexKitReady) {
                val libDir = moduleLibDir(ctx)
                if (libDir != null) {
                    val so = java.io.File(libDir, "libdexkit.so")
                    if (so.isFile) {
                        val ok = runCatching {
                            XposedBridgeShim.load(so.absolutePath)
                            true
                        }.getOrDefault(false)
                        if (ok) {
                            dexKitReady = true
                            WxDownloader.fileLog(ctx, "dexkit loaded from ${so.absolutePath}")
                        }
                    }
                }
            }
            if (!dexKitReady) WxDownloader.fileLog(ctx, "ensureDexKit failed")
        }
    }

    fun fastInit(ctx: Context?): Boolean {
        if (db != null) return true
        if (ctx == null) return false
        for ((k, c, w) in KNOWN_CLASSES) {
            if (bind(ctx, k, c, w)) {
                WxDownloader.fileLog(ctx, "WeDb fast init ok ($k/$c/$w)")
                return true
            }
        }
        return false
    }

    fun init(ctx: Context?): Boolean {
        if (db != null) return true
        if (ctx == null) return false
        // 先试已知类名，绝大多数情况不用付出 DexKit 的代价
        if (fastInit(ctx)) return true
        ensureDexKit(ctx)
        return try {
            val bridge = DexKitBridge.create(ctx.applicationInfo.sourceDir) ?: return false
            fun find(vararg strings: String): String? =
                bridge.findClass {
                    matcher { usingEqStrings(*strings) }
                }.firstOrNull()?.name

            val kernel = find("MicroMsg.MMKernel", "Kernel not null, has initialized.")
            val core = find("MMKernel.CoreStorage", "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s")
            val wrapper = find("MicroMsg.SqliteDB", "sql is null ")
            bridge.close()
            WxDownloader.fileLog(ctx, "WeDb classes kernel=$kernel core=$core wrapper=$wrapper")
            if (kernel == null || core == null || wrapper == null) return false

            bind(ctx, kernel, core, wrapper)
        } catch (e: Throwable) {
            WxDownloader.fileLog(ctx, "WeDb init failed: ${e.message}")
            false
        }
    }

    /** 反射绑定 MMKernel -> CoreStorage -> SqliteDB -> SQLiteDatabase。 */
    private fun bind(ctx: Context, kernel: String, core: String, wrapper: String): Boolean {
        if (db != null) return true
        return try {
            val kernelCls = Class.forName(kernel, false, ctx.classLoader)
            val coreCls = Class.forName(core, false, ctx.classLoader)
            val coreInst = kernelCls.methods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == coreCls
            }?.invoke(null)
            if (coreInst == null) return false

            val wrapperCls = Class.forName(wrapper, false, ctx.classLoader)
            // 字段类型可能是 SqliteDB 类本身，也可能是它实现的接口
            val wrapperInst = coreInst.javaClass.fields.firstOrNull { f ->
                f.type == wrapperCls || wrapperCls.interfaces.any { it == f.type }
            }?.apply { isAccessible = true }?.get(coreInst) ?: return false

            val dbMethod = wrapperInst.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase"
            } ?: return false
            db = dbMethod.invoke(wrapperInst)
            db != null
        } catch (_: Throwable) {
            false
        }
    }

    private val nickCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 会话/联系人的微信昵称（rcontact.nickname）。群聊返回群名称。
     * 缓存到进程结束，避免每次保存都查库。
     */
    fun contactNickname(ctx: Context?, talker: String): String {
        if (ctx == null || talker.isBlank()) return ""
        nickCache[talker]?.let { return it }
        // 只走已知类名（毫秒级）；微信升级后才需要 DexKit，那部分放到后台预热，
        // 避免在保存路径上扫 200MB dex 造成内存峰值和主线程 ANR。
        if (db == null && !fastInit(ctx)) {
            Thread({ init(ctx) }, "wxnotes-db-warm").apply { isDaemon = true }.start()
            return ""
        }
        return try {
            val rows = query(
                ctx,
                "SELECT nickname AS n FROM rcontact WHERE username=? LIMIT 1",
                arrayOf(talker)
            )
            val name = rows.firstOrNull()?.get("n")?.toString().orEmpty().trim()
            if (name.isNotEmpty()) nickCache[talker] = name
            name
        } catch (_: Throwable) {
            ""
        }
    }

    /** 执行非查询 SQL（UPDATE/DELETE 等）。调试/修复数据用。 */
    fun execSql(ctx: Context?, sql: String): Boolean {
        if (!init(ctx)) return false
        return try {
            val m = db!!.javaClass.methods.firstOrNull {
                it.name == "execSQL" && it.parameterCount == 1
            } ?: throw NoSuchMethodException("execSQL(String)")
            m.invoke(db, sql)
            WxDownloader.fileLog(ctx, "WeDb execSql ok sql=${sql.take(80)}")
            true
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            WxDownloader.fileLog(ctx, "WeDb execSql failed: ${cause.javaClass.simpleName} ${cause.message}")
            false
        }
    }

    fun query(ctx: Context?, sql: String, args: Array<String>? = null): List<Map<String, Any?>> {
        if (!init(ctx)) return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        var c: Cursor? = null
        try {
            runCatching {
                val open = db!!.javaClass.getMethod("isOpen").invoke(db)
                WxDownloader.fileLog(ctx, "WeDb isOpen=$open sql=${sql.take(60)}")
            }
            val m = db!!.javaClass.methods.firstOrNull {
                it.name == "rawQuery" && it.parameterCount == 2
            } ?: throw NoSuchMethodException("rawQuery(String,String[])")
            c = m.invoke(db, sql, args ?: arrayOf<String>()) as? Cursor ?: return emptyList()
            val names = c.columnNames
            while (c.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (i in names.indices) {
                    row[names[i]] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> ""
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                out.add(row)
            }
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            WxDownloader.fileLog(
                ctx,
                "WeDb query failed: ${e.message} cause=${cause.javaClass.name}:${cause.message}\n" +
                    cause.stackTraceToString().take(1200)
            )
        } finally {
            try {
                c?.close()
            } catch (_: Throwable) {
            }
        }
        return out
    }
}

private object XposedBridgeShim {
    fun load(path: String) = System.load(path)
}
