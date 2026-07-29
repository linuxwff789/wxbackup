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
        val phoneMsgCount: Long,
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
                if (!child.isDirectory) continue
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

        // Count SQL dump message lines
        val sqlDump = dir.listFiles()?.find { it.name.endsWith("_baseline.sql") }
        val sqlMsgCount = if (sqlDump != null) {
            try {
                val r = RootGateways.runQuiet("grep -c '^INSERT INTO message ' '${sqlDump.absolutePath}' 2>/dev/null")
                r.trim().toLongOrNull() ?: msgCount
            } catch (_: Exception) { msgCount }
        } else msgCount

        // Scan attachment directories
        val attDirs = listOf("image2", "voice2", "video", "avatar", "emoji", "cdn")
        val counts = mutableMapOf<String, Int>()
        val sizes = mutableMapOf<String, Long>()
        var totalFiles = 0
        var totalSize = 0L

        for (ad in attDirs) {
            val d = File(dir, ad)
            if (d.exists()) {
                try {
                    val files = d.walkTopDown().filter { it.isFile }.toList()
                    counts[ad] = files.size
                    sizes[ad] = files.sumOf { it.length() }
                    totalFiles += files.size
                    totalSize += sizes[ad] ?: 0L
                    Log.d(TAG, "readArchiveInfo: $hash/$ad: ${files.size} files, ${formatSize(sizes[ad] ?: 0L)}")
                } catch (e: Exception) {
                    Log.w(TAG, "readArchiveInfo: $hash/$ad scan error: ${e.message}")
                }
            }
        }
        Log.d(TAG, "readArchiveInfo: $hash total: $totalFiles attachments, ${formatSize(totalSize)}")

        return ArchiveInfo(
            tag = tag,
            hash = hash,
            backupTime = backupTime,
            backupTimeStr = formatTime(backupTime),
            messageCount = sqlMsgCount,
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
            phoneMsgCount = phoneMsgCount,
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
        Log.i(TAG, "selectArchive: selected ${match.tag} (msgs=${match.messageCount})")
        val json = JSONObject().apply {
            put("tag", match.tag)
            put("hash", match.hash)
            put("backupTime", match.backupTime)
            put("messageCount", match.messageCount)
            put("password", match.password)
            put("path", match.path)
            put("totalAttachmentFiles", match.totalAttachmentFiles)
        }
        RootGateways.run("mkdir -p /data/local/tmp 2>/dev/null")
        RootGateways.run("cat > '$SELECTED_FILE' << 'EOF'\n${json.toString(2)}\nEOF")
        return true
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
                messageCount = j.optLong("messageCount", 0),
                password = j.optString("password", "e9cd2ae"),
                path = j.optString("path", ""),
                totalAttachmentFiles = j.optInt("totalAttachmentFiles", 0),
            )
        } catch (_: Exception) { null }
    }

    // ── Phone state ──

    fun getPhoneMsgCount(): Long {
        val pw = getSelectedArchive()?.password ?: "e9cd2ae"
        val pws = "PRAGMA key='$pw';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;"
        val r = RootGateways.runQuiet(
            "cd /data/data/com.termux/files/home/wxbackup/tools && " +
            "export LD_LIBRARY_PATH=/data/data/com.termux/files/home/wxbackup/tools && " +
            "./sqlcipher '$PHONE_DB_PATH' \"$pws SELECT count(*) FROM message;\" 2>/dev/null | tail -1"
        )
        val count = r.trim().toLongOrNull() ?: 0L
        Log.d(TAG, "getPhoneMsgCount: $count (raw: ${r.trim()})")
        return count
    }

    fun getPhoneAttachmentCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (d in listOf("image2", "voice2", "video", "avatar", "emoji", "cdn")) {
            val r = RootGateways.runQuiet(
                "find '$PHONE_ATTACH_DIR/$d' -type f 2>/dev/null | wc -l"
            )
            counts[d] = r.trim().toIntOrNull() ?: 0
            Log.d(TAG, "getPhoneAttachmentCounts: $d = ${counts[d]}")
        }
        Log.i(TAG, "getPhoneAttachmentCounts: total ${counts.values.sum()}")
        return counts
    }

    // ── Helpers ──

    private fun formatTime(millis: Long): String {
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
