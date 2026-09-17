package com.nous.wxhook.backup

import com.nous.wxhook.root.RootGateways
import java.io.File

/**
 * 读 tar 包内成员文本 —— 大成员必须走「root 进程落盘 + 按路径 copy」，不能经 Binder 回复。
 *
 * 背景：`RootGateways.readFileFromTar` 最终是 `reply.writeString(content)`，Binder 单次事务
 * ~1MB（Parcel 里 String 按 UTF-16 双倍占位）。附件涨到 1.2 万个之后包内
 * `file_manifest.json` 已 1.66MB（parcel ≈3.3MB），走那条必然 `TransactionTooLargeException`，
 * 被上层 catch 成「读不到元数据」→ 存档详情/对比里附件统计全 0。
 *
 * 这里改成：root 进程内 JNI 读出内容写到 /data/local/tmp 中转文件（只回传字符数），
 * 应用再让 root 按路径 copy 到私有目录后本地读取。中转文件用完即删。
 */
object TarMemberReader {

    private const val STAGING_DIR = "/data/local/tmp/wxhook_meta"

    fun readText(pkgPath: String, member: String): String {
        val name = member.substringAfterLast('/')
        val outPath = "$STAGING_DIR/${System.nanoTime()}_$name"
        val written = try {
            RootGateways.readFileFromTarToPath(pkgPath, member, outPath)
        } catch (_: Exception) {
            -1L
        }
        if (written > 0) {
            val local = File(BackupEnv.filesDirForWrite(), "tar_member_${System.nanoTime()}.txt")
            val copied = try {
                RootGateways.copy(outPath, local.absolutePath)
            } catch (_: Exception) {
                false
            }
            try {
                RootGateways.delete(outPath)
            } catch (_: Exception) {
            }
            if (copied) {
                val text = try {
                    if (local.exists()) local.readText() else ""
                } catch (_: Exception) {
                    ""
                }
                local.delete()
                if (text.isNotEmpty()) return text
            }
        }
        // 兜底：小成员走旧路径（Binder 回复装得下）
        return try {
            RootGateways.readFileFromTar(pkgPath, member)
        } catch (_: Exception) {
            ""
        }
    }
}
