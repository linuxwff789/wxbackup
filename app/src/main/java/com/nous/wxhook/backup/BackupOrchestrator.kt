package com.nous.wxhook.backup
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.core.command.CommandResult

import android.util.Log
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.storage.WxHookPaths
import com.nous.wxhook.sync.WebDavClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the full backup flow: stop → resolve → archive → verify → record.
 * All su commands go through RootGateways.
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
                        // 用 suCopy 复制（避免 FUSE 问题）
                        val copied = BackupEnv.suCopy(gzFile, dbGzFile)
                        if (copied && BackupEnv.backupExists(dbGzFile.absolutePath) && BackupEnv.backupSize(dbGzFile.absolutePath) > 0) {
                            totalFiles++; totalSize += BackupEnv.backupSize(dbGzFile.absolutePath)
                        }
                    }
                }
                // 如果用户目录没有有效文件，重新压缩
                if (!BackupEnv.backupExists(dbGzFile.absolutePath) || BackupEnv.backupSize(dbGzFile.absolutePath) <= 0) {
                    // 删除可能存在的无效文件
                    BackupEnv.su("rm -f \\\"${dbGzFile.absolutePath}\\\"")
                    val compressed = ArchiveService.compressFileSu(dbSrc, dbGzFile.absolutePath)
                    if (compressed && BackupEnv.backupExists(dbGzFile.absolutePath)) {
                        totalFiles++; totalSize += BackupEnv.backupSize(dbGzFile.absolutePath)
                    }
                }

                // Save DB state
                val maxRowId = runCatching {
                    val result = RootGateways.run(
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

            // 3. Backup attachments (tar directly from source)
            val tarFiles = mutableListOf<String>()
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)

                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    // 检查源目录是否存在
                    val exists = BackupEnv.suOut("test -d \\\"$src\\\" && echo 1").trim() == "1"
                    if (!exists) continue

                    callback?.onProgress("[$userHash] $attDir...", totalFiles, totalSize)
                    tarFiles.add("$wxBasePath/$attDir")

                    // 统计文件数和大小
                    val countResult = BackupEnv.suOut(
                        "find \\\"$src\\\" -type f 2>/dev/null | wc -l"
                    ).trim().toLongOrNull() ?: 0L
                    val sizeResult = BackupEnv.suOut(
                        "du -sb \\\"$src\\\" 2>/dev/null | cut -f1"
                    ).trim().toLongOrNull() ?: 0L
                    totalFiles += countResult
                    totalSize += sizeResult
                }

                // Save manifest
                val userDir = File(dir, userHash)
                userDir.mkdirs()
                BackupManifest.saveDbState(userDir, tag, 0)
            }

            // 4. Scan files and save manifest
            android.util.Log.d("wxhook:Backup", "scanFiles: dir=${dir.absolutePath}, exists=${BackupEnv.backupExists(dir.absolutePath)}")
            val allFiles = FileManifest.scanFiles(dir)
            android.util.Log.d("wxhook:Backup", "scanFiles: found ${allFiles.size} files")
            allFiles.forEach { android.util.Log.d("wxhook:Backup", "  file: ${it.path} (${it.size} bytes)") }
            val manifest = FileManifest.toManifest(allFiles, tag)
            FileManifest.save(dir, manifest)
            callback?.onProgress("清单已保存: ${allFiles.size}个文件", totalFiles, totalSize)

            // 5. Save config and records
            BackupManifest.saveDbConfig()
            BackupManifest.saveState(tag, totalFiles, totalSize)
            BackupManifest.addRecord(
                BackupManifest.createRecord(
                    tag, "full", totalFiles, totalSize, "全量备份完成",
                    durationMs = System.currentTimeMillis() - startTime
                )
            )

            // 6. Cloud sync
            cloudSync(callback, tarFiles = tarFiles)
            
            BackupHookLocal.Result(
                true,
                "全量备份完成: ${totalFiles}个文件, ${BackupManifest.formatSize(totalSize)}"
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
                                val cpResult = BackupEnv.su(
                                    "cp \\\"$fp\\\" \\\"${dstFile.absolutePath}\\\" && chmod 644 \\\"${dstFile.absolutePath}\\\""
                                )
                                if (cpResult.isSuccess && dstFile.exists()) {
                                    totalFiles++; totalSize += dstFile.length(); newFiles++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("wxhook:Backup", "Incr $userHash/$attDir failed: $e")
                    }
                }
            }

            // 3. Save state and update manifest
            BackupManifest.saveState(tag, totalFiles, totalSize)

            val incrFiles = mutableListOf<String>()
            val incList = dir.listFiles()
                ?.filter { it.name.startsWith("incr_") && it.name.endsWith(BackupEnv.ext()) }
                ?.sortedBy { it.name } ?: emptyList()
            for (f in incList) incrFiles.add(f.name)

            // Update manifest with new files
            val oldManifest = FileManifest.load(dir)
            val newFilesList = FileManifest.scanFiles(dir)
            val diff = FileManifest.diff(oldManifest, newFilesList)
            if (diff.added.isNotEmpty() || diff.modified.isNotEmpty()) {
                val updatedManifest = FileManifest.toManifest(newFilesList, tag)
                FileManifest.save(dir, updatedManifest)
                callback?.onProgress("清单已更新: +${diff.added.size} ~${diff.modified.size}", totalFiles, totalSize)
            }

            // 4. Cloud sync
            cloudSync(callback)

            val rec = BackupManifest.createRecord(
                tag, "incremental", totalFiles, totalSize,
                if (newFiles > 0) "增量: ${newFiles}个文件, ${BackupManifest.formatSize(totalSize)}" else "无新文件",
                durationMs = System.currentTimeMillis() - startTime
            )
            if (incrFiles.isNotEmpty()) rec.put("files", JSONArray(incrFiles))
            rec.put("newFiles", newFiles)
            BackupManifest.addRecord(rec)
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个文件(${BackupManifest.formatSize(totalSize)}), DB:${incrFrom}→${incrTo}" else "无新文件"
            
            BackupHookLocal.Result(true, msg)
        } catch (e: Exception) {
            BackupHookLocal.Result(false, "增量备份失败: ${e.message}")
        }
    }



    // ── Remote sync via WebDAV (incremental) ──

    fun cloudSync(callback: BackupHookLocal.ProgressCallback?, archivePath: String? = null, tarFiles: List<String> = emptyList()) {
        try {
            val configFile = File(BackupEnv.backupDir, "remote_config.json")
            if (!configFile.exists()) return
            val config = JSONObject(
                BackupEnv.suOut("cat \\\"${configFile.absolutePath}\\\" 2>/dev/null").ifBlank { "{}" }
            )
            val enabled = config.optBoolean("enabled", false)
            if (!enabled) return

            // Read WebDAV settings from settings_config.json
            val settingsCfg = try {
                val settingsFile = File(BackupEnv.filesDirPath, "settings_config.json")
                JSONObject(settingsFile.readText())
            } catch (_: Exception) { JSONObject() }
            val webdavUrl = settingsCfg.optString("webdav_url", "")
            val webdavUser = settingsCfg.optString("webdav_user", "")
            val webdavPass = settingsCfg.optString("webdav_pass", "")
            val remoteBase = config.optString("remote", "wxhook-backup")

            if (webdavUrl.isBlank() || webdavUser.isBlank()) return

            // Use provided archivePath or create new package
            val pkgPath = if (archivePath != null && File(archivePath).exists()) {
                archivePath
            } else if (tarFiles.isNotEmpty()) {
                // Tar directly from source directories
                val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val pkgName = "wxhook_backup_$tag.tar.gz"
                val tmpPkg = "/data/local/tmp/$pkgName"
                val tarCmd = tarFiles.joinToString(" ") { "\\\"$it\\\"" }
                val tarResult = BackupEnv.su("tar czf \\\"$tmpPkg\\\" $tarCmd", 120_000)
                if (!tarResult.isSuccess) {
                    callback?.onProgress("打包失败", 0, 0); return
                }
                tmpPkg
            } else {
                // Package backup files into tar.gz
                val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val pkgName = "wxhook_backup_$tag.tar.gz"
                val tmpPkg = "/data/local/tmp/$pkgName"
                val findCmd = "find \\\"${BackupEnv.backupDir}\\\" -maxdepth 1 -type f -name \\\"backup_*.json\\\" 2>/dev/null; " +
                    "find \\\"${BackupEnv.backupDir}\\\" -maxdepth 2 -type f \\\\( -name \\\"*.sql.gz\\\" -o -name \\\"*.sql.zst\\\" -o -name \\\"db_state.json\\\" \\\\) 2>/dev/null"
                val files = BackupEnv.suOut(findCmd).lines().filter { it.isNotBlank() }
                if (files.isEmpty()) {
                    callback?.onProgress("无文件可同步", 0, 0); return
                }
                val tarCmd = files.joinToString(" ") { "\\\"$it\\\"" }
                val tarResult = BackupEnv.su("tar czf \\\"$tmpPkg\\\" $tarCmd 2>/dev/null", 120_000)
                if (!tarResult.isSuccess) {
                    callback?.onProgress("打包失败", 0, 0); return
                }
                tmpPkg
            }

            val pkgSize = BackupEnv.suOut("stat -c %s \\\"$pkgPath\\\" 2>/dev/null")
                .trim().toLongOrNull() ?: 0L
            if (pkgSize < 100L) {
                callback?.onProgress("打包失败", 0, 0); return
            }
            val pkgName = File(pkgPath).name
            callback?.onProgress("上传 $pkgName (${BackupManifest.formatSize(pkgSize)})...", 0, pkgSize)

            // Incremental upload via WebDavClient
            val client = WebDavClient(webdavUrl, webdavUser, webdavPass)
            val testResult = kotlinx.coroutines.runBlocking { client.testConnection() }
            if (testResult.isFailure) {
                callback?.onProgress("WebDAV连接失败: ${testResult.exceptionOrNull()?.message}", 0, 0)
                return
            }

            kotlinx.coroutines.runBlocking { client.ensureDirectory(remoteBase) }

            // List remote files for incremental check
            val remoteFiles = kotlinx.coroutines.runBlocking { client.list(remoteBase) }.getOrNull() ?: emptyList()
            val localFile = File(pkgPath)
            val remoteMatch = remoteFiles.find { it.path.endsWith(localFile.name) }

            // Upload if new or changed
            if (remoteMatch == null || remoteMatch.size != localFile.length()) {
                val uploadResult = kotlinx.coroutines.runBlocking { client.upload(localFile, "$remoteBase/${localFile.name}") }
                if (uploadResult.isFailure) {
                    callback?.onProgress("上传失败: ${uploadResult.exceptionOrNull()?.message}", 0, 0)
                    return
                }
                callback?.onProgress("已上传: ${localFile.name}", 1, pkgSize)
            } else {
                callback?.onProgress("跳过: ${localFile.name} (远程已存在)", 1, pkgSize)
            }

            // Cleanup local pkg
            BackupEnv.su("rm -f \"$pkgPath\"", 10_000)
            callback?.onProgress("同步完成: $pkgName", 1, pkgSize)
        } catch (e: Exception) {
            callback?.onProgress("同步异常: ${e.message}", 0, 0)
        }
    }

    // ── Test remote (WebDAV) ──

    fun testRemoteConnection(remote: String, configPath: String = ""): String {
        // Read WebDAV settings from settings_config.json
        val settingsCfg = try {
            val settingsFile = File(BackupEnv.filesDirPath, "settings_config.json")
            JSONObject(settingsFile.readText())
        } catch (_: Exception) { JSONObject() }
        val webdavUrl = settingsCfg.optString("webdav_url", "")
        val webdavUser = settingsCfg.optString("webdav_user", "")
        val webdavPass = settingsCfg.optString("webdav_pass", "")

        if (webdavUrl.isBlank() || webdavUser.isBlank()) {
            return "⚠️ WebDAV未配置"
        }

        return try {
            val client = WebDavClient(webdavUrl, webdavUser, webdavPass)
            val result = kotlinx.coroutines.runBlocking { client.testConnection() }
            if (result.isSuccess) {
                // Try listing the remote path
                val listResult = kotlinx.coroutines.runBlocking { client.list(remote.ifBlank { "." }) }
                if (listResult.isSuccess) {
                    val dirs = listResult.getOrNull()?.take(10) ?: emptyList()
                    if (dirs.isEmpty()) "✅ 连接成功（远端无文件）"
                    else "✅ 连接成功\n${dirs.joinToString("\n") { "📦 ${it.path}" }}"
                } else {
                    "✅ 连接成功"
                }
            } else {
                "连接失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        } catch (e: Exception) {
            "启动失败: ${e.message}"
        }
    }

    // ── Rebuild DB State ──

    fun rebuildDbState(): String {
        val results = mutableListOf<String>()
        val rebuiltRecords = JSONArray()
        return try {
            val usersOutput = RootGateways.runQuiet(
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
                val filesOutput = RootGateways.runQuiet(
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
                        val pResult = RootGateways.run(
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
                        val pResult = RootGateways.run(
                            "$dec \"${baseline.absolutePath}\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1",
                            30_000
                        )
                        val rowId = pResult.stdout.trim().toLongOrNull()
                        if (rowId != null && rowId > 0) state.put("lastMessageRowId", rowId)
                    } catch (_: Exception) {}
                }

                // Write state to user dir
                val tmpState = File(BackupEnv.filesDirForWrite(), "db_state_${userDir.name}.json")
                tmpState.writeText(state.toString())
                RootGateways.run(
                    "cp \"${tmpState.absolutePath}\" \"${File(userDir, DB_STATE_FILE).absolutePath}\" && chmod 664 \"${File(userDir, DB_STATE_FILE).absolutePath}\"",
                    10_000
                )
                results.add(
                    "${userDir.name}: rowId=${state.optLong("lastMessageRowId", 0)}"
                )
            }
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            val outArr = JSONArray()
            for (rec in sorted) outArr.put(rec)
            val tmpRecords = File(BackupEnv.filesDirForWrite(), WxHookPaths.RECORDS_FILE)
            tmpRecords.writeText(outArr.toString())
            RootGateways.run(
                "cp \"${tmpRecords.absolutePath}\" \"${File(BackupEnv.backupDir, WxHookPaths.RECORDS_FILE).absolutePath}\" && chmod 664 \"${File(BackupEnv.backupDir, WxHookPaths.RECORDS_FILE).absolutePath}\"",
                10_000
            )
            results.joinToString("\n") + "\nrecords=" + sorted.size
        } catch (e: Exception) {
            "重建失败: ${e.message}"
        }
    }
}
