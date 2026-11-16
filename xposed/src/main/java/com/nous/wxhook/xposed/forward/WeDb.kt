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
            val wrapperInst = coreInst.javaClass.fields.firstOrNull {
                wrapperCls.isAssignableFrom(it.type)
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

    fun query(ctx: Context?, sql: String, args: Array<String>? = null): List<Map<String, Any?>> {
        if (!init(ctx)) return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        var c: Cursor? = null
        try {
            c = db!!.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
                .invoke(db, sql, args) as? Cursor ?: return emptyList()
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
            WxDownloader.fileLog(ctx, "WeDb query failed: ${e.message}")
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
