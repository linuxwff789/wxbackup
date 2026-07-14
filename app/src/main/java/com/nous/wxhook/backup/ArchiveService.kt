package com.nous.wxhook.backup
import com.nous.wxhook.core.command.CommandResult

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

    fun compressFileSu(srcPath: String, dstPath: String): Boolean {
        return try {
            // 先验证源文件存在且大小合理
            val srcSize = suOut("stat -c %s \\\"$srcPath\\\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (srcSize < 1000L) return false

            val compressor = if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -c -3" else "gzip -c"
            val result = su(
                "$compressor \\\"$srcPath\\\" > \\\"$dstPath\\\" && chmod 644 \\\"$dstPath\\\"",
                600_000
            )

            // 验证压缩结果
            val dstSize = suOut("stat -c %s \\\"$dstPath\\\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (dstSize <= 0L) return false

            // 验证压缩比合理（压缩后应该比源文件小）
            if (dstSize >= srcSize) {
                // 压缩失败，删除无效文件
                su("rm -f \\\"$dstPath\\\"")
                return false
            }

            // 验证文件头
            val header = suOut("xxd -l 2 \\\"$dstPath\\\" 2>/dev/null").trim()
            val expectedHeader = if (BackupEnv.useZstd()) "28b52ffd" else "1f8b"
            if (!header.contains(expectedHeader)) {
                su("rm -f \\\"$dstPath\\\"")
                return false
            }

            true
        } catch (e: Exception) {
            Log.e("wxhook:Backup", "compressFileSu failed: $e")
            false
        }
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
            val sqlScript = "/data/local/tmp/decrypt_full.sql"
            val scriptContent = ".output /dev/null\n" +
                "PRAGMA key = '$pwd';\n" +
                "PRAGMA cipher_compatibility = 3;\n" +
                "PRAGMA cipher_page_size = 1024;\n" +
                "PRAGMA kdf_iter = 4000;\n" +
                "PRAGMA cipher_use_hmac = OFF;\n" +
                ".output stdout\n" +
                ".mode insert\n" +
                "SELECT * FROM message;\n"
            val script = "#!/system/bin/sh\n" +
                "mkdir -p $tmpDir\n" +
                "cp \"$dbPath\" $tmpDir/wxhook_dec.db 2>/dev/null\n" +
                "printf '%s' '${scriptContent.replace("'", "'\\''")}' > $sqlScript\n" +
                "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6' " +
                "${BackupEnv.binDir}/sqlcipher $tmpDir/wxhook_dec.db < $sqlScript " +
                (if (BackupEnv.useZstd()) "2>/dev/null | ${BackupEnv.binDir}/zstd -c -3" else "2>/dev/null | gzip -c") +
                " > $gzFile 2>/dev/null\n"
            val b64 = Base64.encodeToString(
                script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            BackupEnv.su("printf '%s' $b64 | base64 -d > $shPath && chmod 755 $shPath")
            // Use RootGateways with long timeout for decryption
            RootGateways.run("sh $shPath > /data/local/tmp/decrypt_exec.log 2>&1", 600_000)
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

            // Compress the SQL dump
            val compressor = if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -c -3" else "gzip -c"
            val gzipResult = RootGateways.run(
                "$compressor \"$workSql\" > \"$workOut\"", 120_000
            )
            val outputSize = RootGateways.runQuiet(
                "stat -c %s \"$workOut\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            if (!gzipResult.isSuccess || outputSize <= 0L) {
                RootGateways.run(cleanupWork, 10_000)
                return ""
            }

            // Move output to final location
            val moved = RootGateways.run(
                "cp \"$workOut\" \"$finalOut\" && chmod 664 \"$finalOut\"", 60_000
            ).isSuccess
            RootGateways.run(cleanupWork, 10_000)
            if (moved) "OK:$finalOut" else ""
        } catch (e: Exception) {
            RootGateways.run(
                "killall sqlcipher 2>/dev/null; rm -f $workDb $workDb-shm $workDb-wal $workOut $finalOut 2>/dev/null",
                10_000
            )
            ""
        }
    }
}
