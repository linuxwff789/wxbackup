package com.nous.wxhook.rootbridge.backup

import org.json.JSONArray
import org.json.JSONObject
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup system:
 * - Files (images/voice/video etc.) → git version control (auto dedup + incremental)
 * - Database → custom incremental (baseline SQL + incremental SQL dumps)
 * - Each backup = git commit + DB incremental dump
 */
object BackupHookLocal {

    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"
    private const val DB_CONFIG_FILE = "db_config.json"
    private const val DB_STATE_FILE = "db_state.json"
    private const val RCLONE_REMOTE = "gdrive:wxhook-backup"
    private const val BACKUP_DIR = "/sdcard/Download/wxhook_backup"
    private var binDir = "/data/data/com.termux/files/usr/bin"
    private var filesDirPath = "/data/local/tmp"
    val binPath: String get() = binDir

    fun init(ctx: android.content.Context) {
        binDir = "/data/local/tmp/wxhook_bin"
        filesDirPath = ctx.filesDir.absolutePath
        rcloneConfigPath = ctx.filesDir.absolutePath + "/.config/rclone/rclone.conf"
        // Ensure backup dir exists (app process, not root)
        try { java.io.File(BACKUP_DIR).mkdirs() } catch (_: Exception) {}
        try { java.io.File("$BACKUP_DIR/tmp").mkdirs() } catch (_: Exception) {}
        // Prevent Android MediaStore from scanning backup images
        RootCommandRunner.runSu("touch $BACKUP_DIR/.nomedia && chmod 644 $BACKUP_DIR/.nomedia", 10_000)
        // Fix DNS for rclone (Go resolver needs /etc/resolv.conf)
        RootCommandRunner.runSu(
            "mkdir -p /data/local/tmp/etc && echo 'nameserver 8.8.8.8' > /data/local/tmp/etc/resolv.conf && " +
            "mount --bind /data/local/tmp/etc /etc 2>/dev/null; " +
            "echo 'nameserver 8.8.4.4' >> /etc/resolv.conf 2>/dev/null", 10_000)
    }
    private var rcloneConfigPath = ""
    private fun filesDirForWrite() = File(filesDirPath).apply { mkdirs() }
    private fun su(cmd: String, timeoutMs: Long = 60_000) = RootCommandRunner.runSu(cmd, timeoutMs)
    private fun suOut(cmd: String, timeoutMs: Long = 60_000) = RootCommandRunner.runSuQuiet(cmd, timeoutMs)
    private fun suCopy(tmp: File, dest: File, mode: String = "644") {
        su("cp \"${tmp.absolutePath}\" \"${dest.absolutePath}\" && chmod $mode \"${dest.absolutePath}\"")
    }

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    private val ATT_DIRS = listOf("image2", "voice2", "video", "emoji", "avatar", "cdn", "record", "favorite")

    fun doFullBackup(callback: ProgressCallback? = null): Result {
        val startTime = System.currentTimeMillis()
        return try {
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(BACKUP_DIR); if (!dir.exists()) dir.mkdirs()
            var totalFiles = 0L; var totalSize = 0L

            // 1. Find WeChat users
            val wxPaths = findWxPaths()
            if (wxPaths.isEmpty()) return Result(false, "微信未运行或未找到数据")

            // 2. Backup DB (baseline)
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                callback?.onProgress("[$userHash] 数据库基线...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val dbGzFile = File(userDir, "EnMicroMsg_baseline" + ext())
                // Decrypt + gzip (fixed printf '%s' + /data/local/tmp/ script)
                val decResult = decryptAndDump(dbSrc)
                if (decResult.startsWith("OK:")) {
                    val gzPath = decResult.substring(3)
                    val gzFile = java.io.File(gzPath)
                    if (gzFile.exists()) {
                        gzFile.renameTo(dbGzFile)
                        totalFiles++; totalSize += backupSize(gzPath)
                    }
                }
                if (!backupExists(dbGzFile.absolutePath)) {
                    compressFileSu(dbSrc, dbGzFile.absolutePath)
                    if (backupExists(dbGzFile.absolutePath)) { totalFiles++; totalSize += backupSize(dbGzFile.absolutePath) }
                }

                // Save DB state
                val maxRowId = runCatching<Long> {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", 
                        "LD_PRELOAD='/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6' " +
                        "/data/local/sqlcipher /sdcard/Download/wxhook_backup/tmp/wxhook_dec.db " +
                        "-cmd 'PRAGMA key = \"e9cd2ae\";' -cmd 'PRAGMA cipher_compatibility=3;' -cmd 'PRAGMA cipher_page_size=1024;' -cmd 'PRAGMA kdf_iter=4000;' -cmd 'PRAGMA cipher_use_hmac=OFF;' " +
                        "-cmd 'SELECT max(rowid) FROM message;' 2>/dev/null"))
                    p.waitFor()
                    p.inputStream.bufferedReader().readLines().lastOrNull { it.all { it.isDigit() } }?.toLong() ?: 0L
                }.getOrDefault(0L)
                saveDbState(userDir, tag, maxRowId)
            }

