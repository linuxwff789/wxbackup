package com.nous.wxhook.backup

import android.util.Log
import com.nous.wxhook.root.RootGateways
import org.json.JSONArray
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
    // 当前设备微信用户 hash（备份包内路径前缀），避免 listTar 全量扫包
    private const val WX_USER_HASH = "6d1f34a5edc49e8b6d238141b2d004f3"

    /** 包元数据缓存：path -> (包 mtime, ArchiveInfo)，避免每次刷新都 JNI 扫包。 */
    private val pkgInfoCache = mutableMapOf<String, Pair<Long, ArchiveInfo>>()

    data class ArchiveInfo(
        val tag: String,
        val hash: String = "",
        val backupTime: Long = 0,
        val backupTimeStr: String = "",
        val messageCount: Long = 0,
        val messageRowIdFrom: Long = 0,
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
        val archiveMsgRowIdFrom: Long,
        val archiveMsgRowId: Long,
        val phoneMsgCount: Long,
        val phoneMsgRowIdFrom: Long,
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
        val msgRowIdFrom: Long,
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
            // 刷新只显示文件名+大小，不解压、不读包内元数据（详情时才处理）
            val tag = f.nameWithoutExtension.removeSuffix(".tar").removeSuffix(".zst").removeSuffix(".gz")
            archives.add(ArchiveInfo(
                tag = tag,
                backupTime = f.lastModified(),
                backupTimeStr = formatTime(f.lastModified()),
                totalAttachmentSize = f.length(),
                path = f.absolutePath,
                source = "local",
            ))
            Log.i(TAG, "scanLocalArchives: found package [${tag}] size=${formatSize(f.length())}")
        }

        Log.i(TAG, "scanLocalArchives: total ${archives.size} archives found")
        return archives
    }

    /**
     * 用 JNI 直接从 tar 包读取元数据（db_state.json / db_config.json / file_manifest.json），
     * 不整包解压、不 listTar 全量扫包。失败返回 null（调用方回退到仅大小信息）。
     */
    private fun readPackageInfo(pkg: File): ArchiveInfo? {
        return try {
            val hash = WX_USER_HASH
            val stateRaw = RootGateways.readFileFromTar(pkg.absolutePath, "$hash/db_state.json")
            if (stateRaw.isBlank()) return null
            val state = JSONObject(stateRaw)
            val tag = pkg.nameWithoutExtension
                .removeSuffix(".tar").removeSuffix(".zst").removeSuffix(".gz")
            val backupTime = state.optLong("lastBackupTime", pkg.lastModified())

            var password = "e9cd2ae"
            try {
                val cfgRaw = RootGateways.readFileFromTar(pkg.absolutePath, "$hash/db_config.json")
                if (cfgRaw.isNotBlank()) password = JSONObject(cfgRaw).optString("password", password)
            } catch (_: Exception) {}

            // 附件统计：JNI 读 file_manifest.json（包前部，快），不 listTar
            var counts = emptyMap<String, Int>()
            try {
                val manRaw = RootGateways.readFileFromTar(pkg.absolutePath, "$hash/file_manifest.json")
                if (manRaw.isNotBlank()) {
                    val man = JSONObject(manRaw)
                    val files = man.optJSONArray("files") ?: JSONArray()
                    val c = mutableMapOf<String, Int>()
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val rel = f.optString("path", "").removePrefix("$hash/")
                        val dir = rel.substringBefore("/")
                        if (dir.isNotBlank()) c[dir] = (c[dir] ?: 0) + 1
                    }
                    counts = c
                }
            } catch (_: Exception) {}

            ArchiveInfo(
                tag = tag,
                hash = hash,
                backupTime = backupTime,
                backupTimeStr = formatTime(backupTime),
                messageCount = state.optLong("lastMessageRowId", 0),
                messageRowIdFrom = state.optLong("lastMessageRowIdFrom", 0),
                messageRowId = state.optLong("lastMessageRowId", 0),
                totalAttachmentFiles = counts.values.sum(),
                totalAttachmentSize = pkg.length(),
                password = password,
                path = pkg.absolutePath,
                source = "local",
                attachmentCounts = counts,
            )
        } catch (_: Exception) { null }
    }

    /** 详情用：JNI 读包内 JSON（db_state/db_config/file_manifest），不 shell 解压。 */
    fun refreshPackageMeta(archive: ArchiveInfo): ArchiveInfo? =
        readPackageInfo(File(archive.path))

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
        val msgRowIdFrom = state.optLong("lastMessageRowIdFrom", 0)
        val msgCount = state.optLong("lastMessageRowId", 0)

        // Count SQL dump message lines (rowid 优先，避免每次刷新对大 SQL 跑 grep)
        val sqlDump = dir.listFiles()?.find { it.name.endsWith("_baseline.sql") }
        val sqlMsgCount = if (msgCount > 0) msgCount else if (sqlDump != null) {
            try {
                val r = RootGateways.runQuiet("grep -c '^INSERT INTO message ' '${sqlDump.absolutePath}' 2>/dev/null")
                r.trim().toLongOrNull() ?: msgCount
            } catch (_: Exception) { msgCount }
        } else msgCount

        // 附件统计：直接读 file_manifest.json（含全部文件名），不遍历目录
        val counts = mutableMapOf<String, Int>()
        val sizes = mutableMapOf<String, Long>()
        var totalFiles = 0
        var totalSize = 0L
        try {
            val manifestFile = File(dir, "file_manifest.json")
            if (manifestFile.exists()) {
                val manifest = JSONObject(manifestFile.readText())
                val files = manifest.optJSONArray("files") ?: JSONArray()
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val rel = f.optString("path", "").removePrefix("$hash/")
                    val ad = rel.substringBefore("/")
                    if (ad.isBlank()) continue
                    counts[ad] = (counts[ad] ?: 0) + 1
                    val sz = f.optLong("size", 0)
                    sizes[ad] = (sizes[ad] ?: 0L) + sz
                    totalFiles++
                    totalSize += sz
                }
                Log.d(TAG, "readArchiveInfo: $hash manifest files=$totalFiles size=${formatSize(totalSize)}")
            } else {
                Log.d(TAG, "readArchiveInfo: no file_manifest.json for $hash")
            }
        } catch (e: Exception) {
            Log.w(TAG, "readArchiveInfo: manifest parse failed: ${e.message}")
        }

        return ArchiveInfo(
            tag = tag,
            hash = hash,
            backupTime = backupTime,
            backupTimeStr = formatTime(backupTime),
            messageCount = sqlMsgCount,
            messageRowIdFrom = msgRowIdFrom,
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
        val archiveMsgRowIdFrom = archive.messageRowIdFrom
        val archiveMsgRowId = archive.messageRowId
        val phoneMsgRowIdFrom = getPhoneMsgRowIdFrom()
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
            archiveMsgRowIdFrom = archiveMsgRowIdFrom,
            archiveMsgRowId = archiveMsgRowId,
            phoneMsgCount = phoneMsgCount,
            phoneMsgRowIdFrom = phoneMsgRowIdFrom,
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
        // 包元数据已由 scanLocalArchives 通过 JNI 读取；若读取失败（rowid=0 且是包），
        // 选中时再补一次 JNI 读取，仍失败则回退基础信息。
        val usable = if (match.path.endsWith(".tar.zst") || match.path.endsWith(".tar.gz")) {
            if (match.messageRowId > 0) match else readPackageInfo(File(match.path)) ?: match
        } else match
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

    /** 确保 tar 包已解压，返回解压后的目录信息（恢复前调用）。 */
    fun ensureExtracted(archive: ArchiveInfo): ArchiveInfo? {
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
                Log.e(TAG, "ensureExtracted failed: ${result.stderr}")
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
    fun getPhoneStats(password: String? = null): PhoneStats {
        val now = System.currentTimeMillis()
        val cached = phoneStatsCache
        if (cached != null && now - phoneStatsTime < PHONE_STATS_TTL) return cached
        val pw = password ?: getSelectedArchive()?.password ?: "e9cd2ae"
        val pws = "PRAGMA key='$pw';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;"
        // sqlcipher 必须用 App 自带 bin（/data/local/tmp/wxhook_bin），不能依赖 Termux 路径
        val bin = BackupEnv.binDir
        val r = RootGateways.runQuiet(
            "cd $bin && " +
            "export LD_LIBRARY_PATH=$bin && " +
            "./sqlcipher '$PHONE_DB_PATH' \"$pws SELECT count(*) FROM message;SELECT coalesce(min(rowid),0) FROM message;SELECT coalesce(max(rowid),0) FROM message;\" 2>/dev/null | tail -3",
            60_000
        )
        val dbLines = r.lines().filter { it.isNotBlank() && it.all { c -> c.isDigit() } }.takeLast(3)
        val msgCount = dbLines.getOrNull(0)?.toLongOrNull() ?: 0L
        val msgRowIdFrom = dbLines.getOrNull(1)?.toLongOrNull() ?: 0L
        val msgRowId = dbLines.getOrNull(2)?.toLongOrNull() ?: 0L

        // 附件统计：纯 Java 遍历（root 进程内 File.walkTopDown，不依赖 shell find）
        val attDirs = listOf("image2", "voice2", "video", "avatar", "emoji", "cdn")
        val counts = RootGateways.countFiles(attDirs.map { "$PHONE_ATTACH_DIR/$it" })
            .mapKeys { File(it.key).name }
        val stats = PhoneStats(msgCount, msgRowIdFrom, msgRowId, counts)
        phoneStatsCache = stats
        phoneStatsTime = now
        Log.d(TAG, "getPhoneStats: msg=$msgCount rowid=$msgRowIdFrom..$msgRowId atts=${counts.values.sum()}")
        return stats
    }

    fun getPhoneMsgCount(): Long = getPhoneStats().msgCount

    fun getPhoneMsgRowId(): Long = getPhoneStats().msgRowId

    fun getPhoneMsgRowIdFrom(): Long = getPhoneStats().msgRowIdFrom

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
