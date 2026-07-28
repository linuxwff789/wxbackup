package com.nous.wxhook.backup

import android.util.Log
import com.nous.wxhook.backup.ArchiveManager.ArchiveInfo
import com.nous.wxhook.root.RootGateways
import java.io.File

/**
 * Restore engine — merges archive DB with phone, replaces EnMicroMsg.db,
 * updates .db.ini, and copies attachment files via data_mirror.
 *
 * All shell commands go through RootGateways (su).
 */
object RestoreEngine {

    private const val TAG = "wxhook:Restore"

    private const val PHONE_MM_DIR = "/data_mirror/data_ce/null/0/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
    private const val PHONE_DB = "$PHONE_MM_DIR/EnMicroMsg.db"
    private const val PHONE_INI = "$PHONE_MM_DIR/EnMicroMsg.db.ini"
    private const val SQLCIPHER = "LD_PRELOAD=/data/data/com.termux/files/home/wxbackup/tools/libz.so.1:/data/data/com.termux/files/home/wxbackup/tools/libcrypto.so.3:/data/data/com.termux/files/home/wxbackup/tools/libedit.so:/data/data/com.termux/files/home/wxbackup/tools/libncursesw.so.6 /data/data/com.termux/files/home/wxbackup/tools/sqlcipher"
    private const val TOOLS_DIR = "/data/data/com.termux/files/home/wxbackup/tools"

    // ── Replace DB ──

    /**
     * Replace phone's EnMicroMsg.db with the merged DB file.
     * 1. dd the new DB into place
     * 2. Set ownership (u0_a298 = 10298) and permissions (0600)
     * 3. Compute MD5 and update EnMicroMsg.db.ini
     * 4. Remove WAL/SHM files
     */
    fun replaceDb(mergedDbPath: String, password: String): Boolean {
        Log.i(TAG, "replaceDb: $mergedDbPath -> $PHONE_DB (pwd=$password)")

        // 1. Write DB via dd (faster than cp for large files)
        val ddResult = RootGateways.run(
            "dd if='$mergedDbPath' of='$PHONE_DB' bs=4M 2>&1",
            300_000
        )
        if (!ddResult.isSuccess) {
            Log.e(TAG, "replaceDb: dd failed: ${ddResult.stderr}")
            return false
        }
        Log.i(TAG, "replaceDb: dd write complete")

        // 2. Ownership and permissions
        RootGateways.run("chown 10298:10298 '$PHONE_DB' 2>/dev/null")
        RootGateways.run("chmod 0600 '$PHONE_DB' 2>/dev/null")

        // 3. Remove stale WAL/SHM
        RootGateways.run("rm -f '$PHONE_MM_DIR/EnMicroMsg.db-wal' 2>/dev/null")
        RootGateways.run("rm -f '$PHONE_MM_DIR/EnMicroMsg.db-shm' 2>/dev/null")
        RootGateways.run("rm -f '$PHONE_MM_DIR/EnMicroMsg.db.sm' 2>/dev/null")
        Log.d(TAG, "replaceDb: cleaned WAL/SHM/SM")

        // 4. Compute MD5 and update .ini
        val md5 = RootGateways.runQuiet("md5sum '$PHONE_DB' 2>/dev/null | cut -d' ' -f1").trim()
        if (md5.length != 32) {
            Log.w(TAG, "replaceDb: invalid md5: $md5")
        } else {
            Log.i(TAG, "replaceDb: DB md5=$md5")
            val iniContent = "#\n#${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\ncreatemd5=$md5\n"
            RootGateways.run("cat > '$PHONE_INI' << 'INIEOF'\n$iniContent\nINIEOF")
            RootGateways.run("chown 10298:10298 '$PHONE_INI' 2>/dev/null")
            RootGateways.run("chmod 0600 '$PHONE_INI' 2>/dev/null")
            Log.d(TAG, "replaceDb: .ini updated with createmd5=$md5")
        }

        // 5. Verify
        val size = RootGateways.runQuiet("stat -c %s '$PHONE_DB' 2>/dev/null").trim()
        Log.i(TAG, "replaceDb: done, size=$size")
        return size.toLongOrNull() ?: 0L > 100_000L
    }

    // ── Copy attachments ──

    /**
     * Copy attachment files from archive's extracted directory to phone.
     * Uses cp -rn (no overwrite), then fixes permissions:
     *   directories → 0700, files → 0600, owner → u0_a298
     */
    fun copyAttachments(archivePath: String): Boolean {
        Log.i(TAG, "copyAttachments: from archive=$archivePath")
        val attDirs = listOf("image2", "voice2", "video", "avatar", "emoji", "cdn")
        var totalCopied = 0
        var totalErrors = 0

        for (dir in attDirs) {
            val src = "$archivePath/$dir"
            val dst = "$PHONE_MM_DIR/$dir"
            val exists = RootGateways.runQuiet("test -d '$src' && echo 1").trim()
            if (exists != "1") {
                Log.d(TAG, "copyAttachments: skip $dir (not in archive)")
                continue
            }

            Log.d(TAG, "copyAttachments: copying $dir...")
            val r = RootGateways.run(
                "cp -rn '$src/'* '$dst/' 2>&1",
                120_000
            )
            if (!r.isSuccess) {
                Log.w(TAG, "copyAttachments: $dir cp had warnings: ${r.stderr.take(200)}")
            }

            // Fix permissions
            RootGateways.run("find '$dst/' -type d -exec chmod 0700 {} \\; 2>/dev/null", 60_000)
            RootGateways.run("find '$dst/' -type f -exec chmod 0600 {} \\; 2>/dev/null", 60_000)
            RootGateways.run("chown -R 10298:10298 '$dst/' 2>/dev/null", 30_000)

            val count = RootGateways.runQuiet("find '$dst/' -type f 2>/dev/null | wc -l").trim()
            Log.i(TAG, "copyAttachments: $dir done, now $count files")
        }

        Log.i(TAG, "copyAttachments: complete")
        return true
    }

