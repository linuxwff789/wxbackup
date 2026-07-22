package com.nous.wxhook.backup

import android.util.Base64
import android.util.Log
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Handles archive creation, compression, and database decryption.
 * All su commands go through RootGateways.
 */
object ArchiveService {

    private var cachedPassword: String? = null

    // ── Password ──

    fun getDbPassword(): String {
        if (cachedPassword != null) return cachedPassword!!
        cachedPassword = try {
            val raw = BackupEnv.suOut("cat /data/local/tmp/.wechat_key 2>/dev/null")
            // Parse key=hex format: key=65396364326165 → e9cd2ae
            val keyLine = raw.lines().firstOrNull { it.startsWith("key=") }
            if (keyLine != null) {
                val hex = keyLine.removePrefix("key=").trim()
                var pwd = ""
                for (i in hex.indices step 2) {
                    if (i + 1 < hex.length) {
                        val byte = hex.substring(i, i + 2).toIntOrNull(16) ?: continue
                        if (byte > 0) pwd += byte.toChar()
                    }
                }
                if (pwd.isNotEmpty()) pwd else hex
            } else {
                val cfg = File(BackupEnv.backupDir, "db_config.json")
                if (cfg.exists()) JSONObject(cfg.readText()).optString("password", "") else ""
            }
        } catch (_: Exception) { "" }
        return cachedPassword ?: ""
    }

    // ── Compression ──

    fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    // ── Full DB decrypt → 直接生成未加密的 .db 文件 ──

    fun decryptAndDump(dbPath: String): String {
        val tmpDir = "/data/local/tmp/wxhook_backup"
        val shPath = "$tmpDir/decrypt_full.sh"
        val outputName = FullBackupLayout.databaseDumpName()
        val outputFile = "$tmpDir/$outputName"
        return try {
            val pwd = getDbPassword()
            val b64 = Base64.encodeToString(
                ("#!/system/bin/sh\n" +
                "mkdir -p $tmpDir\n" +
                "rm -f $outputFile $tmpDir/wxhook_dec.db 2>/dev/null\n" +
                "cp \"$dbPath\" $tmpDir/wxhook_dec.db 2>/dev/null\n" +
                "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6' " +
                "${BackupEnv.binDir}/sqlcipher \"$tmpDir/wxhook_dec.db\" << 'SQL_EOF' > /dev/null 2>&1\n" +
                "PRAGMA key='$pwd';\n" +
                "PRAGMA cipher_compatibility=3;\n" +
                "PRAGMA cipher_page_size=1024;\n" +
                "PRAGMA kdf_iter=4000;\n" +
                "PRAGMA cipher_use_hmac=OFF;\n" +
                "ATTACH DATABASE '$outputFile' AS plain KEY '';\n" +
                "SELECT sqlcipher_export('plain');\n" +
                "DETACH plain;\n" +
                "SQL_EOF\n" +
                "rm -f $tmpDir/wxhook_dec.db $tmpDir/wxhook_dec.db-shm $tmpDir/wxhook_dec.db-wal 2>/dev/null"
                ).toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            RootGateways.run(
                "mkdir -p \"$tmpDir\" && printf '%s' $b64 | base64 -d > \"$shPath\" && chmod 700 \"$shPath\" && sh \"$shPath\" > /data/local/tmp/decrypt_exec.log 2>&1",
                600_000,
            )
            if (BackupEnv.suOut("test -s \"$outputFile\" && echo 1").trim() == "1") return "OK:$outputFile"
            ""
        } catch (e: Exception) {
            Log.e("wxhook:Backup", "decryptAndDump: $e")
            ""
        }
    }

    // ── Incremental DB decrypt + dump ──

    fun decryptIncremental(dbPath: String, lastRowId: Long): String {
        val finalDir = "/sdcard/Download/wxhook_backup/tmp"
        val workDir = "/data/local/tmp/wxhook_backup"
        val workDb = "$workDir/wxhook_inc.db"
        val workSql = "$workDir/wxhook_inc_out.sql"
        val finalOut = "$finalDir/wxhook_inc_out.sql"
        val cleanupWork = "rm -f $workDb $workDb-shm $workDb-wal $workSql 2>/dev/null"
        val cleanupAll = "$cleanupWork; rm -f $finalOut 2>/dev/null"
        return try {
            val pwd = getDbPassword()
            if (pwd.isEmpty()) return ""

            // Kill existing sqlcipher, setup work dir, cleanup
            RootGateways.run(
                "killall sqlcipher 2>/dev/null; mkdir -p $workDir; $cleanupAll", 30_000
            )

            // /proc DB must be copied sequentially to ext4 before SQLCipher opens it.
            val copyResult = RootGateways.run(
                "dd if=\"$dbPath\" of=\"$workDb\" bs=4M status=none", 300_000
            )
            if (!copyResult.isSuccess) return ""
            val copiedSize = RootGateways.runQuiet(
                "stat -c %s \"$workDb\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            if (copiedSize < 1_000_000L) return ""

            // Run SQLCipher to extract incremental SQL
            // Write SQL commands to a script file first, then pipe to sqlcipher
            val sqlScript = "$workDir/incr_query.sql"
            val scriptContent = ".output /dev/null\n" +
                "PRAGMA key = '$pwd';\n" +
                "PRAGMA cipher_compatibility = 3;\n" +
                "PRAGMA cipher_page_size = 1024;\n" +
                "PRAGMA kdf_iter = 4000;\n" +
                "PRAGMA cipher_use_hmac = OFF;\n" +
                ".output stdout\n" +
                ".mode insert\n" +
                "SELECT * FROM message WHERE rowid > $lastRowId;\n"
            RootGateways.runQuiet("printf '%s' '${scriptContent.replace("'", "'\\''")}' > $sqlScript")
            val sqlCmd = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6' " +
                "${BackupEnv.binDir}/sqlcipher \"$workDb\" < $sqlScript > \"$workSql\" 2>/dev/null"
            val queryResult = RootGateways.run(sqlCmd, 300_000)
            if (!queryResult.isSuccess) {
                RootGateways.run(cleanupWork, 10_000)
                return ""
            }
            val sqlSize = RootGateways.runQuiet(
                "stat -c %s \"$workSql\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            if (sqlSize <= 0L) {
                RootGateways.run(cleanupWork, 10_000)
                return ""
            }

            // Output raw SQL (no extra compression — tar zstd handles it)
            RootGateways.run("mkdir -p $finalDir", 5_000)
            RootGateways.run("cp \"$workSql\" \"$finalOut\" && chmod 664 \"$finalOut\"", 60_000)
            val outputSize = RootGateways.runQuiet(
                "stat -c %s \"$finalOut\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            RootGateways.run(cleanupWork, 10_000)
            if (outputSize > 0L) "OK:$finalOut" else ""
        } catch (e: Exception) {
            RootGateways.run(
                "killall sqlcipher 2>/dev/null; rm -f $workDb $workDb-shm $workDb-wal $finalOut 2>/dev/null",
                10_000
            )
            ""
        }
    }
}
