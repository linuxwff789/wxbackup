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
     * 微信当前属主（uid:gid）。
     *
     * 以前这里是硬编码的 `10298`，但本机微信实际是 **10297**（重装/换机后 uid 会变）。
     * 恢复时把 DB/.ini/附件 chown 到不存在的 uid 上，微信（10297）读不了自己的库 →
     * 表现为「数据库损坏」/聊天记录打不开。改为从目标目录实时取属主。
     */
    private fun wechatOwner(): String {
        val out = RootGateways.runQuiet("stat -c '%u:%g' '$PHONE_MM_DIR' 2>/dev/null").trim()
        if (Regex("^\\d+:\\d+$").matches(out)) return out
        val uid = RootGateways.runQuiet(
            "pm list packages -U 2>/dev/null | grep 'package:com.tencent.mm ' | sed 's/.*uid://'"
        ).trim().toIntOrNull()
        return if (uid != null) "$uid:$uid" else "10297:10297"
    }

    /**
     * Replace phone's EnMicroMsg.db with the merged DB file.
     * 1. dd the new DB into place
     * 2. Set ownership (动态取微信 uid) and permissions (0600)
     * 3. Compute MD5 and update EnMicroMsg.db.ini
     * 4. Remove WAL/SHM files
     */
    fun replaceDb(mergedDbPath: String, password: String, owner: String = wechatOwner()): Boolean {
        Log.i(TAG, "replaceDb: $mergedDbPath -> $PHONE_DB (owner=$owner)")

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
        RootGateways.run("chown $owner '$PHONE_DB' 2>/dev/null")
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
            RootGateways.run("chown $owner '$PHONE_INI' 2>/dev/null")
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
     * 覆盖同名文件（恢复语义），目录 0700 / 文件 0600 / 属主 = 微信当前 uid。
     */
    fun copyAttachments(archivePath: String, owner: String = wechatOwner()): Boolean {
        Log.i(TAG, "copyAttachments: from archive=$archivePath (owner=$owner)")
        // 与备份侧共用同一份目录清单（原来这里漏了 favorite/record，恢复永远补不回收藏）
        val attDirs = BackupEnv.ATTACHMENT_DIRS

        for (dir in attDirs) {
            val src = "$archivePath/$dir"
            val dst = "$PHONE_MM_DIR/$dir"
            val exists = RootGateways.runQuiet("test -d '$src' && echo 1").trim()
            if (exists != "1") {
                Log.d(TAG, "copyAttachments: skip $dir (not in archive)")
                continue
            }

            Log.d(TAG, "copyAttachments: copying $dir...")
            // 不用 glob：image2 有 8000+ 个文件，'$src/'* 展开后可能超 ARG_MAX 直接失败，
            // 而失败只记一行 warning → 静默没复制。`cp -r src/. dst/` 覆盖同名文件
            // （恢复语义下就该让存档版本落地），-p 保留 mtime 以便与清单对齐。
            val r = RootGateways.run("cp -r -p '$src/.' '$dst/' 2>&1", 900_000)
            if (!r.isSuccess) {
                Log.w(TAG, "copyAttachments: $dir cp 失败: ${r.stderr.take(200)}")
            }

            // Fix permissions（-exec ... + 批量执行，避免每个文件 fork 一次）
            RootGateways.run("find '$dst/' -type d -exec chmod 0700 {} + 2>/dev/null", 120_000)
            RootGateways.run("find '$dst/' -type f -exec chmod 0600 {} + 2>/dev/null", 120_000)
            RootGateways.run("chown -R $owner '$dst/' 2>/dev/null", 120_000)

            val srcCount = RootGateways.runQuiet("find '$src' -type f 2>/dev/null | wc -l").trim()
            val dstCount = RootGateways.runQuiet("find '$dst/' -type f 2>/dev/null | wc -l").trim()
            Log.i(TAG, "copyAttachments: $dir 存档 $srcCount 个 → 手机 $dstCount 个")
        }

        Log.i(TAG, "copyAttachments: complete")
        return true
    }

    // ── DB Merge ──

    /**
     * 把存档 SQL 合并进「手机库的副本」—— **union 语义**：手机现有数据全部保留，存档历史补进来。
     *
     * 这是两条恢复入口（存档管理 / 备份管理）共用的**唯一**合并实现：
     * - baseline SQL（全量包的 `.dump`）按 `msgSvrId` 去重 + `msgId` 重编号后补入；
     * - 增量 SQL（`incr_X_to_Y.sql`）改写成 `INSERT OR IGNORE` 后按时间顺序补入。
     *
     * @param baselineSql  全量包解出来的 baseline dump 路径（可空）
     * @param incrSqlPaths 增量包解出来的 incr sql 路径（按时间升序）
     * @param phoneDbPath  手机当前 EnMicroMsg.db（只读，不改动）
     * @param outputPath   输出：合并后的加密库
     */
    fun mergePhoneWithArchive(
        baselineSql: String?,
        incrSqlPaths: List<String>,
        phoneDbPath: String,
        outputPath: String,
        password: String,
        progress: ((String) -> Unit)? = null,
    ): Boolean {
        if (baselineSql.isNullOrBlank() && incrSqlPaths.isEmpty()) {
            Log.e(TAG, "mergePhoneWithArchive: 没有可用的存档 SQL")
            return false
        }
        val pw = password.replace("'", "''")
        // 独立 scratch：调用方解出来的 SQL/附件都在 /data/local/tmp/wxhook_restore 下，
        // 这里绝不能整目录 rm，否则把待合并的 SQL 一起删了
        val workDir = "/data/local/tmp/wxhook_restore/merge"
        RootGateways.run("rm -rf $workDir && mkdir -p $workDir", 10_000)
        Log.i(TAG, "mergePhoneWithArchive: baseline=${baselineSql ?: "(none)"} incr=${incrSqlPaths.size} 个")

        // 1. 查询手机 DB 的 message/ImgInfo2 的 msgSvrId 集合 + max(id)（只读）
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
SELECT 'M' || msgSvrId FROM message;
SELECT 'I' || msgSvrId FROM ImgInfo2;
.output stdout
SELECT max(msgId) FROM message;
SELECT max(id) FROM ImgInfo2;
.quit
""".trimIndent()
        val qSql = "$workDir/phone_query.sql"
        RootGateways.run("cat > '$qSql' << 'QEOF'\n$queryScript\nQEOF", 5_000)
        val qr = RootGateways.run("cd $TOOLS_DIR && LD_LIBRARY_PATH=$TOOLS_DIR $SQLCIPHER '$phoneDbPath' < '$qSql' 2>&1 | tail -5", 600_000)
        RootGateways.run("rm -f '$qSql'", 5_000)
        val maxLines = RootGateways.runQuiet("cat '$maxIdFile' 2>/dev/null").lines().filter { it.all(Char::isDigit) }
        val maxMsgId = maxLines.getOrNull(0)?.toLongOrNull() ?: 0L
        val maxImgId = maxLines.getOrNull(1)?.toLongOrNull() ?: 0L
        if (maxMsgId <= 0L) {
            Log.e(TAG, "mergePhoneWithArchive: 读不到手机 max(msgId)（查询失败: ${qr.stdout.take(200)}）")
            return false
        }
        Log.i(TAG, "mergePhoneWithArchive: phone maxMsgId=$maxMsgId maxImgId=$maxImgId")

        // 2. baseline dump 转换（CREATE → IF NOT EXISTS；message/ImgInfo2 按 msgSvrId 去重 + 主键重编号）
        val convertedSql = "$workDir/baseline_converted.sql"
        if (!baselineSql.isNullOrBlank()) {
            val awkFile = "$workDir/convert.awk"
            val awkScript = """
FNR==NR {
    if (substr($1,1,1) == "M") svrM[substr($1,2)] = 1
    else if (substr($1,1,1) == "I") svrI[substr($1,2)] = 1
    next
}
/^CREATE TABLE / { sub(/CREATE TABLE /, "CREATE TABLE IF NOT EXISTS "); print; next }
/^CREATE UNIQUE INDEX / { sub(/^CREATE UNIQUE INDEX /, "CREATE UNIQUE INDEX IF NOT EXISTS "); print; next }
/^CREATE INDEX / { sub(/^CREATE INDEX /, "CREATE INDEX IF NOT EXISTS "); print; next }
/^INSERT INTO message VALUES\(/ {
    pos = index($0, "VALUES(") + 7
    s = substr($0, pos)
    c1 = index(s, ",")
    s2 = substr(s, c1+1)
    c2 = index(s2, ",")
    msgsvr = substr(s2, 1, c2-1)
    if (msgsvr in svrM) next
    maxid++
    sub(/VALUES\([0-9]+/, "VALUES(" maxid)
    print
    if (++cnt % 10000 == 0) { print "COMMIT;"; print "BEGIN TRANSACTION;"; }
    next
}
/^INSERT INTO ImgInfo2 VALUES\(/ {
    pos = index($0, "VALUES(") + 7
    s = substr($0, pos)
    c1 = index(s, ",")
    id = substr(s, 1, c1-1)
    s2 = substr(s, c1+1)
    c2 = index(s2, ",")
    msgsvr = substr(s2, 1, c2-1)
    if (msgsvr in svrI) next
    maximgid++
    sub(/VALUES\([0-9]+/, "VALUES(" maximgid)
    print
    if (++cnt % 10000 == 0) { print "COMMIT;"; print "BEGIN TRANSACTION;"; }
    next
}
/^INSERT INTO / { sub(/INSERT INTO /, "INSERT OR IGNORE INTO "); print; if (++cnt % 10000 == 0) { print "COMMIT;"; print "BEGIN TRANSACTION;"; } next }
{ print }
""".trimIndent()
            RootGateways.writeFile(awkFile, awkScript)
            progress?.invoke("🔗 转换基线 SQL...")
            val convertResult = RootGateways.run(
                "awk -v maxid=$maxMsgId -v maximgid=$maxImgId -f '$awkFile' '$svrIdsFile' '$baselineSql' > '$convertedSql'",
                900_000
            )
            if (!convertResult.isSuccess || RootGateways.runQuiet("test -s '$convertedSql' && echo 1 || echo 0").trim() != "1") {
                Log.e(TAG, "mergePhoneWithArchive: baseline 转换失败: ${convertResult.stderr.take(300)}")
                return false
            }
            Log.i(TAG, "mergePhoneWithArchive: baseline 转换完成 行数=${RootGateways.runQuiet("wc -l < '$convertedSql'").trim()}")
        }

        // 3. 增量 SQL：INSERT → INSERT OR IGNORE，按时间顺序拼到一个文件
        val incrConverted = "$workDir/incr_converted.sql"
        if (incrSqlPaths.isNotEmpty()) {
            RootGateways.run("rm -f '$incrConverted' && touch '$incrConverted'", 5_000)
            for (incr in incrSqlPaths) {
                RootGateways.run(
                    "sed 's/^INSERT INTO /INSERT OR IGNORE INTO /' '$incr' >> '$incrConverted'",
                    900_000
                )
            }
            Log.i(TAG, "mergePhoneWithArchive: 增量 SQL ${incrSqlPaths.size} 个 行数=${RootGateways.runQuiet("wc -l < '$incrConverted'").trim()}")
        }

        // 4. 合并：以手机库副本为底（.save），依次 .read 基线与增量
        val script = buildString {
            appendLine(".output /dev/null")
            appendLine("PRAGMA key='$pw';")
            appendLine("PRAGMA cipher_compatibility=3;")
            appendLine("PRAGMA cipher_page_size=1024;")
            appendLine("PRAGMA kdf_iter=4000;")
            appendLine("PRAGMA cipher_use_hmac=OFF;")
            appendLine("")
            appendLine("-- 输出 = 手机库副本（union 语义：手机数据保留）")
            appendLine(".save '$outputPath'")
            appendLine(".open '$outputPath'")
            appendLine("PRAGMA key='$pw';")
            appendLine("PRAGMA cipher_compatibility=3;")
            appendLine("PRAGMA cipher_page_size=1024;")
            appendLine("PRAGMA kdf_iter=4000;")
            appendLine("PRAGMA cipher_use_hmac=OFF;")
            if (!baselineSql.isNullOrBlank()) appendLine(".read '$convertedSql'")
            if (incrSqlPaths.isNotEmpty()) appendLine(".read '$incrConverted'")
            appendLine("SELECT 'merged' AS stat, count(*) AS cnt FROM message;")
            appendLine(".quit")
        }
        val sqlFile = "/data/local/tmp/wxhook_merge_${System.currentTimeMillis()}.sql"
        RootGateways.writeFile(sqlFile, script)
        progress?.invoke("🔗 合并数据库（手机 ∪ 存档）...")
        val r = RootGateways.run(
            "cd $TOOLS_DIR && LD_LIBRARY_PATH=$TOOLS_DIR $SQLCIPHER < '$sqlFile' 2>&1 | tail -20",
            1_800_000
        )
        RootGateways.run("rm -f '$sqlFile' 2>/dev/null")
        if (!r.isSuccess) {
            Log.e(TAG, "mergePhoneWithArchive: sqlcipher 失败: ${r.stderr.take(200)}")
            return false
        }
        if (RootGateways.runQuiet("test -s '$outputPath' && echo 1 || echo 0").trim() != "1") {
            Log.e(TAG, "mergePhoneWithArchive: 输出为空")
            return false
        }
        Log.i(TAG, "mergePhoneWithArchive: done size=${RootGateways.runQuiet("stat -c %s '$outputPath' 2>/dev/null").trim()} (${r.stdout.trim().takeLast(80)})")
        return true
    }
}
