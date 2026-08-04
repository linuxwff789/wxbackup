package com.nous.wxhook.backup

import android.util.Log
import com.nous.wxhook.root.RootGateways
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archive manager — list, inspect, select, and diff backup archives.
 *
 * Archives are stored under /sdcard/Download/wxhook_backup/ as
 *   <hash>/   (extracted_full style — SQL dump + attachment dirs + metadata)
 * or as archive packages
 *   <tag>.tar.zst  (full backup archive, needs extraction)
 */
object ArchiveManager {

    private const val TAG = "wxhook:ArchiveMgr"
    private const val BACKUP_DIR = "/sdcard/Download/wxhook_backup"
    private const val SELECTED_FILE = "/data/local/tmp/wxhook_selected_archive.json"

    data class ArchiveInfo(
        val tag: String,
        val hash: String = "",
        val backupTime: Long = 0,
        val backupTimeStr: String = "",
        val messageCount: Long = 0,
        val messageRowId: Long = 0,
        val totalAttachmentFiles: Int = 0,
        val totalAttachmentSize: Long = 0,
        val password: String = "e9cd2ae",
        val path: String = "",
        val source: String = "local",  // "local" or "cloud"
        // Per-directory breakdown
        val attachmentCounts: Map<String, Int> = emptyMap(),
        val attachmentSizes: Map<String, Long> = emptyMap(),
    )

    data class DiffResult(
        val archiveMsgCount: Long,
        val archiveMsgRowId: Long,
        val phoneMsgCount: Long,
        val phoneMsgRowId: Long,
        val unionMsg: Long,
        val onlyInArchive: Long,
        val onlyInPhone: Long,
        val attachments: Map<String, AttachmentDiff>,
        val phoneTotalAttachments: Int,
        val archiveTotalAttachments: Int,
    )

    data class AttachmentDiff(
        val phone: Int,
        val archive: Int,
        val phoneMissing: Int,
        val archiveMissing: Int,
        val union: Int,
    )

    /** 手机侧数据库 + 附件统计（一次 root 调用拿全）。 */
    data class PhoneStats(
        val msgCount: Long,
        val msgRowId: Long,
        val attachmentCounts: Map<String, Int>,
    ) {
        val totalAttachments: Int get() = attachmentCounts.values.sum()
    }

    // ── Scan local archives ──