    // ── DB Merge ──

    /**
     * Merge archive's baseline SQL dump with phone's current encrypted DB.
     * Uses sqlcipher to ATTACH both, INSERT OR IGNORE from baseline into a copy of phone DB.
     *
     * @param archivePath path to extracted archive directory (contains _baseline.sql)
     * @param phoneDbPath path to current phone EnMicroMsg.db
     * @param outputPath  where to write the merged result
     * @param password    encryption key
     * @return outputPath on success, null on failure
     */
    fun mergeDb(
        archivePath: String,
        phoneDbPath: String,
        outputPath: String,
        password: String,
    ): String? {
        Log.i(TAG, "mergeDb: archive=$archivePath phone=$phoneDbPath out=$outputPath")

        // Find baseline SQL
        val dir = File(archivePath)
        val baselineSql = dir.listFiles()?.find { it.name.endsWith("_baseline.sql") }
        if (baselineSql == null) {
            Log.w(TAG, "mergeDb: no _baseline.sql found in $archivePath")
            // Fallback: just copy phone DB as-is, no merge
            Log.i(TAG, "mergeDb: no baseline, copying phone DB directly")
            val r = RootGateways.run("cp '$phoneDbPath' '$outputPath'", 30_000)
            return if (r.isSuccess) outputPath else null
        }
        Log.i(TAG, "mergeDb: baseline SQL=${baselineSql.name} size=${baselineSql.length()}")

        // Build merge SQL script
        val pw = password.replace("'", "''")
        val script = """
.output /dev/null
PRAGMA key='$pw';
PRAGMA cipher_compatibility=3;
PRAGMA cipher_page_size=1024;
PRAGMA kdf_iter=4000;
PRAGMA cipher_use_hmac=OFF;

-- Create output as copy of phone DB
.save '$outputPath'
.open '$outputPath'

-- Attach and read baseline SQL (INSERT OR IGNORE for dedup)
.read '${baselineSql.absolutePath}'

-- Stats
SELECT 'merged' AS stat, count(*) AS cnt FROM message;
.quit
""".trimIndent()

        val sqlFile = "/data/local/tmp/wxhook_merge_${System.currentTimeMillis()}.sql"
        RootGateways.run("cat > '$sqlFile' << 'MERGEEOF'\n$script\nMERGEEOF", 5_000)

        val r = RootGateways.run(
            "cd $TOOLS_DIR && LD_LIBRARY_PATH=$TOOLS_DIR $SQLCIPHER < '$sqlFile' 2>/dev/null | tail -5",
            600_000
        )
        RootGateways.run("rm -f '$sqlFile' 2>/dev/null")

        if (!r.isSuccess) {
            Log.e(TAG, "mergeDb: sqlcipher failed: ${r.stderr.take(200)}")
            return null
        }

        val merged = RootGateways.runQuiet("test -s '$outputPath' && echo 1 || echo 0").trim()
        if (merged != "1") {
            Log.e(TAG, "mergeDb: output file empty or missing")
            return null
        }

        val size = RootGateways.runQuiet("stat -c %s '$outputPath' 2>/dev/null").trim()
        Log.i(TAG, "mergeDb: done, output size=$size")
        return outputPath
    }

    // ── Full restore ──

    /**
     * Run the full restore flow:
     * 1. Merge DB (archive baseline + phone current → merged)
     * 2. Replace phone's EnMicroMsg.db
     * 3. Copy attachments from archive to phone
     *
     * @param archive the selected archive info
     * @param progress callback for UI updates (runs on calling thread)
     */
    fun restore(
        archive: ArchiveInfo,
        progress: ((String) -> Unit)? = null,
    ): Boolean {
        val tag = archive.tag
        val pwd = archive.password
        val mergedPath = "/sdcard/Download/wxhook_backup/merged_${tag}_${System.currentTimeMillis()}.db"

        progress?.invoke("🔗 合并数据库...")
        Log.i(TAG, "restore: starting restore for $tag")

        // Step 1: Merge
        val merged = mergeDb(archive.path, PHONE_DB, mergedPath, pwd)
        if (merged == null) {
            Log.e(TAG, "restore: merge failed")
            progress?.invoke("❌ 合并失败")
            return false
        }
        progress?.invoke("✅ 数据库合并完成")

        // Step 2: Replace
        progress?.invoke("💾 替换手机 DB...")
        Log.i(TAG, "restore: replacing phone DB")
        if (!replaceDb(merged, pwd)) {
            Log.e(TAG, "restore: replaceDb failed")
            progress?.invoke("❌ DB 替换失败")
            return false
        }
        progress?.invoke("✅ DB 替换完成")

        // Step 3: Attachments
        if (archive.totalAttachmentFiles > 0) {
            progress?.invoke("📁 复制附件...")
            Log.i(TAG, "restore: copying ${archive.totalAttachmentFiles} attachments")
            copyAttachments(archive.path)
            progress?.invoke("✅ 附件复制完成")
        } else {
            Log.d(TAG, "restore: no attachments to copy")
        }

        // Step 4: Remove corrupted dir
        RootGateways.run("rm -rf '$PHONE_MM_DIR/corrupted' 2>/dev/null")
        Log.i(TAG, "restore: cleared corrupted/ dir")

        progress?.invoke("✅ 恢复完成，可启动微信验证")
        Log.i(TAG, "restore: complete for $tag")
        return true
    }
}
