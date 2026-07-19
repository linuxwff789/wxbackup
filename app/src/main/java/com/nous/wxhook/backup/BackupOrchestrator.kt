package com.nous.wxhook.backup
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.core.command.CommandResult

import android.util.Log
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import com.nous.wxhook.storage.WxHookPaths
import kotlinx.coroutines.runBlocking
import com.nous.wxhook.sync.WebDavClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            val dir = File(BackupEnv.backupDataDir); if (!dir.exists()) dir.mkdirs()
            var totalFiles = 0L; var totalSize = 0L
            val databaseSources = mutableListOf<NativeArchivePlan.Source>()

            // 1. Find WeChat users
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return BackupHookLocal.Result(false, "微信未运行或未找到数据")

            // 2. Dump each database as raw SQL. The outer libarchive tar.zst is its only compression layer.
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)

                callback?.onProgress("[$userHash] 数据库基线...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val dumpResult = ArchiveService.decryptAndDump(dbSrc)
                val dumpPath = dumpResult.removePrefix("OK:").takeIf { dumpResult.startsWith("OK:") }
                if (dumpPath == null) return BackupHookLocal.Result(false, "数据库导出失败: $userHash")
                databaseSources += NativeArchivePlan.Source(
                    dumpPath,
                    "$userHash/${FullBackupLayout.databaseDumpName()}",
                )

                // Save DB state
                val maxRowId = runCatching {
                    val pwd = ArchiveService.getDbPassword()
                    val decDb = "/data/local/tmp/wxhook_backup/wxhook_dec.db"
                    val exists = RootGateways.runQuiet("test -e \"$decDb\" && echo 1").trim() == "1"
                    if (!exists || pwd.isEmpty()) return@runCatching 0L
                    val sqlScript = "/data/local/tmp/wxhook_backup/rowid_query.sql"
                    RootGateways.run("mkdir -p /data/local/tmp/wxhook_backup", 5_000)
                    val scriptContent = ".output /dev/null\n" +
                        "PRAGMA key = '$pwd';\n" +
                        "PRAGMA cipher_compatibility = 3;\n" +
                        "PRAGMA cipher_page_size = 1024;\n" +
                        "PRAGMA kdf_iter = 4000;\n" +
                        "PRAGMA cipher_use_hmac = OFF;\n" +
                        ".output stdout\n" +
                        "SELECT coalesce(max(rowid), 0) FROM message;\n"
                    RootGateways.runQuiet("printf '%s' '${scriptContent.replace("'", "'\\''")}' > $sqlScript")
                    val ld = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6'"
                    val result = RootGateways.run("$ld ${BackupEnv.binDir}/sqlcipher \"$decDb\" < $sqlScript 2>/dev/null", 30_000)
                    RootGateways.run("rm -f $sqlScript", 5_000)
                    result.stdout.lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLong() ?: 0L
                }.getOrDefault(0L)
                BackupManifest.saveDbState(userHash, tag, 0L, maxRowId)
            }

            // 3. Scan source files for manifest
            val sourceFiles = wxPaths.flatMap { wxBasePath ->
                FileManifest.scanWeChatAttachments(wxBasePath, WeChatSourceResolver.extractUserHash(wxBasePath), ATT_DIRS)
            }
            val manifest = FileManifest.toManifest(sourceFiles, tag)
            FileManifest.save(dir, manifest)
            // Per-user manifest
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, hash)
                RootGateways.mkdirs(userDir.absolutePath)
                val userFiles = sourceFiles.filter { it.path.startsWith("$hash/") }
                FileManifest.save(userDir, FileManifest.toManifest(userFiles, tag))
            }
            totalFiles += sourceFiles.size

            // 4. Save config and state
            BackupManifest.saveDbConfig()
            BackupManifest.saveState(tag, totalFiles, totalSize)
            BackupManifest.addRecord(
                BackupManifest.createRecord(tag, "full", totalFiles, totalSize, "全量备份完成",
                    durationMs = System.currentTimeMillis() - startTime)
            )

            // 5. Package sources into one tar.zst in libsu RootService.
            // 5. Package sources into one tar.zst — write directly to sdcard
            val pkgFile = File(dir, "wxbackup_full_$tag.tar.zst")
            val tmpPkg = pkgFile.absolutePath
            val sources = mutableListOf<NativeArchivePlan.Source>()
            sources += databaseSources
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                sources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDataDir, "$hash/db_state.json").absolutePath, "$hash/db_state.json")
                sources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDataDir, "$hash/file_manifest.json").absolutePath, "$hash/file_manifest.json")
                sources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDir, "db_config.json").absolutePath, "$hash/db_config.json")
            }

            // Add files from scan results directly (avoid Binder limit on loading manifest)
            for (entry in sourceFiles) {
                val arcPath = entry.path
                val slashIdx = arcPath.indexOf('/')
                if (slashIdx < 0) continue
                val fileHash = arcPath.substring(0, slashIdx)
                val relPath = arcPath.substring(slashIdx + 1)
                val base = wxPaths.firstOrNull {
                    WeChatSourceResolver.extractUserHash(it) == fileHash
                } ?: continue
                sources += NativeArchivePlan.Source("$base/$relPath", arcPath)
            }

            val plan = NativeArchivePlan(tmpPkg, sources)
            android.util.Log.i("wxhook:PKG", "plan sources: ${sources.size}")
            // Write pairs file locally, then root-copy (Binder content too large for direct writeFile)
            val pairsFile = File(dir, "archive_pairs.txt").absolutePath
            val localPairs = File(BackupEnv.filesDirPath, "archive_pairs.txt")
            val pairsContent = plan.toPairsContent()
            try {
                localPairs.writeText(pairsContent)
            } catch (_: Exception) {
                RootGateways.delete(tmpPkg)
                return BackupHookLocal.Result(false, "写入源文件清单失败")
            }
            if (!RootGateways.copy(localPairs.absolutePath, pairsFile)) {
                RootGateways.delete(tmpPkg)
                localPairs.delete()
                return BackupHookLocal.Result(false, "写入源文件清单失败")
            }
            localPairs.delete()
            val writeResult = RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd())
            val verifyResult = if (writeResult == 0) RootGateways.verifyTarZstd(tmpPkg) else -1
            val pkgSize = BackupEnv.suOut("stat -c %s \"$tmpPkg\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            Log.i("wxhook:PKG", "native write=$writeResult verify=$verifyResult size=$pkgSize sources=${sources.size}")
            if (writeResult != 0 || verifyResult <= 0 || pkgSize <= 0L) {
                RootGateways.delete(tmpPkg)
                RootGateways.delete(pairsFile)
                return BackupHookLocal.Result(false, "打包失败: native=$writeResult verify=$verifyResult")
            }
            RootGateways.delete(pairsFile)

            // 6. Cloud sync
            cloudSync(callback)

            BackupHookLocal.Result(true,
                "全量备份完成: ${totalFiles}个文件, ${BackupManifest.formatSize(totalSize)}, 包: ${pkgFile.name}")
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
            val dir = File(BackupEnv.backupDataDir)
            var totalFiles = 0L; var totalSize = 0L; var newFiles = 0L

            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return BackupHookLocal.Result(false, "微信未运行或未找到数据")

            var incrFrom = 0L
            var incrTo = 0L

            // Collect all incr sources (SQL, config, attachments)
            val incrSources = mutableListOf<NativeArchivePlan.Source>()

            // 1. DB incremental
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)

                val dbState = BackupManifest.loadDbState(userHash)
                val lastRowId = dbState.optLong("lastMessageRowId", 0)
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
                    if (BackupEnv.backupExists(gzPath) && BackupEnv.backupSize(gzPath) > 0) {
                            // Extract last rowid from raw SQL directly (no longer compressed)
                            incrTo = runCatching {
                                BackupEnv.suOut(
                                    "tail -1 \"$gzPath\" 2>/dev/null | cut -d'(' -f2 | cut -d',' -f1"
                                ).trim().toLong()
                            }.getOrDefault(lastRowId)

                        val incrSqlName = "incr_${incrFrom}_to_${incrTo}.sql"
                        val tmpDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}"
                        val tmpSql = "$tmpDir/$incrSqlName"
                        // Raw SQL from decryptIncremental, copy directly into tar
                        RootGateways.run("mkdir -p \"$tmpDir\"", 5_000)
                        val ok = RootGateways.run("cp \"$gzPath\" \"$tmpSql\" 2>/dev/null", 10_000).isSuccess
                        if (ok && BackupEnv.backupExists(tmpSql) && BackupEnv.backupSize(tmpSql) > 0) {
                            totalFiles++; newFiles++
                            incrSources += NativeArchivePlan.Source(tmpSql, "$userHash/$incrSqlName")
                            callback?.onProgress(
                                "[$userHash] DB增量: ${incrTo - incrFrom}条新消息",
                                totalFiles, totalSize
                            )
                        } else {
                            callback?.onProgress(
                                "[$userHash] DB增量文件无效", totalFiles, totalSize
                            )
                        }
                        // Always update db_state during incr (tag/time even if rowid unchanged)
                        BackupManifest.updateDbState(userHash, tag, incrFrom, incrTo)
                    } else {
                        callback?.onProgress("[$userHash] DB增量输出为空", totalFiles, totalSize)
                    }
                }
            }

            // 2. Attachments incremental via per-user manifest diff
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, userHash)
                val userOldManifest = FileManifest.load(userDir)
                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    try {
                        // Scan current source files for this attachment directory
                        val currentFiles = FileManifest.scanWeChatAttachments(
                            wxBasePath, userHash, listOf(attDir)
                        )
                        // Filter to only added or modified relative to per-user manifest
                        val toCopy = currentFiles.filter { entry ->
                            val oldEntry = FileManifest.findEntry(userOldManifest, entry.path)
                            oldEntry == null || oldEntry.size != entry.size || oldEntry.mtime != entry.mtime
                        }
                        if (toCopy.isEmpty()) continue

                        callback?.onProgress(
                            "[$userHash] 增量 $attDir: ${toCopy.size}个",
                            totalFiles, totalSize
                        )
                        for (entry in toCopy) {
                            val rel = entry.path.removePrefix("$userHash/")
                            val srcFile = "$wxBasePath/$rel"
                            val dstFile = File(BackupEnv.backupDataDir, "tmp/${tag}_${userHash}/$rel")
                            dstFile.parentFile?.mkdirs()
                            val cpResult = BackupEnv.su(
                                "cp \"$srcFile\" \"${dstFile.absolutePath}\" && chmod 644 \"${dstFile.absolutePath}\""
                            )
                            if (cpResult.isSuccess &&
                                BackupEnv.backupExists(dstFile.absolutePath) &&
                                BackupEnv.backupSize(dstFile.absolutePath) > 0) {
                                totalFiles++; totalSize += BackupEnv.backupSize(dstFile.absolutePath); newFiles++
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("wxhook:Backup", "Incr $userHash/$attDir failed: $e")
                    }
                }
            }

            // 3. Update per-user manifest with full current state, then package
            val allCurrentFiles = mutableListOf<FileEntry>()
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, hash)
                RootGateways.mkdirs(userDir.absolutePath)

                val userCurrentFiles = FileManifest.scanWeChatAttachments(wxBasePath, hash, ATT_DIRS)
                allCurrentFiles.addAll(userCurrentFiles)

                val userOldManifest = FileManifest.load(userDir)
                val userDiff = FileManifest.diff(userOldManifest, userCurrentFiles)
                if (userDiff.added.isNotEmpty() || userDiff.modified.isNotEmpty() || userDiff.deleted.isNotEmpty()) {
                    // Save FULL per-user manifest to disk (for next incremental diff)
                    val userUpdatedManifest = FileManifest.toManifest(userCurrentFiles, tag)
                    userUpdatedManifest.put("incrFrom", incrFrom)
                    userUpdatedManifest.put("incrTo", incrTo)
                    FileManifest.save(userDir, userUpdatedManifest)

                    // Save incremental-only manifest for archive (not full set)
                    val incrFiles = userDiff.added + userDiff.modified
                    if (incrFiles.isNotEmpty()) {
                        val incrOnlyManifest = FileManifest.toManifest(incrFiles, tag)
                        incrOnlyManifest.put("incrFrom", incrFrom)
                        incrOnlyManifest.put("incrTo", incrTo)
                        val tmpManifestDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${hash}"
                        RootGateways.mkdirs(tmpManifestDir)
                        RootGateways.writeFile("$tmpManifestDir/file_manifest.json", incrOnlyManifest.toString())
                    }

                    callback?.onProgress(
                        "[$hash] 清单已更新: +${userDiff.added.size} ~${userDiff.modified.size} -${userDiff.deleted.size}",
                        totalFiles, totalSize,
                    )
                }
            }

            // Update global manifest for backward compat (not used for diff anymore)
            val globalManifest = FileManifest.toManifest(allCurrentFiles, tag)
            FileManifest.save(dir, globalManifest)

            // 3b. Package incremental changes into tar.zst via JNI

            // Add db_state.json, db_config.json, file_manifest.json per user
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                incrSources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDataDir, "$userHash/db_state.json").absolutePath, "$userHash/db_state.json")
                incrSources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDir, "db_config.json").absolutePath, "$userHash/db_config.json")
                // Use incremental-only manifest from tmp (not full disk version)
                val incrManifestPath = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}/file_manifest.json"
                if (RootGateways.exists(incrManifestPath)) {
                    incrSources += NativeArchivePlan.Source(incrManifestPath, "$userHash/file_manifest.json")
                } else {
                    // Fallback to full manifest if no incremental (shouldn't normally happen)
                    incrSources += NativeArchivePlan.Source(
                        File(BackupEnv.backupDataDir, "$userHash/file_manifest.json").absolutePath, "$userHash/file_manifest.json")
                }
            }
            // Add changed attachments from tmp/ (find individual files, not directory trees)
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val tmpDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}"
                val files = RootGateways.runQuiet(
                    "find \"$tmpDir\" -type f 2>/dev/null"
                ).lines().filter { it.isNotBlank() }
                for (f in files) {
                    val arcPath = f.removePrefix("${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}/")
                    incrSources += NativeArchivePlan.Source(f, "$userHash/$arcPath")
                }
            }
            
            if (incrSources.isNotEmpty()) {
                val incrArchive = File(dir, "incr_attachments_$tag.tar.zst")
                val tmpPkg = incrArchive.absolutePath
                val plan = NativeArchivePlan(tmpPkg, incrSources)
                val pairsFile = File(dir, "incr_pairs.txt").absolutePath
                val localPairs = File(BackupEnv.filesDirPath, "incr_pairs.txt")
                try { localPairs.writeText(plan.toPairsContent()) } catch (_: Exception) {
                    return BackupHookLocal.Result(false, "写入增量清单失败")
                }
                if (!RootGateways.copy(localPairs.absolutePath, pairsFile) ||
                    RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd()) != 0) {
                    localPairs.delete()
                    return BackupHookLocal.Result(false, "增量打包失败")
                }
                localPairs.delete()
                RootGateways.delete(pairsFile)
                val pkgSize = BackupEnv.backupSize(tmpPkg)
                if (pkgSize > 0L) {
                    totalFiles++; totalSize += pkgSize; newFiles++
                    callback?.onProgress("增量附件: ${incrArchive.name}", totalFiles, totalSize)
                    Log.i("wxhook:PKG", "incr archive: $tmpPkg size=$pkgSize")
                }
            }

            // Clean up tmp dir after packaging
            RootGateways.runQuiet("rm -rf ${BackupEnv.backupDataDir}/tmp/${tag}_* 2>/dev/null")

            // 5. Cloud sync (after all data committed)
            cloudSync(callback)

            // 6. Save state LAST (after all data/manifest committed)
            BackupManifest.saveState(tag, totalFiles, totalSize)

            val rec = BackupManifest.createRecord(
                tag, "incremental", totalFiles, totalSize,
                if (newFiles > 0) "增量: ${newFiles}个文件, ${BackupManifest.formatSize(totalSize)}" else "无新文件",
                durationMs = System.currentTimeMillis() - startTime
            )
            rec.put("newFiles", newFiles)
            if (incrSources.isNotEmpty()) rec.put("hasIncrArchive", true)
            BackupManifest.addRecord(rec)
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个文件(${BackupManifest.formatSize(totalSize)}), DB:${incrFrom}→${incrTo}" else "无新文件"
            
            BackupHookLocal.Result(true, msg)
        } catch (e: Exception) {
            BackupHookLocal.Result(false, "增量备份失败: ${e.message}")
        }
    }

    // ── Remote sync via WebDAV ──

    fun cloudSync(callback: BackupHookLocal.ProgressCallback?, archivePath: String? = null, tarFiles: List<String> = emptyList()) {
        try {
            val configFile = File(BackupEnv.backupDir, "remote_config.json")
            if (!configFile.exists()) return
            val config = JSONObject(BackupEnv.suOut("cat \"${configFile.absolutePath}\" 2>/dev/null").ifBlank { "{}" })
            if (!config.optBoolean("enabled", false)) return

            val settingsCfg = try { JSONObject(File(BackupEnv.filesDirPath, "settings_config.json").readText()) } catch (_: Exception) { JSONObject() }
            val webdavUrl = settingsCfg.optString("webdav_url", "")
            val webdavUser = settingsCfg.optString("webdav_user", "")
            val webdavPass = settingsCfg.optString("webdav_pass", "")
            val remoteBase = config.optString("remote", "wxhook-backup")
            if (webdavUrl.isBlank() || webdavUser.isBlank()) return

            // Find the latest wxbackup_full_*.tar.zst
            val pkgPath = if (archivePath != null && BackupEnv.backupExists(archivePath)) {
                archivePath
            } else {
                val found = BackupEnv.suOut("ls -t ${BackupEnv.backupDir}/wxbackup_full_*.tar.zst 2>/dev/null | head -1").trim()
                if (found.isBlank()) { callback?.onProgress("无备份包可同步", 0, 0); return }
                found
            }

            val pkgSize = BackupEnv.backupSize(pkgPath)
            if (pkgSize < 100L) { callback?.onProgress("备份包为空", 0, 0); return }
            val pkgName = File(pkgPath).name
            callback?.onProgress("上传 $pkgName (${BackupManifest.formatSize(pkgSize)})...", 0, pkgSize)

            val client = WebDavClient(webdavUrl, webdavUser, webdavPass)
            val testResult = kotlinx.coroutines.runBlocking { client.testConnection() }
            if (testResult.isFailure) {
                callback?.onProgress("WebDAV连接失败: ${testResult.exceptionOrNull()?.message}", 0, 0); return
            }
            kotlinx.coroutines.runBlocking { client.ensureDirectory(remoteBase) }

            val remoteFiles = kotlinx.coroutines.runBlocking { client.list(remoteBase) }.getOrNull() ?: emptyList()
            val remoteMatch = remoteFiles.find { it.path.endsWith(pkgName) }
            if (remoteMatch != null && remoteMatch.size == pkgSize) {
                callback?.onProgress("跳过: $pkgName (远程已存在)", 1, pkgSize)
            } else {
                val uploadResult = kotlinx.coroutines.runBlocking { client.upload(File(pkgPath), "$remoteBase/$pkgName") }
                if (uploadResult.isFailure) {
                    callback?.onProgress("上传失败: ${uploadResult.exceptionOrNull()?.message}", 0, 0); return
                }
                callback?.onProgress("已上传: $pkgName", 1, pkgSize)
            }
            callback?.onProgress("同步完成", 1, pkgSize)
        } catch (e: Exception) {
            callback?.onProgress("同步异常: ${e.message}", 0, 0)
        }
    }

    // ── Test remote (WebDAV) ──

    fun testRemoteConnection(remote: String, configPath: String = ""): String {
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

    private fun hasStoragePermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
        android.os.Environment.isExternalStorageManager()

    private fun requestStoragePermission() {
        val ctx = com.nous.wxhook.App.instance ?: return
        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { ctx.startActivity(intent) } catch (_: Exception) {}
    }

    fun rebuildDbState(callback: BackupHookLocal.ProgressCallback? = null): String {
        val results = mutableListOf<String>()
        val rebuiltRecords = JSONArray()
        if (!hasStoragePermission()) {
            Log.e("wxhook:rebuild", "MANAGE_EXTERNAL_STORAGE not granted, requesting...")
            requestStoragePermission()
            callback?.onProgress("⚠️ 请在设置中授权「所有文件访问」权限", 0, 0)
        }
        return try {
            callback?.onProgress("检查微信登录状态...", 0, 0)
            // 1. Check WeChat is running (needed for manifest scan)
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty())
                return "微信未运行，请先打开微信再重建"

            // 2. Scan archives (shared across users)
            callback?.onProgress("扫描备份文件...", 0, 0)
            val fullArchives = RootGateways.runQuiet(
                "ls ${BackupEnv.backupDataDir}/wxbackup_full_*.tar.zst 2>/dev/null"
            ).lines().filter { it.isNotBlank() }.sorted()
            val incrArchives = RootGateways.runQuiet(
                "ls ${BackupEnv.backupDataDir}/incr_attachments_*.tar.zst 2>/dev/null"
            ).lines().filter { it.isNotBlank() }.sorted()
            callback?.onProgress("全量: ${fullArchives.size}个, 增量: ${incrArchives.size}个", 0, 0)

            data class ChainPoint(
                val from: Long, val to: Long, val time: Long,
                val name: String, val isFull: Boolean, val hash: String)

            // Pre-read centralized db_state (Binder may die during long operations)
            val centralizedStates = mutableMapOf<String, JSONObject>()
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                centralizedStates[hash] = BackupManifest.loadDbState(hash)
            }

            // 3. For each user, build chain from archives
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val centralized = centralizedStates[hash] ?: JSONObject()
                callback?.onProgress("处理用户: $hash...", 0, 0)
                val points = mutableListOf<ChainPoint>()

                // Full archives: NativeArchive JNI — direct app-process read, no Binder
                callback?.onProgress("[$hash] 分析全量包...", 0, 0)
                for (arc in fullArchives) {
                    val f = File(arc)
                    val rowId = try {
                        NativeArchive.getFullArchiveRowId(arc, hash)
                    } catch (e: Throwable) {
                        Log.e("wxhook:rebuild", "getFullArchiveRowId failed for ${f.name}", e)
                        0L
                    }
                    if (rowId > 0) points += ChainPoint(
                        centralized.optLong("lastMessageRowIdFrom", 0L), rowId,
                        f.lastModified(), f.name, true, hash)
                    Log.i("wxhook:rebuild", "full arc ${f.name}: rowId=$rowId")
                }

                // Incremental archives: extract db_state.json for from/to
                callback?.onProgress("[$hash] 分析增量包...", 0, 0)
                for (arc in incrArchives) {
                    val f = File(arc)
                    var incrFrom = 0L; var incrTo = 0L
                    try {
                        val dbJson = NativeArchive.readFileFromTar(arc, "$hash/db_state.json")
                        incrFrom = try { JSONObject(dbJson).optLong("lastMessageRowIdFrom", 0) } catch (_: Exception) { 0L }
                        incrTo = try { JSONObject(dbJson).optLong("lastMessageRowId", 0) } catch (_: Exception) { 0L }
                    } catch (e: Throwable) {
                        Log.e("wxhook:rebuild", "NativeArchive.readFileFromTar for incr db_state failed: $arc", e)
                    }
                    if (incrFrom > 0 && incrTo > 0) {
                        points += ChainPoint(incrFrom, incrTo, f.lastModified(), f.name, false, hash)
                    } else if (incrTo > 0) {
                        try {
                            val listing = NativeArchive.listTar(arc)
                            val m = Regex("incr_(\\d+)_to_(\\d+)\\.sql").find(listing)
                            incrFrom = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                            points += ChainPoint(incrFrom, incrTo, f.lastModified(), f.name, false, hash)
                        } catch (e: Throwable) {
                            Log.e("wxhook:rebuild", "NativeArchive.listTar for incr failed: $arc", e)
                        }
                    }
                    Log.i("wxhook:rebuild", "incr arc ${f.name}: from=$incrFrom to=$incrTo")
                }

                // Longest continuous chain
                callback?.onProgress("[$hash] 计算最长链...", 0, 0)
                Log.i("wxhook:rebuild", "points for $hash: ${points.size}")
                points.sortBy { it.time }
                var chainEnd = 0L; var chainPoints = mutableListOf<ChainPoint>()
                var bestChain = mutableListOf<ChainPoint>()
                for (p in points) {
                    Log.i("wxhook:rebuild", "  point: from=${p.from} to=${p.to} name=${p.name}")
                    if (p.from <= chainEnd || chainEnd == 0L) {
                        chainEnd = maxOf(chainEnd, p.to)
                        chainPoints.add(p)
                        if (chainPoints.size > bestChain.size)
                            bestChain = mutableListOf<ChainPoint>().also { it.addAll(chainPoints) }
                    } else {
                        chainEnd = p.to
                        chainPoints = mutableListOf(p)
                    }
                }
                val safeFrom = if (bestChain.isNotEmpty()) bestChain.minOf { it.from } else 0L
                val safeRowId = if (bestChain.isNotEmpty()) bestChain.maxOf { it.to } else 0L

                // Per-user db_state (only if chain has data)
                if (bestChain.isNotEmpty()) {
                    callback?.onProgress("[$hash] 保存状态: $safeFrom→$safeRowId (链=${bestChain.size})", 0, 0)
                    if (!BackupManifest.saveDbState(hash, "rebuild", safeFrom, safeRowId)) {
                        runBlocking { (RootGateways.gateway as? RootGatewayImpl)?.ensureRootService() }
                        BackupManifest.saveDbState(hash, "rebuild", safeFrom, safeRowId)
                    }
                } else {
                    callback?.onProgress("[$hash] ⚠️ 链为空，跳过保存", 0, 0)
                }

                // Per-user manifest: merge from all archives in chain
                callback?.onProgress("[$hash] 提取附件清单...", 0, 0)
                val userDir = File(BackupEnv.backupDataDir, hash)
                RootGateways.mkdirs(userDir.absolutePath)
                val mergedFiles = mutableListOf<JSONObject>()
                for (cp in bestChain) {
                    val arcPath = File(BackupEnv.backupDataDir, cp.name).absolutePath
                    try {
                        // Shell pipe: zstd→tar→extract manifest (bypass JNI typeflag issue)
                        val json = try {
                            NativeArchive.readFileFromTar(arcPath, "$hash/file_manifest.json")
                        } catch (e: Throwable) { "" }
                        if (json.isNotBlank()) {
                            val manifest = JSONObject(json)
                            val files = manifest.optJSONArray("files") ?: manifest.optJSONArray("entries")
                            if (files != null) {
                                for (i in 0 until files.length()) {
                                    mergedFiles.add(files.getJSONObject(i))
                                }
                                Log.i("wxhook:rebuild", "manifest from ${cp.name}: +${files.length()} files")
                            }
                        } else {
                            Log.e("wxhook:rebuild", "manifest shell pipe empty for ${cp.name}")
                        }
                    } catch (e: Throwable) {
                        Log.e("wxhook:rebuild", "manifest extract failed for ${cp.name}", e)
                    }
                }
                // Save merged manifest to disk (was being read but not persisted)
                if (mergedFiles.isNotEmpty()) {
                    val mergedManifest = JSONObject().apply {
                        put("version", 1)
                        put("tag", "rebuild_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}")
                        put("fileCount", mergedFiles.size)
                        put("files", JSONArray(mergedFiles.toList()))
                    }
                    FileManifest.save(userDir, mergedManifest)
                    Log.i("wxhook:rebuild", "merged manifest saved: ${mergedFiles.size} files to $userDir")
                }

// Records from bestChain
                for (p in bestChain) {
                    rebuiltRecords.put(JSONObject().apply {
                        put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(p.time)))
                        put("type", if (p.isFull) "full" else "incremental")
                        put("time", p.time)
                        put("totalSize", BackupEnv.backupSize(File(BackupEnv.backupDataDir, p.name).absolutePath))
                        put("compression", "zstd")
                        put("newFiles", if (!p.isFull) 1 else 0)
                        put("files", JSONArray().put(p.name))
                        put("message", if (p.isFull) "全量备份" else "增量备份: ${p.from}→${p.to}")
                    })
                }
                results.add("$hash: rowId=$safeRowId (chain=${bestChain.size})")
            }

            // 4. Save backup records (may need to reconnect Binder)
            callback?.onProgress("保存备份记录...", 0, 0)
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            var recordsOk = writeSortedRecords(sorted)
            if (!recordsOk) {
                // Binder may have died during archive reads; reconnect and retry
                runBlocking {
                    (RootGateways.gateway as? RootGatewayImpl)?.ensureRootService()
                }
                recordsOk = writeSortedRecords(sorted)
            }
            if (!recordsOk) android.util.Log.e("wxhook:rebuild", "Failed to write backup_records.json")

            callback?.onProgress("✅ 重建完成: ${sorted.size}条记录", 0, 0)
            results.joinToString("\n") + "\nrecords=" + sorted.size
        } catch (e: Exception) {
            Log.e("wxhook:rebuild", "rebuildDbState failed", e)
            callback?.onProgress("❌ ${e::class.simpleName}: ${e.message}", 0, 0)
            "重建失败: ${e::class.simpleName}: ${e.message}"
        }
    }

    private fun writeSortedRecords(sorted: List<JSONObject>): Boolean {
        val outArr = JSONArray()
        for (rec in sorted) outArr.put(rec)
        return RootGateways.writeFile(
            File(BackupEnv.backupDataDir, WxHookPaths.RECORDS_FILE).absolutePath,
            outArr.toString()
        )
    }
}
