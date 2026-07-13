package com.nous.wxhook.backup

import android.util.Base64
import android.util.Log
import com.nous.wxhook.rootbridge.RootCommandRunner
import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Handles archive creation, compression, and database decryption.
 * All su commands go through RootCommandRunner.
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

    fun compressFileSu(srcPath: String, dstPath: String) {
        try {
            val compressor = if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -c -3" else "gzip -c"
            BackupEnv.su(
                "$compressor \"$srcPath\" > \"$dstPath\" && chmod 644 \"$dstPath\"",
                600_000
            )
        } catch (_: Exception) {}
    }

    fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    // ── Full DB decrypt + dump ──

    fun decryptAndDump(dbPath: String): String {
        val tmpDir = "/sdcard/Download/wxhook_backup/tmp"
        val shPath = "/data/local/tmp/decrypt_full.sh"
        val gzFile = "$tmpDir/EnMicroMsg_baseline" + BackupEnv.ext()
        return try {
            val pwd = getDbPassword()
            val script = "#!/system/bin/sh\n" +
                "mkdir -p $tmpDir\n" +
                "cp \"$dbPath\" $tmpDir/wxhook_dec.db 2>/dev/null\n" +
                "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6' " +
                "${BackupEnv.binDir}/sqlcipher $tmpDir/wxhook_dec.db -batch " +
                "-cmd '.output /dev/null' " +
                "-cmd 'PRAGMA key = \"$pwd\";' " +
                "-cmd 'PRAGMA cipher_compatibility = 3;' " +
                "-cmd 'PRAGMA cipher_page_size = 1024;' " +
                "-cmd 'PRAGMA kdf_iter = 4000;' " +
                "-cmd 'PRAGMA cipher_use_hmac = OFF;' " +
                "-cmd '.output stdout' " +
                "-cmd '.mode insert' " +
                "-cmd 'SELECT * FROM message;' " +
                "2>/dev/null | " +
                (if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -c -3" else "gzip -c") +
                " > $gzFile 2>/dev/null\n"
            val b64 = Base64.encodeToString(
                script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            BackupEnv.su("printf '%s' $b64 | base64 -d > $shPath && chmod 755 $shPath")
            // Use RootCommandRunner with long timeout for decryption
            RootCommandRunner.runSu("sh $shPath > /data/local/tmp/decrypt_exec.log 2>&1", 600_000)
            if (File(gzFile).exists() && File(gzFile).length() > 0) return "OK:$gzFile"
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
        val workOut = "$workDir/wxhook_inc_out.sql.gz"
        val finalOut = "$finalDir/wxhook_inc_out.sql.gz"
        val cleanupWork = "rm -f $workDb $workDb-shm $workDb-wal $workSql $workOut 2>/dev/null"
        val cleanupAll = "$cleanupWork; rm -f $finalOut 2>/dev/null"
        return try {
            val pwd = getDbPassword()
            if (pwd.isEmpty()) return ""

            // Kill existing sqlcipher, setup work dir, cleanup
            RootCommandRunner.runSu(
                "killall sqlcipher 2>/dev/null; mkdir -p $workDir; $cleanupAll", 30_000
            )

            // /proc DB must be copied sequentially to ext4 before SQLCipher opens it.
            val copyResult = RootCommandRunner.runSu(
                "dd if=\"$dbPath\" of=\"$workDb\" bs=4M status=none", 300_000
            )
            if (!copyResult.isSuccess) return ""
            val copiedSize = RootCommandRunner.runSuQuiet(
                "stat -c %s \"$workDb\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            if (copiedSize < 1_000_000L) return ""

            // Run SQLCipher to extract incremental SQL
            val sqlCmd = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6' " +
                "${BackupEnv.binDir}/sqlcipher \"$workDb\" -batch " +
                "-cmd '.output /dev/null' " +
                "-cmd 'PRAGMA key = \"$pwd\";' " +
                "-cmd 'PRAGMA cipher_compatibility = 3;' " +
                "-cmd 'PRAGMA cipher_page_size = 1024;' " +
                "-cmd 'PRAGMA kdf_iter = 4000;' " +
                "-cmd 'PRAGMA cipher_use_hmac = OFF;' " +
                "-cmd '.output stdout' " +
                "-cmd '.mode insert' " +
                "-cmd 'SELECT * FROM message WHERE rowid > $lastRowId;' " +
                "> \"$workSql\" 2>/dev/null"
            val queryResult = RootCommandRunner.runSu(sqlCmd, 300_000)
            if (!queryResult.isSuccess) {
                RootCommandRunner.runSu(cleanupWork, 10_000)
                return ""
            }
            val sqlSize = RootCommandRunner.runSuQuiet(
                "stat -c %s \"$workSql\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            if (sqlSize <= 0L) {
                RootCommandRunner.runSu(cleanupWork, 10_000)
                return ""
            }

            // Compress the SQL dump
            val compressor = if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -c -3" else "gzip -c"
            val gzipResult = RootCommandRunner.runSu(
                "$compressor \"$workSql\" > \"$workOut\"", 120_000
            )
            val outputSize = RootCommandRunner.runSuQuiet(
                "stat -c %s \"$workOut\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            if (!gzipResult.isSuccess || outputSize <= 0L) {
                RootCommandRunner.runSu(cleanupWork, 10_000)
                return ""
            }

            // Move output to final location
            val moved = RootCommandRunner.runSu(
                "cp \"$workOut\" \"$finalOut\" && chmod 664 \"$finalOut\"", 60_000
            ).isSuccess
            RootCommandRunner.runSu(cleanupWork, 10_000)
            if (moved) "OK:$finalOut" else ""
        } catch (e: Exception) {
            RootCommandRunner.runSu(
                "killall sqlcipher 2>/dev/null; rm -f $workDb $workDb-shm $workDb-wal $workOut $finalOut 2>/dev/null",
                10_000
            )
            ""
        }
    }
}