            // 3. Backup attachments (git will handle dedup)
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                for (attDir in ATT_DIRS) {
                    callback?.onProgress("[$userHash] $attDir...", totalFiles, totalSize)
                    val src = "$wxBasePath/$attDir"
                    val dst = "${userDir.absolutePath}/$attDir"
                    try {
                        su("mkdir -p $dst")
                        su("cp -r " + src + " " + dst + " 2>/dev/null && chmod -R 644 " + dst + " 2>/dev/null")
                        val d = File(dst)
                        if (d.exists()) {
                            totalFiles += d.walkTopDown().filter { it.isFile }.count()
                            totalSize += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("wxhook:Backup", "Copy $userHash/$attDir failed: $e")
                    }
                }
            }

            // 4. Git commit
            val gitHash = gitAddAndCommit(tag)
            rcloneSync(callback)

            // 5. Save config and records
            saveDbConfig()
            saveState(tag, totalFiles, totalSize)
            if (gitHash.isNotEmpty()) {
                try {
                    val stateFile = File(BACKUP_DIR, STATE_FILE)
                    val st = JSONObject(backupRead(stateFile.absolutePath))
                    st.put("gitCommit", gitHash)
                    backupWrite(stateFile.absolutePath, st.toString())
                    for (d in File(BACKUP_DIR).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("tmp") } ?: emptyList()) {
                        val dbStateFile = File(d, DB_STATE_FILE)
                        if (backupExists(dbStateFile.absolutePath)) {
                            val dbst = JSONObject(backupRead(dbStateFile.absolutePath))
                            dbst.put("gitCommit", gitHash)
                            backupWrite(dbStateFile.absolutePath, dbst.toString())
                        }
                    }
                } catch (_: Exception) {}
            }
            addRecord(createRecord(tag, "full", totalFiles, totalSize, "全量备份完成", durationMs = System.currentTimeMillis() - startTime))
            val gitMsg = if (gitHash.isNotEmpty()) " git:$gitHash" else " (git无commit)"
            Result(true, "全量备份完成: ${totalFiles}个文件, ${formatSize(totalSize)}$gitMsg")
        } catch (e: Exception) { Result(false, "备份失败: ${e.message}") }
    }

    fun doIncrementalBackup(callback: ProgressCallback? = null): Result {
        val startTime = System.currentTimeMillis()
        android.util.Log.e("wxhook:CLICK", "BackupHookLocal.doIncrementalBackup enter")
        return try {
            val state = loadState()
            val lastTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(BACKUP_DIR)
            var totalFiles = 0L; var totalSize = 0L; var newFiles = 0L

            val wxPaths = findWxPaths()
            if (wxPaths.isEmpty()) return Result(false, "微信未运行或未找到数据")

            var incrFrom = 0L
            var incrTo = 0L
            // 1. DB incremental
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                val dbState = loadDbState(userDir)
                android.util.Log.e("wxhook:INCR", "userDir=${userDir.absolutePath}")
                android.util.Log.e("wxhook:INCR", "dbState=${dbState}")
                val lastRowId = dbState.optLong("lastMessageRowId", 0)
                android.util.Log.e("wxhook:INCR", "lastRowId=$lastRowId")
                if (lastRowId <= 0) {
                    callback?.onProgress("[$userHash] 无基线数据，请先全量备份", totalFiles, totalSize)
                    continue
                }

                callback?.onProgress("[$userHash] DB增量...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val incResult = decryptIncremental(dbSrc, lastRowId)
                incrFrom = lastRowId
                incrTo = lastRowId
                if (incResult.startsWith("OK:")) {
                    val gzPath = incResult.substring(3)
                    val gzFile = java.io.File(gzPath)
                    if (backupExists(gzPath) && backupSize(gzPath) > 0) {
                        // Extract last rowid from gz file (read only last line)
                        incrTo = runCatching {
                            val dec = if (useZstd()) "${binDir}/zstd -dc" else "gzip -dc"
                            suOut(dec + " \"" + gzFile.absolutePath + "\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1").trim().toLong()
                        }.getOrDefault(lastRowId)
                        val incrName = "incr_${incrFrom}_to_${incrTo}" + ext()
                        val incrPath = "${userDir.absolutePath}/$incrName"
                        val ok = suCopyResult(gzPath, incrPath)
                        if (ok && backupExists(incrPath) && backupSize(incrPath) > 0) {
                            totalFiles++; totalSize += backupSize(incrPath); newFiles++
                            updateDbState(userDir, tag, incrTo.toString())
                            callback?.onProgress("[$userHash] DB增量: ${incrTo - incrFrom}条新消息", totalFiles, totalSize)
                        } else {
                            callback?.onProgress("[$userHash] DB增量文件无效/重命名失败", totalFiles, totalSize)
                        }
                    } else {
                        callback?.onProgress("[$userHash] DB增量输出为空", totalFiles, totalSize)
                    }
                }
            }

            // 2. Attachments incremental (only newer files)
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    val dst = "${userDir.absolutePath}/$attDir"
                    try {
                        val findOut = suOut("find $src -type f -newermt @$lastTime 2>/dev/null")
                        val list = findOut.lines().filter { it.isNotBlank() }
                        if (list.isNotEmpty()) {
                            callback?.onProgress("[$userHash] 增量 $attDir: ${list.size}个", totalFiles, totalSize)
                            for (fp in list) {
                                val rel = fp.removePrefix("$src/")
                                val dstFile = File(dst, rel)
                                dstFile.parentFile?.mkdirs()
                                su("cp \"$fp\" \"${dstFile.absolutePath}\" && chmod 644 \"${dstFile.absolutePath}\"")
                                if (dstFile.exists()) { totalFiles++; totalSize += dstFile.length(); newFiles++ }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("wxhook:Backup", "Incr $userHash/$attDir failed: $e")
                    }
                }
            }

            // 3. Git commit
            val gitHash = gitAddAndCommit(tag)
            rcloneSync(callback)

            saveState(tag, totalFiles, totalSize)
            if (gitHash.isNotEmpty()) {
                try {
                    val stateFile = File(BACKUP_DIR, STATE_FILE)
                    val st = JSONObject(backupRead(stateFile.absolutePath))
                    st.put("gitCommit", gitHash)
                    backupWrite(stateFile.absolutePath, st.toString())
                    for (d in File(BACKUP_DIR).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("tmp") } ?: emptyList()) {
                        val dbStateFile = File(d, DB_STATE_FILE)
                        if (backupExists(dbStateFile.absolutePath)) {
                            val dbst = JSONObject(backupRead(dbStateFile.absolutePath))
                            dbst.put("gitCommit", gitHash)
                            backupWrite(dbStateFile.absolutePath, dbst.toString())
                        }
                    }
                } catch (_: Exception) {}
            }
            val incrFiles = mutableListOf<String>()
            val incList = dir.listFiles()?.filter { it.name.startsWith("incr_") && it.name.endsWith(ext()) }?.sortedBy { it.name } ?: emptyList()
            for (f in incList) {
                incrFiles.add(f.name)
            }
            val rec = createRecord(tag, "incremental", totalFiles, totalSize, 
                if (newFiles > 0) "增量: ${newFiles}个文件, ${formatSize(totalSize)}" else "无新文件", durationMs = System.currentTimeMillis() - startTime)
            if (incrFiles.isNotEmpty()) rec.put("files", JSONArray(incrFiles))
            rec.put("newFiles", newFiles)
            addRecord(rec)
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个文件(${formatSize(totalSize)}), DB:${incrFrom}→${incrTo}" else "无新文件"
            val gitMsg = if (gitHash.isNotEmpty()) " git:$gitHash" else " (git无commit)"
            Result(true, msg + gitMsg)
        } catch (e: Exception) { Result(false, "增量备份失败: ${e.message}") }
    }

    // ── Git operations ──

    private fun gitAddAndCommit(tag: String): String {
        val g = binDir + "/git"
        val ld = "LD_LIBRARY_PATH=" + binDir
        val env = "HOME=/data/local/tmp " + ld + " " + g + " -C \"" + BACKUP_DIR + "\""
        val init = RootCommandRunner.runSu("$env init", 30_000)
        if (!init.ok) return ""
        val identity = RootCommandRunner.runSu(
            "$env config user.name wxhook && $env config user.email wxhook@localhost",
            30_000
        )
        if (!identity.ok) return ""
        val add = RootCommandRunner.runSu("$env add -A", 120_000)
        if (!add.ok) return ""
        val commit = RootCommandRunner.runSu("$env commit -m \"$tag\" --allow-empty", 120_000)
        if (!commit.ok) return ""
        val head = RootCommandRunner.runSu("$env rev-parse --verify HEAD", 30_000)
        return if (head.ok) head.stdout.trim().take(12) else ""
    }

    private fun rcloneSync(callback: ProgressCallback?) {
        try {
            val configFile = File(BACKUP_DIR, "remote_config.json")
            if (!configFile.exists()) return
            val config = JSONObject(suOut("cat \"${configFile.absolutePath}\" 2>/dev/null").ifBlank { "{}" })
            val enabled = config.optBoolean("enabled", false)
            if (!enabled) return
            val remote = config.optString("remote", "")
            if (remote.isBlank()) return
            callback?.onProgress("同步到 $remote...", 0, 0)
            // Package DB backup files into a tar.gz
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pkgName = "wxhook_backup_$tag.tar.gz"
            val pkgPath = "/data/local/tmp/$pkgName"
            // Find backup state files and latest incr/baseline
            val findCmd = "find \"$BACKUP_DIR\" -maxdepth 1 -type f -name \"backup_*.json\" 2>/dev/null; " +
                "find \"$BACKUP_DIR\" -maxdepth 2 -type f \\( -name \"*.sql.gz\" -o -name \"*.sql.zst\" -o -name \"db_state.json\" \\) 2>/dev/null"
            val files = suOut(findCmd).lines().filter { it.isNotBlank() }
            if (files.isEmpty()) { callback?.onProgress("无文件可同步", 0, 0); return }
            // Create tar.gz
            val tarCmd = files.joinToString(" ") { "\"$it\"" }
            su("tar czf \"$pkgPath\" $tarCmd 2>/dev/null", 120_000)
            val pkgSize = suOut("stat -c %s \"$pkgPath\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (pkgSize < 100L) { callback?.onProgress("打包失败", 0, 0); return }
            callback?.onProgress("上传 $pkgName (${formatSize(pkgSize)})...", 0, pkgSize)
            // Upload with env fix and timeout
            val env = "HOME=/data/local/tmp LD_LIBRARY_PATH=$binDir SSL_CERT_DIR=/system/etc/security/cacerts"
            val rclone = "$binDir/rclone"
            val args = mutableListOf(rclone, "copy", pkgPath, remote, "--no-check-certificate", "--timeout=30s")
            if (rcloneConfigPath.isNotEmpty() && java.io.File(rcloneConfigPath).exists()) {
                args.add("--config"); args.add(rcloneConfigPath)
            }
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "$env ${args.joinToString(" ")}"))
            val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            proc.inputStream.bufferedReader().readText() // drain
            if (!finished) { proc.destroyForcibly(); callback?.onProgress("同步超时", 0, 0); return }
            if (proc.exitValue() != 0) { callback?.onProgress("同步失败(exit=${proc.exitValue()})", 0, 0); return }
            // Cleanup local pkg
            su("rm -f \"$pkgPath\"", 10_000)
            callback?.onProgress("同步完成: $pkgName", 1, pkgSize)
        } catch (e: Exception) {
            callback?.onProgress("同步异常: ${e.message}", 0, 0)
        }
    }

    fun testRemoteConnection(remote: String, configPath: String = ""): String {
        val env = "HOME=/data/local/tmp LD_LIBRARY_PATH=$binDir SSL_CERT_DIR=/system/etc/security/cacerts"
        val cfgPath = if (configPath.isNotEmpty()) configPath else rcloneConfigPath
        val cfgFlag = if (cfgPath.isNotEmpty() && java.io.File(cfgPath).exists())
            " --config \"$cfgPath\"" else ""
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "$env $binDir/rclone lsd \"$remote:\"$cfgFlag --no-check-certificate --timeout=15s 2>&1"))
        } catch (e: Exception) { return "启动失败: ${e.message}" }
        val out = try {
            proc.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }
        val finished = try { proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { false }
        if (!finished) { proc.destroyForcibly(); return "连接超时(15s)" }
        if (proc.exitValue() != 0) {
            val err = if (out.contains("certificate")) "证书错误，请检查服务端TLS配置"
                else if (out.contains("no such host") || out.contains("lookup")) "DNS解析失败，无法连接到服务器"
                else if (out.contains("401") || out.contains("403")) "认证失败，请检查账号密码"
                else if (out.contains("timeout") || out.contains("refused")) "服务器无响应或被拒绝"
                else "连接失败: ${out.lines().firstOrNull()?.take(120) ?: "未知错误"}"
            return err
        }
        if (out.isBlank()) return "✅ 连接成功（远端无目录）"
        val dirs = out.lines().filter { it.isNotBlank() }.take(10)
        return "✅ 连接成功\n${dirs.joinToString("\n")}"
    }

    private fun userDir(hash: String) = hash

    // ── DB incremental backup ──

    private var cachedPassword: String? = null

    private fun suCopyResult(src: String, dest: String, mode: String = "664"): Boolean {
        val result = su("cp \"$src\" \"$dest\" && chmod $mode \"$dest\"", 120_000)
        return result.ok
    }

    private fun backupExists(path: String): Boolean = suOut("test -e \"$path\" && echo 1").trim() == "1"
    private fun backupSize(path: String): Long = suOut("stat -c %s \"$path\" 2>/dev/null").trim().toLongOrNull() ?: 0L
    private fun backupRead(path: String): String = suOut("cat \"$path\" 2>/dev/null")
    private fun backupWrite(path: String, content: String) {
        val tmp = File(filesDirForWrite(), "sdcard_write_${System.nanoTime()}.tmp")
        tmp.writeText(content)
        suCopy(tmp, File(path))
        tmp.delete()
    }

    fun setCompressionUseZstd(enabled: Boolean) {
        val cfg = try { JSONObject(backupRead(File(BACKUP_DIR, DB_CONFIG_FILE).absolutePath)) } catch (_: Exception) { JSONObject() }
        cfg.put("zstd", enabled)
        backupWrite(File(BACKUP_DIR, DB_CONFIG_FILE).absolutePath, cfg.toString())
    }

    private fun useZstd(): Boolean {
        return try {
            val cfg = JSONObject(backupRead(File(BACKUP_DIR, DB_CONFIG_FILE).absolutePath))
            cfg.optBoolean("zstd", false)
        } catch (_: Exception) { false }
    }

    private fun ext() = if (useZstd()) ".sql.zst" else ".sql.gz"

    private fun getDbPassword(): String {
        if (cachedPassword != null) return cachedPassword!!
        cachedPassword = try {
            val raw = suOut("cat /data/local/tmp/.wechat_key 2>/dev/null")
            // Parse key=hex format: key=65396364326165 → e9cd2ae
            val keyLine = raw.lines().firstOrNull { it.startsWith("key=") }
            if (keyLine != null) {
                val hex = keyLine.removePrefix("key=").trim()
                // Convert hex to ASCII bytes
                var pwd = ""
                for (i in hex.indices step 2) {
                    if (i + 1 < hex.length) {
                        val byte = hex.substring(i, i + 2).toIntOrNull(16) ?: continue
                        if (byte > 0) pwd += byte.toChar()
                    }
                }
                if (pwd.isNotEmpty()) pwd
                else hex // fallback: use raw hex
            } else {
                // Try db_config.json
                val cfg = java.io.File(BACKUP_DIR, "db_config.json")
                if (cfg.exists()) JSONObject(cfg.readText()).optString("password", "") else ""
            }
        } catch (_: Exception) { "" }
        return cachedPassword ?: ""
    }

    private fun decryptAndDump(dbPath: String): String {
        val tmpDir = "/sdcard/Download/wxhook_backup/tmp"
        val shPath = "/data/local/tmp/decrypt_full.sh"
        val gzFile = "$tmpDir/EnMicroMsg_baseline" + ext()
        return try {
            val pwd = getDbPassword()
            val script = ("#!/system/bin/sh\n" +
                "mkdir -p $tmpDir\n" +
                "cp \"" + dbPath + "\" $tmpDir/wxhook_dec.db 2>/dev/null\n" +
                "LD_PRELOAD='${binDir}/libz.so.1:${binDir}/libcrypto.so.3:${binDir}/libedit.so:${binDir}/libncursesw.so.6' " +
                "${binDir}/sqlcipher $tmpDir/wxhook_dec.db -batch " +
                "-cmd '.output /dev/null' " +
                "-cmd 'PRAGMA key = \"" + pwd + "\";' " +
                "-cmd 'PRAGMA cipher_compatibility = 3;' " +
                "-cmd 'PRAGMA cipher_page_size = 1024;' " +
                "-cmd 'PRAGMA kdf_iter = 4000;' " +
                "-cmd 'PRAGMA cipher_use_hmac = OFF;' " +
                "-cmd '.output stdout' " +
                "-cmd '.mode insert' " +
                "-cmd 'SELECT * FROM message;' " +
                "2>/dev/null | " + (if (useZstd()) "${binDir}/zstd -c -3" else "gzip -c") + " > $gzFile 2>/dev/null\n")
            val b64 = android.util.Base64.encodeToString(script.toByteArray(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
            su("printf '%s' " + b64 + " | base64 -d > $shPath && chmod 755 $shPath")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "sh $shPath > /data/local/tmp/decrypt_exec.log 2>&1"))
            proc.outputStream.close()
            proc.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)
            if (java.io.File(gzFile).exists() && java.io.File(gzFile).length() > 0) return "OK:$gzFile"
            ""
        } catch (e: Exception) { android.util.Log.e("wxhook:Backup", "decryptAndDump: $e"); "" }
    }
    private fun decryptIncremental(dbPath: String, lastRowId: Long): String {
        val finalDir = "/sdcard/Download/wxhook_backup/tmp"
        val workDir = "/data/local/tmp/wxhook_backup"
        val workDb = "$workDir/wxhook_inc.db"
        val workSql = "$workDir/wxhook_inc_out.sql"
        val workOut = "$workDir/wxhook_inc_out.sql.gz"
        val finalOut = "$finalDir/wxhook_inc_out.sql.gz"
        return try {
            val pwd = getDbPassword()
            if (pwd.isEmpty()) return ""
            val cleanupWork = "rm -f $workDb $workDb-shm $workDb-wal $workSql $workOut 2>/dev/null"
            val cleanupAll = "$cleanupWork; rm -f $finalOut 2>/dev/null"
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall sqlcipher 2>/dev/null; mkdir -p $workDir; $cleanupAll")).waitFor()

            // /proc DB must be copied sequentially to ext4 before SQLCipher opens it.
            val copy = Runtime.getRuntime().exec(arrayOf("su", "-c", "dd if=\"$dbPath\" of=\"$workDb\" bs=4M status=none"))
            val copyFinished = copy.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
            if (!copyFinished || copy.exitValue() != 0) return ""
            val copiedSize = RootCommandRunner.runSuQuiet("stat -c %s \"$workDb\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (copiedSize < 1_000_000L) return ""

            val sqlCmd = "LD_PRELOAD='${binDir}/libz.so.1:${binDir}/libcrypto.so.3:${binDir}/libedit.so:${binDir}/libncursesw.so.6' " +
                "${binDir}/sqlcipher \"$workDb\" -batch " +
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
            val query = Runtime.getRuntime().exec(arrayOf("su", "-c", sqlCmd))
            query.outputStream.close()
            val queryFinished = query.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
            if (!queryFinished) {
                query.destroyForcibly()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "killall sqlcipher 2>/dev/null")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", cleanupWork)).waitFor()
                return ""
            }
            val sqlSize = RootCommandRunner.runSuQuiet("stat -c %s \"$workSql\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (query.exitValue() != 0 || sqlSize <= 0L) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cleanupWork)).waitFor()
                return ""
            }
            val compressor = if (useZstd()) "${binDir}/zstd -c -3" else "gzip -c"
            val gzip = Runtime.getRuntime().exec(arrayOf("su", "-c", "$compressor \"$workSql\" > \"$workOut\""))
            val gzipFinished = gzip.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!gzipFinished) gzip.destroyForcibly()
            val outputSize = RootCommandRunner.runSuQuiet("stat -c %s \"$workOut\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (!gzipFinished || gzip.exitValue() != 0 || outputSize <= 0L) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cleanupWork)).waitFor()
                return ""
            }
            val moved = RootCommandRunner.runSu("cp \"$workOut\" \"$finalOut\" && chmod 664 \"$finalOut\"", 60_000).ok
            Runtime.getRuntime().exec(arrayOf("su", "-c", cleanupWork)).waitFor()
            if (moved) "OK:$finalOut" else ""
        } catch (e: Exception) {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall sqlcipher 2>/dev/null; rm -f $workDb $workDb-shm $workDb-wal $workOut $finalOut 2>/dev/null")).waitFor()
            ""
        }
    }


    private fun compressGzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun saveDbState(userDir: File, tag: String, maxRowId: Long = 0) {
        val state = JSONObject().apply { put("lastBackupTag", tag); put("lastBackupTime", System.currentTimeMillis()); if (maxRowId > 0) put("lastMessageRowId", maxRowId) }
        val tmp = File(filesDirForWrite(), "db_state_${userDir.name}.json")
        tmp.writeText(state.toString())
        suCopy(tmp, File(userDir, DB_STATE_FILE))
    }

    private fun loadDbState(userDir: File): JSONObject {
        val f = File(userDir, DB_STATE_FILE)
        return try {
            val txt = suOut("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
        } catch (e: Exception) {
            android.util.Log.e("wxhook:INCR", "loadDbState failed: $e")
            JSONObject()
        }
    }

    private fun updateDbState(userDir: File, tag: String, newRowId: String) {
        val state = loadDbState(userDir)
        state.put("lastBackupTag", tag)
        state.put("lastBackupTime", System.currentTimeMillis())
        state.put("incrCount", state.optInt("incrCount", 0) + 1)
        val rowId = newRowId.toLongOrNull()
        if (rowId != null && rowId > 0) state.put("lastMessageRowId", rowId)
        val tmp = File(filesDirForWrite(), "db_state_${userDir.name}.json")
        tmp.writeText(state.toString())
        suCopy(tmp, File(userDir, DB_STATE_FILE))
    }

    // ── Helpers ──

    private fun compressFileSu(srcPath: String, dstPath: String) {
        try {
            su("" + (if (useZstd()) "${binDir}/zstd -c -3" else "gzip -c") + " \"" + srcPath + "\" > \"" + dstPath + "\" && chmod 644 \"" + dstPath + "\"", 600_000)
        } catch (_: Exception) {}
    }

    private fun findWxPaths(): List<String> {
        val paths = mutableListOf<String>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
            val pid = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pid.isNotEmpty()) {
                val basePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg"
                val lsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls $basePath 2>/dev/null"))
                val dirs = lsProc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
                lsProc.waitFor()
                for (d in dirs) {
                    val check = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls $basePath/$d/EnMicroMsg.db 2>/dev/null"))
                    val out = check.inputStream.bufferedReader().readText().trim()
                    check.waitFor()
                    if (out.isNotEmpty()) paths.add("$basePath/$d")
                }
            }
        } catch (_: Exception) {}
        return paths
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / 1024 / 1024 / 1024)
            bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
            bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
            else -> "$bytes B"
        }
    }

    private fun createRecord(tag: String, type: String, fileCount: Long, totalSize: Long, message: String, compression: String = "", durationMs: Long = 0): JSONObject {
        val comp = if (compression.isNotEmpty()) compression else if (useZstd()) "zstd" else "gzip"
        return JSONObject().apply {
            put("tag", tag); put("type", type); put("time", System.currentTimeMillis())
            put("fileCount", fileCount); put("totalSize", totalSize); put("message", message)
            put("compression", comp)
            if (durationMs > 0) put("durationMs", durationMs)
        }
    }

    private fun addRecord(record: JSONObject) {
        val dir = File(BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, RECORDS_FILE)
        val arr = try {
            val txt = suOut("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONArray(txt) else JSONArray()
        } catch (_: Exception) { JSONArray() }
        arr.put(record); while (arr.length() > 50) arr.remove(0)
        val tmp = File(filesDirForWrite(), RECORDS_FILE)
        tmp.writeText(arr.toString())
        suCopy(tmp, f)
    }

    private fun saveState(tag: String, count: Long, size: Long) {
        val state = JSONObject().apply { put("lastBackupTime", System.currentTimeMillis()); put("lastBackupTag", tag); put("fileCount", count); put("totalSize", size) }
        val tmp = File(filesDirForWrite(), STATE_FILE)
        tmp.writeText(state.toString())
        suCopy(tmp, File(BACKUP_DIR, STATE_FILE))
    }

    private fun loadState(): JSONObject {
        val f = File(BACKUP_DIR, STATE_FILE)
        return try {
            val txt = suOut("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
        } catch (_: Exception) { JSONObject() }
    }

    private fun saveDbConfig() {
        val config = JSONObject().apply { put("password", getDbPassword()); put("savedAt", System.currentTimeMillis()) }
        val tmp = File(filesDirForWrite(), DB_CONFIG_FILE)
        tmp.writeText(config.toString())
        suCopy(tmp, File(BACKUP_DIR, DB_CONFIG_FILE))
    }

    fun rebuildDbState(): String {
        val g = binDir + "/git"
        val results = mutableListOf<String>()
        val rebuiltRecords = JSONArray()
        return try {
            val usersProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "find " + BACKUP_DIR + " -mindepth 1 -maxdepth 1 -type d | grep -v '/\\.' | grep -v '/tmp$' | grep -v '/.git$'"))
            val userDirs = usersProc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
            usersProc.waitFor()
            if (userDirs.isEmpty()) return "备份目录为空"
            for (userPath in userDirs) {
                val userDir = File(userPath)
                val previousState = runCatching { JSONObject(backupRead(File(userDir, DB_STATE_FILE).absolutePath)) }.getOrDefault(JSONObject())
                val state = JSONObject().apply { put("restoredAt", System.currentTimeMillis()) }
                val previousRowId = previousState.optLong("lastMessageRowId", 0L)
                val fileProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "find \"$userPath\" -maxdepth 1 -type f"))
                val files = fileProc.inputStream.bufferedReader().readLines().map { File(it) }
                fileProc.waitFor()
                val baseline = files.find { it.name.startsWith("EnMicroMsg_baseline") && (it.name.endsWith(".sql.gz") || it.name.endsWith(".sql.zst")) }
                if (baseline != null) {
                    state.put("baseline", baseline.name)
                    state.put("baselineSize", baseline.length())
                    state.put("lastBackupTime", baseline.lastModified())
                    rebuiltRecords.put(JSONObject().apply {
                        put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(baseline.lastModified())))
                        put("type", "full")
                        put("time", baseline.lastModified())
                        put("fileCount", 1)
                        put("totalSize", baseline.length())
                        put("compression", if (baseline.name.endsWith(".zst")) "zstd" else "gzip")
                        put("message", "全量备份: ${baseline.name}")
                        put("files", JSONArray().put(baseline.name))
                    })
                }
                val incrFiles = files.filter { it.name.startsWith("incr_") && (it.name.endsWith(".sql.gz") || it.name.endsWith(".sql.zst")) }.sortedBy { it.name }
                if (incrFiles.isNotEmpty()) {
                    state.put("incrCount", incrFiles.size)
                    state.put("incrFiles", JSONArray(incrFiles.map { it.name }))
                    val lastFile = incrFiles.last()
                    val dec = if (lastFile.name.endsWith(".zst")) binDir + "/zstd -dc" else "gzip -dc"
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", dec + " \"" + lastFile.absolutePath + "\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1"))
                        val rowId = p.inputStream.bufferedReader().readText().trim().toLongOrNull()
                        if (rowId != null && rowId > previousRowId) state.put("lastMessageRowId", rowId)
                        else if (previousRowId > 0) state.put("lastMessageRowId", previousRowId)
                    } catch (_: Exception) {}
                    for (f in incrFiles) {
                        val m = Regex("incr_(\\d+)_to_(\\d+)\\.sql\\.(gz|zst)").find(f.name)
                        val from = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val to = m?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
                        rebuiltRecords.put(JSONObject().apply {
                            put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(f.lastModified())))
                            put("type", "incremental")
                            put("time", f.lastModified())
                            put("fileCount", 1)
                            put("totalSize", f.length())
                            put("compression", if (f.name.endsWith(".zst")) "zstd" else "gzip")
                            put("newFiles", 1)
                            put("incrFrom", from)
                            put("incrTo", to)
                            put("message", "增量备份: DB:${from}→${to}")
                            put("files", JSONArray().put(f.name))
                        })
                    }
                } else if (baseline != null) {
                    val dec = if (baseline.name.endsWith(".zst")) binDir + "/zstd -dc" else "gzip -dc"
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", dec + " \"" + baseline.absolutePath + "\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1"))
                        val rowId = p.inputStream.bufferedReader().readText().trim().toLongOrNull()
                        if (rowId != null && rowId > 0) state.put("lastMessageRowId", rowId)
                    } catch (_: Exception) {}
                }
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "HOME=/data/local/tmp LD_LIBRARY_PATH=" + binDir + " " + g + " -C " + BACKUP_DIR + " rev-parse HEAD"))
                    val hash = p.inputStream.bufferedReader().readText().trim().take(12)
                    if (hash.isNotEmpty() && hash != "HEAD") state.put("gitCommit", hash)
                } catch (_: Exception) {}
                val tmpState = File(filesDirForWrite(), "db_state_${userDir.name}.json")
                tmpState.writeText(state.toString())
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp \"${tmpState.absolutePath}\" \"${File(userDir, DB_STATE_FILE).absolutePath}\" && chmod 664 \"${File(userDir, DB_STATE_FILE).absolutePath}\"")).waitFor()
                results.add("${userDir.name}: rowId=${state.optLong("lastMessageRowId", 0)} incr=${incrFiles.size} git=${state.optString("gitCommit", "-")}")
            }
            val sorted = (0 until rebuiltRecords.length()).map { rebuiltRecords.getJSONObject(it) }.sortedBy { it.optLong("time", 0L) }
            val outArr = JSONArray(); for (rec in sorted) outArr.put(rec)
            val tmpRecords = File(filesDirForWrite(), RECORDS_FILE)
            tmpRecords.writeText(outArr.toString())
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cp \"${tmpRecords.absolutePath}\" \"${File(BACKUP_DIR, RECORDS_FILE).absolutePath}\" && chmod 664 \"${File(BACKUP_DIR, RECORDS_FILE).absolutePath}\"")).waitFor()
            results.joinToString("\n") + "\nrecords=" + sorted.size
        } catch (e: Exception) {
            "重建失败: ${e.message}"
        }
    }

    data class Result(val success: Boolean, val message: String)

}