    fun scanLocalArchives(): List<ArchiveInfo> {
        Log.d(TAG, "scanLocalArchives: scanning $BACKUP_DIR")
        val archives = mutableListOf<ArchiveInfo>()
        val dir = File(BACKUP_DIR)
        if (!dir.exists()) {
            Log.w(TAG, "scanLocalArchives: backup dir not found: $BACKUP_DIR")
            return archives
        }

        // Scan extracted_full-style: <hash>/ directories
        val extracted = File(dir, "backupdata")
        if (extracted.exists()) {
            Log.d(TAG, "scanLocalArchives: scanning backupdata/: ${extracted.listFiles()?.size ?: 0} entries")
            for (child in extracted.listFiles()?.sorted() ?: emptyList()) {
                if (!child.isDirectory || !isArchiveDirCandidate(child.name)) continue
                val info = readArchiveInfo(child)
                if (info != null) {
                    Log.i(TAG, "scanLocalArchives: found archive [${info.tag}] msgs=${info.messageCount} atts=${info.totalAttachmentFiles}")
                    archives.add(info)
                } else {
                    Log.w(TAG, "scanLocalArchives: skipping unreadable dir: ${child.name}")
                }
            }
        } else {
            Log.d(TAG, "scanLocalArchives: no backupdata/ dir")
        }

        // Scan archive packages: .tar.zst files (in backupdata/)
        val pkgDir = File(dir, "backupdata")
        val packages = if (pkgDir.exists()) {
            pkgDir.listFiles()?.filter {
                it.name.endsWith(".tar.zst") || it.name.endsWith(".tar.gz")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else emptyList()
        Log.d(TAG, "scanLocalArchives: found ${packages.size} archive packages")
        packages.forEach { f ->
            val tag = f.nameWithoutExtension
                .removeSuffix(".tar").removeSuffix(".zst").removeSuffix(".gz")
            Log.i(TAG, "scanLocalArchives: found package [${tag}] size=${formatSize(f.length())}")
            archives.add(ArchiveInfo(
                tag = tag,
                backupTime = f.lastModified(),
                backupTimeStr = formatTime(f.lastModified()),
                path = f.absolutePath,
                source = "local",
            ))
        }

        Log.i(TAG, "scanLocalArchives: total ${archives.size} archives found")
        return archives
    }

    /** 过滤解压缓存目录和临时目录，避免把 extracted_* / tmp 显示成存档。 */
    fun isArchiveDirCandidate(name: String): Boolean =
        !name.startsWith("extracted_") && name != "tmp"

    private fun readArchiveInfo(dir: File): ArchiveInfo? {
        val hash = dir.name
        Log.d(TAG, "readArchiveInfo: reading $hash")
        val stateFile = File(dir, "db_state.json")
        val configFile = File(dir, "db_config.json")

        val state = if (stateFile.exists()) try {
            JSONObject(stateFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "readArchiveInfo: bad db_state.json: ${e.message}")
            JSONObject()
        } else {
            Log.d(TAG, "readArchiveInfo: no db_state.json for $hash")
            JSONObject()
        }

        val config = if (configFile.exists()) try {
            JSONObject(configFile.readText())
        } catch (_: Exception) { JSONObject() } else JSONObject()

        val tag = state.optString("lastBackupTag", hash)
        val backupTime = state.optLong("lastBackupTime", dir.lastModified())
        val msgCount = state.optLong("lastMessageRowId", 0)

        // Count SQL dump message lines (rowid 优先，避免每次刷新对大 SQL 跑 grep)
        val sqlDump = dir.listFiles()?.find { it.name.endsWith("_baseline.sql") }
        val sqlMsgCount = if (msgCount > 0) msgCount else if (sqlDump != null) {
            try {
                val r = RootGateways.runQuiet("grep -c '^INSERT INTO message ' '${sqlDump.absolutePath}' 2>/dev/null")
                r.trim().toLongOrNull() ?: msgCount
            } catch (_: Exception) { msgCount }
        } else msgCount

        // Scan attachment directories — 一次 root find 调用统计全部目录（避免 Java walk 逐文件 stat）
        val attDirs = listOf("image2", "voice2", "video", "avatar", "emoji", "cdn")
        val counts = mutableMapOf<String, Int>()
        val sizes = mutableMapOf<String, Long>()
        var totalFiles = 0
        var totalSize = 0L
        val statScript = attDirs.joinToString(" ") { ad ->
            "p='${dir.absolutePath}/$ad'; if [ -d \"\$p\" ]; then echo \"$ad \$(find \"\$p\" -type f 2>/dev/null | wc -l) \$(du -sb \"\$p\" 2>/dev/null | cut -f1)\"; fi"
        }
        val statOut = RootGateways.runQuiet(statScript, 120_000)
        for (line in statOut.lines()) {
            val parts = line.trim().split(" ")
            if (parts.size < 3) continue
            val ad = parts[0]
            val n = parts[1].toIntOrNull() ?: 0
            val sz = parts[2].toLongOrNull() ?: 0L
            counts[ad] = n
            sizes[ad] = sz
            totalFiles += n
            totalSize += sz
            Log.d(TAG, "readArchiveInfo: $hash/$ad: $n files, ${formatSize(sz)}")
        }

        return ArchiveInfo(
            tag = tag,
            hash = hash,
            backupTime = backupTime,
            backupTimeStr = formatTime(backupTime),
            messageCount = sqlMsgCount,
            messageRowId = msgCount,
            totalAttachmentFiles = totalFiles,
            totalAttachmentSize = totalSize,
            password = config.optString("password", "e9cd2ae"),
            path = dir.absolutePath,
            source = "local",
            attachmentCounts = counts,
            attachmentSizes = sizes,
        )
    }

    // ── Diff ──

    fun diffArchive(archive: ArchiveInfo, phoneMsgCount: Long, phoneAttachments: Map<String, Int>): DiffResult {
        Log.d(TAG, "diffArchive: arch=${archive.tag} archMsg=${archive.messageCount} phoneMsg=$phoneMsgCount")
        val archiveMsgCount = archive.messageCount
        val archiveMsgRowId = archive.messageRowId
        val phoneMsgRowId = getPhoneMsgRowId()
        val onlyInArchive = maxOf(0L, archiveMsgCount - phoneMsgCount)
        val onlyInPhone = maxOf(0L, phoneMsgCount - archiveMsgCount)
        val unionMsg = maxOf(archiveMsgCount, phoneMsgCount)

        val allDirs = (phoneAttachments.keys + archive.attachmentCounts.keys).toSet()
        val attDiffs = mutableMapOf<String, AttachmentDiff>()
        var phoneTotal = 0
        var archiveTotal = 0

        for (d in allDirs.sorted()) {
            val phoneN = phoneAttachments[d] ?: 0
            val archN = archive.attachmentCounts[d] ?: 0
            phoneTotal += phoneN
            archiveTotal += archN
            attDiffs[d] = AttachmentDiff(
                phone = phoneN,
                archive = archN,
                phoneMissing = maxOf(0, archN - phoneN),
                archiveMissing = maxOf(0, phoneN - archN),
                union = maxOf(phoneN, archN),
            )
        }

        return DiffResult(
            archiveMsgCount = archiveMsgCount,
            archiveMsgRowId = archiveMsgRowId,
            phoneMsgCount = phoneMsgCount,
            phoneMsgRowId = phoneMsgRowId,
            unionMsg = unionMsg,
            onlyInArchive = onlyInArchive,
            onlyInPhone = onlyInPhone,
            attachments = attDiffs,
            phoneTotalAttachments = phoneTotal,
            archiveTotalAttachments = archiveTotal,
        )
    }

    // ── Select current archive ──

    fun selectArchive(tag: String): Boolean {
        Log.d(TAG, "selectArchive: trying to select $tag")
        val archives = scanLocalArchives()
        val match = archives.find { it.tag == tag }
        if (match == null) {
            Log.w(TAG, "selectArchive: archive not found: $tag")
            return false
        }
        val usable = materialize(match) ?: run {
            Log.w(TAG, "selectArchive: cannot materialize ${match.path}")
            return false
        }
        Log.i(TAG, "selectArchive: selected ${usable.tag} (msgs=${usable.messageCount})")
        val json = JSONObject().apply {
            put("tag", usable.tag)
            put("hash", usable.hash)
            put("backupTime", usable.backupTime)
            put("backupTimeStr", usable.backupTimeStr)
            put("messageCount", usable.messageCount)
            put("messageRowId", usable.messageRowId)
            put("password", usable.password)
            put("path", usable.path)
            put("source", usable.source)
            put("totalAttachmentFiles", usable.totalAttachmentFiles)
            put("totalAttachmentSize", usable.totalAttachmentSize)
        }
        RootGateways.run("mkdir -p /data/local/tmp 2>/dev/null")
        RootGateways.run("cat > '$SELECTED_FILE' << 'EOF'\n${json.toString(2)}\nEOF")
        return true
    }

    private fun materialize(archive: ArchiveInfo): ArchiveInfo? {
        if (!archive.path.endsWith(".tar.zst") && !archive.path.endsWith(".tar.gz")) {
            return archive.takeIf { File(it.path).isDirectory }
        }
        val target = File(BackupEnv.backupDataDir, "extracted_${archive.tag}")
        val dirs = target.listFiles() ?: emptyArray()
        val existing = dirs.firstOrNull { it.isDirectory && File(it, "db_state.json").exists() }
        if (existing == null) {
            RootGateways.run("rm -rf '${target.absolutePath}' && mkdir -p '${target.absolutePath}'", 30_000)
            val result = RootGateways.run(BackupEnv.tarExtractCommand(archive.path, target.absolutePath), 600_000)
            if (!result.isSuccess) {
                Log.e(TAG, "materialize failed: ${result.stderr}")
                return null
            }
        }
        val dir = target.listFiles()?.firstOrNull { it.isDirectory && File(it, "db_state.json").exists() }
            ?: return null
        return readArchiveInfo(dir)
    }

    fun clearSelection(): Boolean = RootGateways.run("rm -f '$SELECTED_FILE' 2>/dev/null").isSuccess

    fun deleteLocalArchive(archive: ArchiveInfo): Boolean {
        if (archive.source != "local" || archive.path.isBlank()) return false
        val ok = RootGateways.run("rm -rf '${archive.path}'", 120_000).isSuccess
        RootGateways.run("rm -rf '${File(BackupEnv.backupDataDir, "extracted_${archive.tag}").absolutePath}'", 120_000)
        if (ok && getSelectedArchive()?.tag == archive.tag) clearSelection()
        return ok
    }

    fun getSelectedArchive(): ArchiveInfo? {
        val raw = RootGateways.runQuiet("cat '$SELECTED_FILE' 2>/dev/null")
        if (raw.isBlank()) return null
        return try {
            val j = JSONObject(raw)
            ArchiveInfo(
                tag = j.optString("tag", ""),
                hash = j.optString("hash", ""),
                backupTime = j.optLong("backupTime", 0),
                backupTimeStr = j.optString("backupTimeStr", formatTime(j.optLong("backupTime", 0))),
                messageCount = j.optLong("messageCount", 0),
                messageRowId = j.optLong("messageRowId", 0),
                password = j.optString("password", "e9cd2ae"),
                path = j.optString("path", ""),
                source = j.optString("source", "local"),
                totalAttachmentFiles = j.optInt("totalAttachmentFiles", 0),
                totalAttachmentSize = j.optLong("totalAttachmentSize", 0),
            )
        } catch (_: Exception) { null }
    }

    // ── Phone state ──

    private var phoneStatsCache: PhoneStats? = null
    private var phoneStatsTime = 0L
    private const val PHONE_STATS_TTL = 5_000L

    /** 一次 root 调用获取手机消息数 + rowid + 附件统计，5 秒内存缓存。 */
    fun getPhoneStats(): PhoneStats {
        val now = System.currentTimeMillis()
        val cached = phoneStatsCache
        if (cached != null && now - phoneStatsTime < PHONE_STATS_TTL) return cached
        val pw = getSelectedArchive()?.password ?: "e9cd2ae"
        val pws = "PRAGMA key='$pw';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;"
        val r = RootGateways.runQuiet(
            "cd /data/data/com.termux/files/home/wxbackup/tools && " +
            "export LD_LIBRARY_PATH=/data/data/com.termux/files/home/wxbackup/tools && " +
            "./sqlcipher '$PHONE_DB_PATH' \"$pws SELECT count(*) FROM message;SELECT coalesce(max(rowid),0) FROM message;\" 2>/dev/null | tail -2"
        )
        val dbLines = r.lines().filter { it.isNotBlank() && it.all { c -> c.isDigit() } }.takeLast(2)
        val msgCount = dbLines.getOrNull(0)?.toLongOrNull() ?: 0L
        val msgRowId = dbLines.getOrNull(1)?.toLongOrNull() ?: 0L

        // 附件统计：一次 root 调用遍历全部目录
        val attDirs = listOf("image2", "voice2", "video", "avatar", "emoji", "cdn")
        val statScript = attDirs.joinToString(" ") { d ->
            "p='$PHONE_ATTACH_DIR/$d'; if [ -d \"\$p\" ]; then echo \"$d \$(find \"\$p\" -type f 2>/dev/null | wc -l)\"; fi"
        }
        val statOut = RootGateways.runQuiet(statScript, 60_000)
        val counts = mutableMapOf<String, Int>()
        for (line in statOut.lines()) {
            val parts = line.trim().split(" ")
            if (parts.size >= 2) counts[parts[0]] = parts[1].toIntOrNull() ?: 0
        }
        val stats = PhoneStats(msgCount, msgRowId, counts)
        phoneStatsCache = stats
        phoneStatsTime = now
        Log.d(TAG, "getPhoneStats: msg=$msgCount rowid=$msgRowId atts=${counts.values.sum()}")
        return stats
    }

    fun getPhoneMsgCount(): Long = getPhoneStats().msgCount

    fun getPhoneMsgRowId(): Long = getPhoneStats().msgRowId

    fun getPhoneAttachmentCounts(): Map<String, Int> = getPhoneStats().attachmentCounts

    /** 解析 "dir count" 行（root find 统计输出）。 */
    fun parseDirCounts(output: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (line in output.lines()) {
            val parts = line.trim().split(" ")
            if (parts.size >= 2) counts[parts[0]] = parts[1].toIntOrNull() ?: 0
        }
        return counts
    }

    // ── Helpers ──

    fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    fun formatSize(bytes: Long): String = when {
        bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / (1024 * 1024 * 1024))
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / (1024 * 1024))
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }

    private const val PHONE_DB_PATH = "/data_mirror/data_ce/null/0/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
    private const val PHONE_ATTACH_DIR = "/data_mirror/data_ce/null/0/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
}
