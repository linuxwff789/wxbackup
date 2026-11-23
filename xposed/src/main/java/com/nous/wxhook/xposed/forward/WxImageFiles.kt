package com.nous.wxhook.xposed.forward

import java.io.File

/**
 * 按 md5 在微信本地找图片，覆盖 8.0.80 的 vfs 形态（文件名带 ⌖、内容为 UUID 引用）。
 *
 * 降级顺序：原图 → ehд(增强预览) → hd(高清缩略) → 缩略图。
 * 每一级都同时尝试「普通文件名」和「vfs 引用名（带 ⌖）」，并要求解析后 ≥1KB
 * （36 字节的 UUID 引用不算）。
 */
object WxImageFiles {

    private const val VFS_SUFFIX = "\u2316" // ⌖

    fun find(md5: String): File? {
        if (md5.length < 4) return null
        val dirs = File("/data/data/com.tencent.mm/MicroMsg").listFiles() ?: return null
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val base = File(dir, "image2/${md5.take(2)}/${md5.drop(2).take(2)}")
            if (!base.isDirectory) continue

            fun pick(vararg names: String, minBytes: Long = 1024): File? {
                for (n in names) {
                    val f = File(base, n)
                    if (!f.isFile) continue
                    val r = WxVfs.resolve(f) ?: continue
                    if (r.length() >= minBytes) return r
                }
                return null
            }

            // 1) 原图
            for (ext in listOf("jpg", "png", "gif", "webp", "heic")) {
                pick("$md5.$ext", "$md5.$ext$VFS_SUFFIX")?.let { return it }
            }
            // 2) 原图不可用（服务端过期/从未下载）→ 退回本地已有的预览大图
            for (suffix in listOf("ehd", "hd", "")) {
                pick("th_$md5$suffix", "th_$md5$suffix$VFS_SUFFIX")?.let { return it }
            }
        }
        return null
    }

    /** 从 `THUMBNAIL_DIRPATH://th_<md5>` 这类伪路径里取出 md5。 */
    fun md5OfThumbPath(path: String): String? {
        val t = path.substringAfter("th_", "").substringBefore('.').substringBefore('\u2316')
        return t.takeIf { it.length >= 8 }
    }
}
