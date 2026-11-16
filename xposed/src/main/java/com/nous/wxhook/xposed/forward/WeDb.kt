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

    fun init(ctx: Context?): Boolean {
        if (db != null) return true
        if (ctx == null) return false
        return try {
            // DexKit 的 .so 在模块 APK 里，微信进程的 java.library.path 找不到，必须按绝对路径加载
            try {
                System.loadLibrary("dexkit")
            } catch (_: Throwable) {
                try {
                    val info = ctx.packageManager.getApplicationInfo("com.nous.wxhook", 0)
                    val so = java.io.File(info.nativeLibraryDir, "libdexkit.so")
                    XposedBridgeShim.load(so.absolutePath)
                    WxDownloader.fileLog(ctx, "WeDb loaded dexkit from ${so.absolutePath}")
                } catch (e2: Throwable) {
                    WxDownloader.fileLog(ctx, "WeDb load dexkit failed: ${e2.message}")
                }
            }
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

            val kernelCls = Class.forName(kernel, false, ctx.classLoader)
            val coreCls = Class.forName(core, false, ctx.classLoader)
            val coreInst = kernelCls.methods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == coreCls
            }?.invoke(null)
            WxDownloader.fileLog(ctx, "WeDb coreInst=${coreInst?.javaClass?.name}")
            if (coreInst == null) return false

            val wrapperCls = Class.forName(wrapper, false, ctx.classLoader)
            // 字段类型可能是 SqliteDB 类本身，也可能是它实现的接口
            val wrapperInst = coreInst.javaClass.fields.firstOrNull { f ->
                f.type == wrapperCls || wrapperCls.interfaces.any { it == f.type }
            }?.apply { isAccessible = true }?.get(coreInst)
            WxDownloader.fileLog(
                ctx,
                "WeDb wrapperInst=${wrapperInst?.javaClass?.name} fields=" +
                    coreInst.javaClass.fields.joinToString { it.name + ":" + it.type.simpleName }
            )
            if (wrapperInst == null) return false

            val dbMethod = wrapperInst.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase"
            }
            WxDownloader.fileLog(ctx, "WeDb dbMethod=${dbMethod?.name} methods=" +
                wrapperInst.javaClass.methods.filter { it.parameterCount == 0 }.take(12)
                    .joinToString { it.name + ":" + it.returnType.simpleName })
            db = dbMethod?.invoke(wrapperInst)
            WxDownloader.fileLog(ctx, "WeDb ready=${db != null}")
            db != null
        } catch (e: Throwable) {
            WxDownloader.fileLog(ctx, "WeDb init failed: ${e.message}")
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
        // 首次 init 要 10s+（DexKit 扫 dex），保存路径上不能同步做，否则主线程 ANR。
        // 这里后台预热，本次先用聊天窗口标题，下一次保存就能带上微信名。
        if (db == null) {
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
