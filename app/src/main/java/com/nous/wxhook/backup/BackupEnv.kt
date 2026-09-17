package com.nous.wxhook.backup

import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import android.util.Log
import org.json.JSONObject
import java.io.File

object BackupEnv {
    val backupDir = WxHookPaths.BACKUP_DIR
    val backupDataDir = WxHookPaths.BACKUP_DATA_DIR
    var binDir = "/data/local/tmp/wxhook_bin"
    var filesDirPath = "/data/local/tmp"
    var rcloneConfigPath = ""

    /** 当前设备微信用户 hash（备份包内路径前缀）。包内能解析出来时以包内为准，
     *  这个常量只作兜底（微信重装/换账号后可能变化，届时按需更新）。 */
    const val WX_USER_HASH = "6d1f34a5edc49e8b6d238141b2d004f3"

    /**
     * 微信附件目录（备份 / 恢复 / 对比共用同一份清单）。
     * 以前 RestoreEngine 少写了 favorite、ArchiveManager 少写了 record/favorite，
     * 导致"备份有、恢复不回"和"对比界面看不到"。
     */
    val ATTACHMENT_DIRS = listOf(
        "image2", "voice2", "video", "emoji", "avatar", "cdn", "record", "favorite"
    )

    fun init(binDirectory: String, filesDir: String, rcloneCfg: String = "") {
        binDir = binDirectory
        filesDirPath = filesDir
        rcloneConfigPath = rcloneCfg
    }

    fun useZstd(): Boolean = try {
        // 设置页的「使用 zstd 压缩」开关写的是 settings_config.json 的 "zstd"，
        // 而实际压缩实现记录在 db_config.json 的 "compression" —— 两个键都得认，
        // 否则开关切了不生效（开关写 A，打包读 B）。
        val settings = try {
            JSONObject(File(filesDirPath, "settings_config.json").readText())
        } catch (_: Exception) {
            JSONObject()
        }
        if (settings.has("zstd")) {
            settings.optBoolean("zstd", true)
        } else {
            val cfg = File(backupDir, "db_config.json")
            if (cfg.exists()) {
                JSONObject(backupRead(cfg.absolutePath)).optString("compression", "zstd") == "zstd"
            } else true  // 默认 zstd
        }
    } catch (e: Exception) {
        Log.w("wxhook:env", "useZstd check failed, defaulting to zstd", e)
        true
    }

    fun ext(): String = if (useZstd()) ".sql.zst" else ".sql.gz"

    fun archiveExtension(): String = if (useZstd()) ".tar.zst" else ".tar.gz"

    fun isArchiveFile(name: String): Boolean = name.endsWith(".tar.zst") || name.endsWith(".tar.gz")

    fun tarExtractCommand(archivePath: String, outputDir: String): String {
        val zstdPath = "$binDir/zstd"
        return if (archivePath.endsWith(".tar.gz")) {
            "tar -xzf \"$archivePath\" -C \"$outputDir\""
        } else {
            // toybox tar 无法在 PATH 中找到 zstd，必须用绝对路径
            "tar -I '$zstdPath' -xf \"$archivePath\" -C \"$outputDir\""
        }
    }

    // ── Root 操作 ──

    fun su(cmd: String, timeoutMs: Long = 60_000) =
        RootGateways.run(cmd, timeoutMs)

    fun suOut(cmd: String, timeoutMs: Long = 60_000) =
        RootGateways.runQuiet(cmd, timeoutMs)

    fun suCopy(tmp: File, dest: File, mode: String = "644"): Boolean {
        return RootGateways.copy(tmp.absolutePath, dest.absolutePath)
    }

    fun suCopyResult(src: String, dest: String, mode: String = "664"): Boolean {
        return RootGateways.copy(src, dest)
    }

    fun filesDirForWrite(): File = File(filesDirPath).apply { mkdirs() }

    // ── 大内容写入 ──

    /** Binder 单次事务上限 ~1MB，且 Parcel 里字符串按 UTF-16 双倍占位 —— 超过这个阈值必须绕开 writeFile。 */
    private const val BINDER_SAFE_LIMIT = 256 * 1024

    /**
     * 写文件到任意路径（含 /sdcard）。内容超过 Binder 安全阈值时不能走 RootGateways.writeFile
     * ——那是一条 transact 把整份字符串塞进 Parcel，超限抛 TransactionTooLargeException，
     * 被 RootManager 吞成 false，调用方只看到「写入失败」（file_manifest.json 1.66MB 就是这么丢的）。
     * 大内容先写 app 私有目录，再让 root 侧按路径 copy（只传路径，与内容大小无关）。
     */
    fun writeFileSafe(path: String, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= BINDER_SAFE_LIMIT) return RootGateways.writeFile(path, content)
        Log.i("wxhook:Backup", "大内容写入走 copy 路径: ${bytes.size} 字节 -> $path")
        val tmp = File(filesDirForWrite(), "bigwrite_${System.nanoTime()}.tmp")
        return try {
            tmp.writeBytes(bytes)
            val ok = RootGateways.copy(tmp.absolutePath, path)
            if (!ok) Log.e("wxhook:Backup", "大内容写入 copy 失败: $path (${bytes.size} 字节)")
            ok
        } catch (e: Exception) {
            Log.e("wxhook:Backup", "大内容写入异常: ${e.message}")
            false
        } finally {
            tmp.delete()
        }
    }

    // ── /sdcard 操作（走 root） ──

    fun backupExists(path: String): Boolean =
        RootGateways.exists(path)

    fun backupSize(path: String): Long =
        RootGateways.fileSize(path)

    fun backupRead(path: String): String =
        RootGateways.readFile(path)

    fun backupWrite(path: String, content: String) {
        RootGateways.writeFile(path, content)
    }

    fun backupMkdirs(path: String): Boolean =
        RootGateways.mkdirs(path)

    fun backupDelete(path: String): Boolean =
        RootGateways.delete(path)
}
