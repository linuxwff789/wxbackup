package com.nous.wxhook.xposed.forward

import de.robv.android.xposed.XposedBridge

/** 反射读取微信 MsgInfo（混淆类名，但 field_* 字段名长期稳定）。 */
object MessageExtractor {

    private const val TAG = "[wxhook:Extract]"

    data class Extracted(
        val msgId: Long,
        val msgSvrId: Long,
        val type: Int,
        val createTime: Long,
        val isSend: Boolean,
        val talker: String,
        val content: String,
        val imgPath: String,
        val fileFullPath: String,
        val fileName: String
    )

    fun looksLikeMsgInfo(obj: Any?): Boolean {
        if (obj == null) return false
        // 8.0.79 的类名被混淆到 gl5.*，不能靠包名判断，只看 field_* 字段
        var clazz: Class<*>? = obj.javaClass
        var hits = 0
        var hasId = false
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                if (f.name == "field_msgId") { hits++; hasId = true }
                else if (f.name == "field_talker" || f.name == "field_createTime" || f.name == "field_content") hits++
            }
            clazz = clazz.superclass
        }
        return hasId || hits >= 2
    }

    fun extract(obj: Any): Extracted? {
        if (!looksLikeMsgInfo(obj)) return null
        val msgId = (read(obj, "field_msgId", "msgId") as? Number)?.toLong() ?: 0L
        val msgSvrId = (read(obj, "field_msgSvrId", "msgSvrId") as? Number)?.toLong() ?: 0L
        val type = (read(obj, "field_type", "type") as? Number)?.toInt() ?: 0
        val createTime = (read(obj, "field_createTime", "createTime") as? Number)?.toLong() ?: 0L
        val isSend = ((read(obj, "field_isSend", "isSend") as? Number)?.toInt() ?: 0) == 1
        val talker = read(obj, "field_talker", "talker") as? String ?: ""
        val content = read(obj, "field_content", "content") as? String ?: ""
        val imgPath = resolveOriginalImage(read(obj, "field_imgPath", "imgPath") as? String ?: "")
        val fileFullPath = read(obj, "field_fileFullPath", "fileFullPath") as? String ?: ""
        val fileName = read(obj, "field_fileName", "fileName") as? String ?: ""
        return Extracted(msgId, msgSvrId, type, createTime, isSend, talker, content, imgPath, fileFullPath, fileName)
    }

    /** 诊断用：打印对象全部字段（首次遇到某类时调用）。 */
    fun dump(obj: Any): String {
        val sb = StringBuilder("class=${obj.javaClass.name}\n")
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                f.isAccessible = true
                val v = try {
                    f.get(obj)
                } catch (_: Throwable) {
                    continue
                } ?: continue
                val s = when (v) {
                    is ByteArray -> "bytes(${v.size})"
                    is String -> v.take(400)
                    else -> v.toString().take(200)
                }
                sb.append("  ").append(f.name).append('=').append(s).append('\n')
            }
            clazz = clazz.superclass
        }
        return sb.toString()
    }

    /**
     * 微信把缩略图存成 image2/xx/yy/th_<md5>，原图是同目录 <md5>.jpg。
     * field_imgPath 通常指向 th_ 缩略图，这里优先换成原图。
     */
    private fun resolveOriginalImage(path: String): String {
        if (path.isBlank()) return path
        // 微信 8.0 的 field_imgPath 常是伪协议 THUMBNAIL_DIRPATH://th_<md5>
        if (path.startsWith("THUMBNAIL_DIRPATH://")) {
            val thumb = path.removePrefix("THUMBNAIL_DIRPATH://")
            val x = thumb.removePrefix("th_")
            if (x.length >= 4) {
                val rel = "image2/${x.substring(0, 2)}/${x.substring(2, 4)}/$thumb"
                val abs = findUnderMicroMsg(rel)
                XposedBridge.log("$TAG imgPath raw=$path -> thumb=$abs")
                if (abs != null) return abs
            }
            return path
        }
        var p = path
        if (p.endsWith("hd")) p = p.dropLast(2)
        val idx = p.lastIndexOf("/th_")
        if (idx < 0) {
            val abs = findUnderMicroMsg(p)
            XposedBridge.log("$TAG imgPath raw=$path -> ${abs ?: path}")
            return abs ?: path
        }
        val base = p.substring(0, idx)
        val name = p.substring(idx + 4)
        val candidates = listOf(
            "$base/$name.jpg", "$base/$name.png", "$base/$name.gif",
            "$base/$name.webp", "$base/$name"
        )
        for (c in candidates) {
            val abs = findUnderMicroMsg(c)
            if (abs != null) {
                XposedBridge.log("$TAG imgPath raw=$path -> original=$abs")
                return abs
            }
        }
        val absThumb = findUnderMicroMsg(p)
        XposedBridge.log("$TAG imgPath raw=$path -> thumb=${absThumb ?: path}")
        return absThumb ?: path
    }

    private fun findUnderMicroMsg(rel: String): String? {
        return try {
            val root = java.io.File("/data/data/com.tencent.mm/MicroMsg")
            root.listFiles()?.forEach { d ->
                val f = java.io.File(d, rel)
                if (f.isFile) return f.absolutePath
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun read(obj: Any, vararg names: String): Any? {
        // 先精确匹配 field_*，再按后缀匹配
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                if (names.any { f.name == it }) {
                    f.isAccessible = true
                    return try {
                        f.get(obj)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            clazz = clazz.superclass
        }
        clazz = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                if (names.any { f.name.endsWith(it) }) {
                    f.isAccessible = true
                    return try {
                        f.get(obj)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }
}
