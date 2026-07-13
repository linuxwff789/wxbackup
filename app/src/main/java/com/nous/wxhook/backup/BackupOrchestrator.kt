package com.nous.wxhook.backup

import android.util.Log
import com.nous.wxhook.rootbridge.RootCommandRunner
import com.nous.wxhook.storage.WxHookPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the full backup flow: stop → resolve → archive → verify → record.
 * All su commands go through RootCommandRunner.
 */
object BackupOrchestrator {

    private const val DB_STATE_FILE = WxHookPaths.DB_STATE_FILE
    private val ATT_DIRS = listOf(
        "image2", "voice2", "video", "emoji", "avatar", "cdn", "record", "favorite"
    )

    // ── Full Backup ──

    fun doFullBackup(callback: BackupHookLocal.ProgressCallback? = null): BackupHookLocal.Result {
        val startTime = System.currentTimeMillis()
        return try {
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(BackupEnv.backupDir); if (!dir.exists()) dir.mkdirs()
            var totalFiles = 0L; var totalSize = 0L

            // 1. Find WeChat users
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return BackupHookLocal.Result(false, "微信未运行或未找到数据")

            // 2. Backup DB (baseline)
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.mkdirs()

                callback?.onProgress("[$userHash] 数据库基线...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val dbGzFile = File(userDir, "EnMicroMsg_baseline" + BackupEnv.ext())
                // Decrypt + gzip
                val decResult = ArchiveService.decryptAndDump(dbSrc)
                if (decResult.startsWith("OK:")) {
                    val gzPath = decResult.substring(3)
                    val gzFile = File(gzPath)
                    if (gzFile.exists()) {
                        gzFile.renameTo(dbGzFile)
                        totalFiles++; totalSize += BackupEnv.backupSize(gzPath)
                    }
                }
                if (!BackupEnv.backupExists(dbGzFile.absolutePath)) {
                    ArchiveService.compressFileSu(dbSrc, dbGzFile.absolutePath)
                    if (BackupEnv.backupExists(dbGzFile.absolutePath)) {
                        totalFiles++; totalSize += BackupEnv.backupSize(dbGzFile.absolutePath)
                    }
                }

                // Save DB state
                val maxRowId = runCatching {
                    val result = RootCommandRunner.runSu(
                        "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6' " +
                        "/data/local/sqlcipher /sdcard/Download/wxhook_backup/tmp/wxhook_dec.db " +
                        "-cmd 'PRAGMA key = \"e9cd2ae\";' -cmd 'PRAGMA cipher_compatibility=3;' " +
                        "-cmd 'PRAGMA cipher_page_size=1024;' -cmd 'PRAGMA kdf_iter=4000;' " +
                        "-cmd 'PRAGMA cipher_use_hmac=OFF;' " +
                        "-cmd 'SELECT max(rowid) FROM message;' 2>/dev/null",
                        30_000
                    )
                    result.stdout.lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLong() ?: 0L
                }.getOrDefault(0L)
                BackupManifest.saveDbState(userDir, tag, maxRowId)
            }

            // 3. Backup attachments (git will handle dedup)
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.mkdirs()

                for (attDir in ATT_DIRS) {
                    callback?.onProgress("[$userHash] $attDir...", totalFiles, totalSize)
                    val src = "$wxBasePath/$attDir"
                    val dst = "${userDir.absolutePath}/$attDir"
                    try {
                        BackupEnv.su("mkdir -p $dst")
                        BackupEnv.su(
                            "cp -r $src $dst 2>/dev/null && chmod -R 644 $dst 2>/dev/null"
                        )
                        val d = File(dst)
                        if (d.exists()) {
                            totalFiles += d.walkTopDown().filter { it.isFile }.count()
                            totalSize += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        }
                    } catch (e: Exception) {
                        Log.e("wxhook:Backup", "Copy $userHash/$attDir failed: $e")
                    }
                }
            }

            // 4. Git commit
            val gitHash = gitAddAndCommit(tag)
            rcloneSync(callback)

            // 5. Save config and records
            BackupManifest.saveDbConfig()
            BackupManifest.saveState(tag, totalFiles, totalSize)
            BackupManifest.stampGitCommit(gitHash)
            BackupManifest.addRecord(
                BackupManifest.createRecord(
                    tag, "full", totalFiles, totalSize, "全量备份完成",
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
            val gitMsg = if (gitHash.isNotEmpty()) " git:$gitHash" else " (git无commit)"
            BackupHookLocal.Result(
                true,
                "全量备份完成: ${totalFiles}个文件, ${BackupManifest.formatSize(totalSize)}$gitMsg"
            )
        } catch (e: Exception) {
            BackupHookLocal.Result(false, "备份失败: ${e.message}")
        }
    }

    // ── Incremental Backup ──

    fun doIncrementalBackup(callback: BackupHookLocal.ProgressCallback? = null): BackupHookLocal.Result {
        val startTime = System.currentTimeMillis()
        Log.e("wxhook:CLICK", "BackupHookLocal.doIncrementalBackup enter")
        return try {
            val state = BackupManifest.loadState()
            val lastTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(BackupEnv.backupDir)
            var totalFiles = 0L; var totalSize = 0L; var newFiles = 0L

            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return BackupHookLocal.Result(false, "微信未运行或未找到数据")

            var incrFrom = 0L
            var incrTo = 0L

            // 1. DB incremental
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.mkdirs()

                val dbState = BackupManifest.loadDbState(userDir)
                Log.e("wxhook:INCR", "userDir=${userDir.absolutePath}")
                Log.e("wxhook:INCR", "dbState=$dbState")
                val lastRowId = dbState.optLong("lastMessageRowId", 0)
                Log.e("wxhook:INCR", "lastRowId=$lastRowId")
                if (lastRowId <= 0) {
                    callback?.onProgress(
                        "[$userHash] 无基线数据，请先全量备份", totalFiles, totalSize
                    )
                    continue
                }

                callback?.onProgress("[$userHash] DB增量...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val incResult = ArchiveService.decryptIncremental(dbSrc, lastRowId)
                incrFrom = lastRowId
                incrTo = lastRowId
                if (incResult.startsWith("OK:")) {
                    val gzPath = incResult.substring(3)
                    val gzFile = File(gzPath)
                    if (BackupEnv.backupExists(gzPath) && BackupEnv.backupSize(gzPath) > 0) {
                        // Extract last rowid from gz file (read only last line)
                        incrTo = runCatching {
                            val dec = if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -dc" else "gzip -dc"
                            BackupEnv.suOut(
                                "$dec \"$gzPath\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1"
                            ).trim().toLong()
                        }.getOrDefault(lastRowId)
                        val incrName = "incr_${incrFrom}_to_${incrTo}" + BackupEnv.ext()
                        val incrPath = "${userDir.absolutePath}/$incrName"
                        val ok = BackupEnv.suCopyResult(gzPath, incrPath)
                        if (ok && BackupEnv.backupExists(incrPath) && BackupEnv.backupSize(incrPath) > 0) {
                            totalFiles++; totalSize += BackupEnv.backupSize(incrPath); newFiles++
                            BackupManifest.updateDbState(userDir, tag, incrTo.toString())
                            callback?.onProgress(
                                "[$userHash] DB增量: ${incrTo - incrFrom}条新消息",
                                totalFiles, totalSize
                            )
                        } else {
                            callback?.onProgress(
                                "[$userHash] DB增量文件无效/重命名失败", totalFiles, totalSize
                            )
                        }
                    } else {
                        callback?.onProgress("[$userHash] DB增量输出为空", totalFiles, totalSize)
                    }
                }
            }

            // 2. Attachments incremental (only newer files)
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.mkdirs()

                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    val dst = "${userDir.absolutePath}/$attDir"
                    try {
                        val findOut = BackupEnv.suOut(
                            "find $src -type f -newermt @$lastTime 2>/dev/null"
                        )
                        val list = findOut.lines().filter { it.isNotBlank() }
                        if (list.isNotEmpty()) {
                            callback?.onProgress(
                                "[$userHash] 增量 $attDir: ${list.size}个",
                                totalFiles, totalSize
                            )
                            for (fp in list) {
                                val rel = fp.removePrefix("$src/")
                                val dstFile = File(dst, rel)
                                dstFile.parentFile?.mkdirs()
                                BackupEnv.su(
                                    "cp \"$fp\" \"${dstFile.absolutePath}\" && chmod 644 \"${dstFile.absolutePath}\""
                                )
                                if (dstFile.exists()) {
                                    totalFiles++; totalSize += dstFile.length(); newFiles++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("wxhook:Backup", "Incr $userHash/$attDir failed: $e")
                    }
                }
            }

            // 3. Git commit
            val gitHash = gitAddAndCommit(tag)
            rcloneSync(callback)

            BackupManifest.saveState(tag, totalFiles, totalSize)
            BackupManifest.stampGitCommit(gitHash)

            val incrFiles = mutableListOf<String>()
            val incList = dir.listFiles()
                ?.filter { it.name.startsWith("incr_") && it.name.endsWith(BackupEnv.ext()) }
                ?.sortedBy { it.name } ?: emptyList()
            for (f in incList) incrFiles.add(f.name)

            val rec = BackupManifest.createRecord(
                tag, "incremental", totalFiles, totalSize,
                if (newFiles > 0) "增量: ${newFiles}个文件, ${BackupManifest.formatSize(totalSize)}" else "无新文件",
                durationMs = System.currentTimeMillis() - startTime
            )
            if (incrFiles.isNotEmpty()) rec.put("files", JSONArray(incrFiles))
            rec.put("newFiles", newFiles)
            BackupManifest.addRecord(rec)
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个文件(${BackupManifest.formatSize(totalSize)}), DB:${incrFrom}→${incrTo}" else "无新文件"
            val gitMsg = if (gitHash.isNotEmpty()) " git:$gitHash" else " (git无commit)"
            BackupHookLocal.Result(true, msg + gitMsg)
        } catch (e: Exception) {
            BackupHookLocal.Result(false, "增量备份失败: ${e.message}")
        }
    }

    // ── Git operations ──

    fun gitAddAndCommit(tag: String): String {
        val g = BackupEnv.binDir + "/git"
        val ld = "LD_LIBRARY_PATH=${BackupEnv.binDir}"
        val env = "HOME=/data/local/tmp $ld $g -C \"${BackupEnv.backupDir}\""
        val init = RootCommandRunner.runSu("$env init", 30_000)
        if (!init.isSuccess) return ""
        val identity = RootCommandRunner.runSu(
            "$env config user.name wxhook && $env config user.email wxhook@localhost",
            30_000
        )
        if (!identity.isSuccess) return ""
        val add = RootCommandRunner.runSu("$env add -A", 120_000)
        if (!add.isSuccess) return ""
        val commit = RootCommandRunner.runSu(
            "$env commit -m \"$tag\" --allow-empty", 120_000
        )
        if (!commit.isSuccess) return ""
        val head = RootCommandRunner.runSu("$env rev-parse --verify HEAD", 30_000)
        return if (head.isSuccess) head.stdout.trim().take(12) else ""
    }

    // ── Remote sync ──

    fun rcloneSync(callback: BackupHookLocal.ProgressCallback?) {
        try {
            val configFile = File(BackupEnv.backupDir, "remote_config.json")
            if (!configFile.exists()) return
            val config = JSONObject(
                BackupEnv.suOut("cat \"${configFile.absolutePath}\" 2>/dev/null").ifBlank { "{}" }
            )
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
            val findCmd = "find \"${BackupEnv.backupDir}\" -maxdepth 1 -type f -name \"backup_*.json\" 2>/dev/null; " +
                "find \"${BackupEnv.backupDir}\" -maxdepth 2 -type f \\( -name \"*.sql.gz\" -o -name \"*.sql.zst\" -o -name \"db_state.json\" \\) 2>/dev/null"
            val files = BackupEnv.suOut(findCmd).lines().filter { it.isNotBlank() }
            if (files.isEmpty()) {
                callback?.onProgress("无文件可同步", 0, 0); return
            }
            // Create tar.gz
            val tarCmd = files.joinToString(" ") { "\"$it\"" }
            BackupEnv.su("tar czf \"$pkgPath\" $tarCmd 2>/dev/null", 120_000)
            val pkgSize = BackupEnv.suOut("stat -c %s \"$pkgPath\" 2>/dev/null")
                .trim().toLongOrNull() ?: 0L
            if (pkgSize < 100L) {
                callback?.onProgress("打包失败", 0, 0); return
            }
            callback?.onProgress("上传 $pkgName (${BackupManifest.formatSize(pkgSize)})...", 0, pkgSize)
            // Upload with env fix and timeout
            val env = "HOME=/data/local/tmp LD_LIBRARY_PATH=${BackupEnv.binDir} SSL_CERT_DIR=/system/etc/security/cacerts"
            val rclone = "${BackupEnv.binDir}/rclone"
            val args = mutableListOf(rclone, "copy", pkgPath, remote, "--no-check-certificate", "--timeout=30s")
            if (BackupEnv.rcloneConfigPath.isNotEmpty() && File(BackupEnv.rcloneConfigPath).exists()) {
                args.add("--config"); args.add(BackupEnv.rcloneConfigPath)
            }
            val cmdStr = "$env ${args.joinToString(" ")}"
            val uploadResult = RootCommandRunner.runSu(cmdStr, 120_000)
            if (!uploadResult.isSuccess) {
                val errMsg = if (uploadResult.stderr.contains("timeout") || uploadResult.stdout.contains("timeout"))
                    "同步超时" else "同步失败(exit=${uploadResult.exitCode})"
                callback?.onProgress(errMsg, 0, 0)
                return
            }
            // Cleanup local pkg
            BackupEnv.su("rm -f \"$pkgPath\"", 10_000)
            callback?.onProgress("同步完成: $pkgName", 1, pkgSize)
        } catch (e: Exception) {
            callback?.onProgress("同步异常: ${e.message}", 0, 0)
        }
    }

    // ── Test remote ──

    fun testRemoteConnection(remote: String, configPath: String = ""): String {
        val env = "HOME=/data/local/tmp LD_LIBRARY_PATH=${BackupEnv.binDir} SSL_CERT_DIR=/system/etc/security/cacerts"
        val cfgPath = if (configPath.isNotEmpty()) configPath else BackupEnv.rcloneConfigPath
        val cfgFlag = if (cfgPath.isNotEmpty() && File(cfgPath).exists())
            " --config \"$cfgPath\"" else ""
        val result = try {
            RootCommandRunner.runSu(
                "$env ${BackupEnv.binDir}/rclone lsd \"$remote:\"$cfgFlag --no-check-certificate --timeout=15s 2>&1",
                20_000
            )
        } catch (e: Exception) {
            return "启动失败: ${e.message}"
        }
        if (result.timedOut) return "连接超时(15s)"
        val out = result.stdout.trim()
        if (result.exitCode != 0) {
            val err = when {
                out.contains("certificate") -> "证书错误，请检查服务端TLS配置"
                out.contains("no such host") || out.contains("lookup") -> "DNS解析失败，无法连接到服务器"
                out.contains("401") || out.contains("403") -> "认证失败，请检查账号密码"
                out.contains("timeout") || out.contains("refused") -> "服务器无响应或被拒绝"
                else -> "连接失败: ${out.lines().firstOrNull()?.take(120) ?: "未知错误"}"
            }
            return err
        }
        if (out.isBlank()) return "✅ 连接成功（远端无目录）"
        val dirs = out.lines().filter { it.isNotBlank() }.take(10)
        return "✅ 连接成功\n${dirs.joinToString("\n")}"
    }

    // ── Rebuild DB State ──

    fun rebuildDbState(): String {
        val g = BackupEnv.binDir + "/git"
        val results = mutableListOf<String>()
        val rebuiltRecords = JSONArray()
        return try {
            val usersOutput = RootCommandRunner.runSuQuiet(
                "find ${BackupEnv.backupDir} -mindepth 1 -maxdepth 1 -type d | " +
                "grep -v '/\\\\.' | grep -v '/tmp$' | grep -v '/.git$'"
            )
            val userDirs = usersOutput.lines().filter { it.isNotBlank() }
            if (userDirs.isEmpty()) return "备份目录为空"
            for (userPath in userDirs) {
                val userDir = File(userPath)
                val previousState = runCatching {
                    JSONObject(BackupEnv.backupRead(File(userDir, DB_STATE_FILE).absolutePath))
                }.getOrDefault(JSONObject())
                val state = JSONObject().apply { put("restoredAt", System.currentTimeMillis()) }
                val previousRowId = previousState.optLong("lastMessageRowId", 0L)

                // List files in user dir
                val filesOutput = RootCommandRunner.runSuQuiet(
                    "find \"$userPath\" -maxdepth 1 -type f"
                )
                val files = filesOutput.lines().filter { it.isNotBlank() }.map { File(it) }

                val baseline = files.find {
                    it.name.startsWith("EnMicroMsg_baseline") &&
                    (it.name.endsWith(".sql.gz") || it.name.endsWith(".sql.zst"))
                }
                if (baseline != null) {
                    state.put("baseline", baseline.name)
                    state.put("baselineSize", baseline.length())
                    state.put("lastBackupTime", baseline.lastModified())
                    rebuiltRecords.put(JSONObject().apply {
                        put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(Date(baseline.lastModified())))
                        put("type", "full")
                        put("time", baseline.lastModified())
                        put("fileCount", 1)
                        put("totalSize", baseline.length())
                        put("compression", if (baseline.name.endsWith(".zst")) "zstd" else "gzip")
                        put("message", "全量备份: ${baseline.name}")
                        put("files", JSONArray().put(baseline.name))
                    })
                }

                val incrFiles = files.filter {
                    it.name.startsWith("incr_") &&
                    (it.name.endsWith(".sql.gz") || it.name.endsWith(".sql.zst"))
                }.sortedBy { it.name }

                if (incrFiles.isNotEmpty()) {
                    state.put("incrCount", incrFiles.size)
                    state.put("incrFiles", JSONArray(incrFiles.map { it.name }))
                    val lastFile = incrFiles.last()
                    val dec = if (lastFile.name.endsWith(".zst"))
                        "${BackupEnv.binDir}/zstd -dc" else "gzip -dc"
                    try {
                        val pResult = RootCommandRunner.runSu(
                            "$dec \"${lastFile.absolutePath}\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1",
                            30_000
                        )
                        val rowId = pResult.stdout.trim().toLongOrNull()
                        if (rowId != null && rowId > previousRowId) state.put("lastMessageRowId", rowId)
                        else if (previousRowId > 0) state.put("lastMessageRowId", previousRowId)
                    } catch (_: Exception) {}
                    for (f in incrFiles) {
                        val m = Regex("incr_(\\d+)_to_(\\d+)\\.sql\\.(gz|zst)").find(f.name)
                        val from = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val to = m?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
                        rebuiltRecords.put(JSONObject().apply {
                            put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(Date(f.lastModified())))
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
                    val dec = if (baseline.name.endsWith(".zst"))
                        "${BackupEnv.binDir}/zstd -dc" else "gzip -dc"
                    try {
                        val pResult = RootCommandRunner.runSu(
                            "$dec \"${baseline.absolutePath}\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1",
                            30_000
                        )
                        val rowId = pResult.stdout.trim().toLongOrNull()
                        if (rowId != null && rowId > 0) state.put("lastMessageRowId", rowId)
                    } catch (_: Exception) {}
                }

                // Git commit hash
                try {
                    val pResult = RootCommandRunner.runSu(
                        "HOME=/data/local/tmp LD_LIBRARY_PATH=${BackupEnv.binDir} $g -C ${BackupEnv.backupDir} rev-parse HEAD",
                        30_000
                    )
                    val hash = pResult.stdout.trim().take(12)
                    if (hash.isNotEmpty() && hash != "HEAD") state.put("gitCommit", hash)
                } catch (_: Exception) {}

                // Write state to user dir
                val tmpState = File(BackupEnv.filesDirForWrite(), "db_state_${userDir.name}.json")
                tmpState.writeText(state.toString())
                RootCommandRunner.runSu(
                    "cp \"${tmpState.absolutePath}\" \"${File(userDir, DB_STATE_FILE).absolutePath}\" && chmod 664 \"${File(userDir, DB_STATE_FILE).absolutePath}\"",
                    10_000
                )
                results.add(
                    "${userDir.name}: rowId=${state.optLong("lastMessageRowId", 0)} " +
                    "incr=${incrFiles.size} git=${state.optString("gitCommit", "-")}"
                )
            }
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            val outArr = JSONArray()
            for (rec in sorted) outArr.put(rec)
            val tmpRecords = File(BackupEnv.filesDirForWrite(), WxHookPaths.RECORDS_FILE)
            tmpRecords.writeText(outArr.toString())
            RootCommandRunner.runSu(
                "cp \"${tmpRecords.absolutePath}\" \"${File(BackupEnv.backupDir, WxHookPaths.RECORDS_FILE).absolutePath}\" && chmod 664 \"${File(BackupEnv.backupDir, WxHookPaths.RECORDS_FILE).absolutePath}\"",
                10_000
            )
            results.joinToString("\n") + "\nrecords=" + sorted.size
        } catch (e: Exception) {
            "重建失败: ${e.message}"
        }
    }
}
