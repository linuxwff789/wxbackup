package com.nous.wxhook.xposed.forward

import android.content.Context

/**
 * 微信升级后的「一键探针」：把所有依赖的混淆类按**字符串常量 + 方法签名**重新定位一遍。
 *
 * 用法：
 *   am broadcast -a com.nous.wxhook.CMD -p com.tencent.mm --es cmd probe
 * 结果写到 wxhook_dl.log，形如：
 *   [probe] MsgInfoStorage = com.tencent.mm.storage.g9(4555m)
 *
 * 思路：微信升级时类名会整体重排，但**日志 tag 字符串**和**关键方法签名**相对稳定，
 * 所以每个目标都用「字符串常量命中 → 按方法签名/命名特征筛选」两步来定位。
 */
object WxProbe {

    private data class Target(
        val name: String,
        /** DexKit 字符串常量候选（任一命中即可作为候选类） */
        val strings: List<String>,
        /** 进一步筛选：null 表示只按「方法数最多 / 混淆包名」取第一个 */
        val verify: ((Class<*>) -> Boolean)? = null,
        /** 优先取「混淆包名」形态（wk5.d / g61.k0），排除框架类 */
        val preferObfuscated: Boolean = false
    )

    private val OBF = Regex("^[a-z][a-z0-9]{1,3}\\.[a-z0-9]{1,4}$")

    private val TARGETS = listOf(
        Target(
            "MsgInfoStorage",
            listOf("MicroMsg.MsgInfoStorage"),
            verify = { c ->
                c.declaredMethods.any { m ->
                    m.parameterCount == 2 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.parameterTypes[1] == java.lang.Long.TYPE &&
                        !m.returnType.isPrimitive && m.returnType.name != "void"
                }
            },
            preferObfuscated = false
        ),
        Target(
            "ImgInfoStorage",
            listOf("MicroMsg.ImgInfoStorage", "MicroMsg.ImgInfo2Storage"),
            verify = { c ->
                c.declaredMethods.any { m ->
                    m.parameterCount == 2 && m.parameterTypes[0] == String::class.java &&
                        m.parameterTypes[1] == java.lang.Long.TYPE
                }
            },
            preferObfuscated = true
        ),
        Target(
            "MediaHelper",
            listOf("MicroMsg.MsgHistoryGalleryMediaHelper"),
            verify = { c ->
                c.declaredMethods.any { m ->
                    m.parameterCount == 3 &&
                        m.parameterTypes[1] == java.lang.Boolean.TYPE &&
                        m.parameterTypes[2].name == "kotlin.coroutines.Continuation"
                }
            },
            preferObfuscated = true
        ),
        Target(
            "ChattingContext",
            listOf("MicroMsg.ChattingContext"),
            preferObfuscated = true
        ),
        Target(
            "AppAttachDownLoadUtil",
            listOf("MicroMsg.AppAttachDownLoadUtil")
        ),
        Target(
            "AppAttachInfoStorage",
            listOf("MicroMsg.AppAttachInfoStorage"),
            preferObfuscated = true
        ),
        Target(
            "AutoDownloadTaskQueue",
            listOf("MicroMsg.AutoDownloadTaskQueue")
        ),
        Target(
            "SqliteDB",
            listOf("MicroMsg.SqliteDB")
        ),
        Target(
            "MMKernel",
            listOf("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        ),
        Target(
            "ImageService",
            listOf("MicroMsg.ImageService", "MicroMsg.DownloadImageService")
        ),
        Target(
            "BigFileDownload",
            listOf("MicroMsg.CheckBigFileDownload", "MicroMsg.DownloadAppAttach")
        )
    )

    /** 未混淆的固定类：直接确认存在 + 关键成员是否还在 */
    private val FIXED = listOf(
        Triple("ChatAdapter", "com.tencent.mm.ui.chatting.adapter.k", "W"),
        Triple("ChattingFooterMoreBtnBar", "com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar", "a"),
        Triple("MsgInfo", "com.tencent.mm.storage.f9", "field_msgId"),
        Triple("ChattingScrollLayout", "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout", null),
        Triple("ChattingRecyclerView", "com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView", null),
        Triple("EmptyCoroutineContext", "ua6.m", "d")
    )

    fun run(ctx: Context?): String {
        if (ctx == null) return "no context"
        val sb = StringBuilder()

        // 1) 按字符串常量 + 签名定位的混淆类
        for (t in TARGETS) {
            val cands = WxClasses.candidates(ctx, *t.strings.toTypedArray())
            if (cands.isEmpty()) {
                sb.append("[probe] ").append(t.name).append(" = <not found by '")
                    .append(t.strings.firstOrNull()).append("'>\n")
                continue
            }
            val verified = if (t.verify == null) cands else cands.filter { (name, _) ->
                val c = runCatching { Class.forName(name, false, ctx.classLoader) }.getOrNull()
                c != null && t.verify.invoke(c)
            }
            val pool = verified.ifEmpty { cands }
            val picked = if (t.preferObfuscated) {
                pool.firstOrNull { OBF.matches(it.first) } ?: pool.first()
            } else {
                pool.first()
            }
            sb.append("[probe] ").append(t.name).append(" = ").append(picked.first)
                .append('(').append(picked.second).append("m)")
            if (verified.isEmpty()) sb.append(" [signature-mismatch]")
            val others = pool.drop(1).take(3).map { it.first }
            if (others.isNotEmpty()) sb.append("  others=").append(others)
            sb.append('\n')
        }

        // 2) 未混淆固定类：确认类存在 + 关键成员仍在
        for ((label, clsName, member) in FIXED) {
            val c = runCatching { Class.forName(clsName, false, ctx.classLoader) }.getOrNull()
            if (c == null) {
                sb.append("[probe] ").append(label).append(" = MISSING ").append(clsName).append('\n')
                continue
            }
            val has = member == null || runCatching {
                c.declaredFields.any { it.name == member } ||
                    c.declaredMethods.any { it.name == member }
            }.getOrDefault(false)
            sb.append("[probe] ").append(label).append(" = ").append(clsName)
                .append(if (has) " ok" else " [member '$member' missing]").append('\n')
        }

        sb.append("[probe] wechat=")
            .append(runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull())
            .append(" done")
        WxDownloader.fileLog(ctx, sb.toString())
        return sb.toString().replace('\n', ';').take(1500)
    }
}
