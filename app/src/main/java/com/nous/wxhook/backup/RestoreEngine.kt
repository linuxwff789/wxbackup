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
    // 使用 app 部署的 wxhook_bin（/data/local/tmp/wxhook_bin），不依赖 termux 路径
    private const val TOOLS_DIR = "/data/local/tmp/wxhook_bin"
    private const val SQLCIPHER = "LD_PRELOAD=${TOOLS_DIR}/libz.so.1:${TOOLS_DIR}/libcrypto.so.3:${TOOLS_DIR}/libedit.so:${TOOLS_DIR}/libncursesw.so.6 ${TOOLS_DIR}/sqlcipher"

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
        // 转换 dump：
        //  - CREATE TABLE/INDEX → IF NOT EXISTS（避免与手机已有表冲突）
        //  - message 表特殊处理：msgId 是 INTEGER PRIMARY KEY 且局部重用，按 msgId 去重
        //    会丢掉几乎全部备份消息——必须按全局唯一的 msgSvrId 去重，并给新行重新编号
        //    msgId = 手机 max(msgId) + 递增
        //  - 其他表 INSERT INTO → INSERT OR IGNORE（主键去重）
        val workDir = "/data/local/tmp/wxhook_restore"
        val convertedSql = "$workDir/baseline_converted.sql"
        RootGateways.run("rm -rf $workDir && mkdir -p $workDir", 10_000)

        // 1. 查询手机 DB 的 message msgSvrId 集合 + max(msgId)（只读，不改动手机 DB）
        val svrIdsFile = "$workDir/phone_msg_svr.txt"
        val maxIdFile = "$workDir/phone_max_msgid.txt"
        val queryScript = """
.output /dev/null
PRAGMA key='$pw';
PRAGMA cipher_compatibility=3;
PRAGMA cipher_page_size=1024;
PRAGMA kdf_iter=4000;
PRAGMA cipher_use_hmac=OFF;
.output '$svrIdsFile'
SELECT msgSvrId FROM message;
.output stdout
SELECT max(msgId) FROM message;
.quit
""".trimIndent()
        val qSql = "$workDir/phone_query.sql"
        RootGateways.run("cat > '$qSql' << 'QEOF'\n$queryScript\nQEOF", 5_000)
        val qr = RootGateways.run("cd $TOOLS_DIR && LD_LIBRARY_PATH=$TOOLS_DIR $SQLCIPHER '$phoneDbPath' < '$qSql' 2>&1 | tail -5", 600_000)
        RootGateways.run("rm -f '$qSql'", 5_000)
        val maxMsgId = RootGateways.runQuiet("cat '$maxIdFile' 2>/dev/null").trim().toLongOrNull() ?: 0L
        if (maxMsgId <= 0L) {
            Log.e(TAG, "mergeDb: cannot read phone max(msgId) (phone db query failed: ${qr.stdout.take(200)})")
            return null
        }
        Log.i(TAG, "mergeDb: phone message maxMsgId=$maxMsgId, svrIds 行数=${RootGateways.runQuiet("wc -l < '$svrIdsFile'").trim()}")

        // 2. awk 转换 dump（posix 语法，兼容 toybox awk；只解析行首两个整数，不碰 unistr 内容）
        val awkFile = "$workDir/convert.awk"
        val awkScript = """
FNR==NR { svr[$1]=1; next }
/^CREATE TABLE / { sub(/CREATE TABLE /, "CREATE TABLE IF NOT EXISTS "); print; next }
/^CREATE UNIQUE INDEX / { sub(/CREATE UNIQUE INDEX /, "CREATE UNIQUE INDEX IF NOT EXISTS "); print; next }
/^CREATE INDEX / { sub(/CREATE INDEX /, "CREATE INDEX IF NOT EXISTS "); print; next }
/^INSERT INTO message VALUES\(/ {
    pos = index($0, "VALUES(") + 7
    s = substr($0, pos)
    c1 = index(s, ",")
    s2 = substr(s, c1+1)
    c2 = index(s2, ",")
    msgsvr = substr(s2, 1, c2-1)
    if (msgsvr in svr) next
    maxid++
    sub(/VALUES\([0-9]+/, "VALUES(" maxid)
    print
    next
}
/^INSERT INTO / { sub(/INSERT INTO /, "INSERT OR IGNORE INTO "); print; next }
{ print }
"""
        RootGateways.writeFile(awkFile, awkScript)
        val convertResult = RootGateways.run(
            "awk -v maxid=$maxMsgId -f '$awkFile' '$svrIdsFile' '${baselineSql.absolutePath}' > '$convertedSql'",
            600_000
        )
        if (!convertResult.isSuccess || RootGateways.runQuiet("test -s '$convertedSql' && echo 1 || echo 0").trim() != "1") {
            Log.e(TAG, "mergeDb: dump convert failed: ${convertResult.stderr.take(300)}")
            return null
        }
        Log.i(TAG, "mergeDb: converted dump 行数=${RootGateways.runQuiet("wc -l < '$convertedSql'").trim()}")

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
PRAGMA key='$pw';
PRAGMA cipher_compatibility=3;
PRAGMA cipher_page_size=1024;
PRAGMA kdf_iter=4000;
PRAGMA cipher_use_hmac=OFF;

-- Apply baseline dump (converted: IF NOT EXISTS + OR IGNORE)
.read '$convertedSql'

-- Stats
SELECT 'merged' AS stat, count(*) AS cnt FROM message;
.quit
""".trimIndent()

        val sqlFile = "/data/local/tmp/wxhook_merge_${System.currentTimeMillis()}.sql"
        RootGateways.run("cat > '$sqlFile' << 'MERGEEOF'\n$script\nMERGEEOF", 5_000)

        val r = RootGateways.run(
            "cd $TOOLS_DIR && LD_LIBRARY_PATH=$TOOLS_DIR $SQLCIPHER < '$sqlFile' 2>&1 | tail -20",
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
        // 选中时不再整包解压；恢复前确保解压完成
        val usable = ArchiveManager.ensureExtracted(archive)
        if (usable == null) {
            progress?.invoke("❌ 存档解压失败，无法恢复")
            Log.e(TAG, "restore: extract failed for ${archive.tag}")
            return false
        }
        progress?.invoke("✅ 存档解压完成")
        val tag = archive.tag
        val pwd = archive.password
        val mergedPath = "/sdcard/Download/wxhook_backup/merged_${tag}_${System.currentTimeMillis()}.db"

        progress?.invoke("🔗 合并数据库...")
        Log.i(TAG, "restore: starting restore for $tag path=${usable.path}")

        // Step 1: Merge
        val merged = mergeDb(usable.path, PHONE_DB, mergedPath, pwd)
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
            copyAttachments(usable.path)
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
