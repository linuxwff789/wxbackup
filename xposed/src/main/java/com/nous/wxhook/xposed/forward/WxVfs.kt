package com.nous.wxhook.xposed.forward

import java.io.File

/**
 * 微信 8.0.80 的 vfs 存储：磁盘上的图片文件名后面带 `⌖`，文件内容只有 36 字节，
 * 是一个 UUID 引用；真实数据在 `<图片目录>/.ref/d/<UUID>`。
 *
 * 例：
 *   image2/18/bc/th_18bc40....jpg⌖   -> "96a303c6-7b51-4947-a7f5-c40901fd7909"
 *   image2/.ref/d/96a303c6-....      -> 真实 JPEG（89747 字节）
 */
object WxVfs {

    private val UUID_RE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** 如果 file 是 vfs 引用（小文件 + UUID 内容），返回背后的真实文件；否则原样返回。 */
    fun resolve(file: File?): File? {
        if (file == null || !file.isFile) return null
        if (file.length() > 64) return file
        val txt = runCatching { file.readText().trim() }.getOrNull() ?: return file
        if (!UUID_RE.matches(txt)) return file
        var dir = file.parentFile
        while (dir != null) {
            val ref = File(dir, ".ref/d/$txt")
            if (ref.isFile && ref.length() > 64) return ref
            dir = dir.parentFile
        }
        return file
    }

    fun resolvePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return resolve(File(path))?.absolutePath
    }
}
