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
                BackupManifest.saveDbState(userHash, tag, maxRowId)
            }

            // 3. Scan source files for manifest
            val sourceFiles = wxPaths.flatMap { wxBasePath ->
                FileManifest.scanWeChatAttachments(wxBasePath, WeChatSourceResolver.extractUserHash(wxBasePath), ATT_DIRS)
            }
            val manifest = FileManifest.toManifest(sourceFiles, tag)
            FileManifest.save(dir, manifest)
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
                    File(dir, "file_manifest.json").absolutePath, "$hash/file_manifest.json")
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
                        BackupManifest.updateDbState(userHash, tag, incrTo.toString())
                    } else {
                        callback?.onProgress("[$userHash] DB增量输出为空", totalFiles, totalSize)
                    }
                }
            }

            // 2. Attachments incremental via manifest diff
            val oldManifest = FileManifest.load(dir)
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)

                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    try {
                        // Scan current source files for this attachment directory
                        val currentFiles = FileManifest.scanWeChatAttachments(
                            wxBasePath, userHash, listOf(attDir)
                        )
                        // Filter to only added or modified relative to old manifest
                        val toCopy = currentFiles.filter { entry ->
                            val oldEntry = FileManifest.findEntry(oldManifest, entry.path)
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

            // 3. Update manifest with current state, then package
            val currentSourceFiles = wxPaths.flatMap { wxBasePath ->
                FileManifest.scanWeChatAttachments(
                    wxBasePath,
                    WeChatSourceResolver.extractUserHash(wxBasePath),
                    ATT_DIRS,
                )
            }
            val diff = FileManifest.diff(oldManifest, currentSourceFiles)
            if (diff.added.isNotEmpty() || diff.modified.isNotEmpty() || diff.deleted.isNotEmpty()) {
                val updatedManifest = FileManifest.toManifest(currentSourceFiles, tag)
                FileManifest.save(dir, updatedManifest)
                callback?.onProgress(
                    "清单已更新: +${diff.added.size} ~${diff.modified.size} -${diff.deleted.size}",
                    totalFiles,
                    totalSize,
                )
            }

            // 3b. Package incremental changes into tar.zst via JNI

            // Add db_state.json, db_config.json, file_manifest.json per user
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                incrSources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDataDir, "db_state.json").absolutePath, "$userHash/db_state.json")
                incrSources += NativeArchivePlan.Source(
                    File(BackupEnv.backupDir, "db_config.json").absolutePath, "$userHash/db_config.json")
                incrSources += NativeArchivePlan.Source(
                    File(dir, "file_manifest.json").absolutePath, "$userHash/file_manifest.json")
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

    fun rebuildDbState(): String {
        val results = mutableListOf<String>()
        val rebuiltRecords = JSONArray()
        return try {
            // 1. Check if WeChat is running
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty())
                return "微信未运行，请先打开微信再重建"

            // 2. For each WeChat user, read live rowid + file count
            val liveStates = mutableMapOf<String, JSONObject>()
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val live = JSONObject()
                live.put("hash", userHash)

                // Query live max(rowid)
                val workDb = "/data/local/tmp/wxhook_backup/wxhook_rebuild.db"
                val pwd = runCatching {
                    JSONObject(BackupEnv.suOut(
                        "cat ${BackupEnv.backupDir}/db_config.json 2>/dev/null"
                    )).optString("password", "")
                }.getOrDefault("")
                if (pwd.isNotEmpty()) {
                    val rowId = runCatching {
                        RootGateways.run("mkdir -p /data/local/tmp/wxhook_backup", 5_000)
                        RootGateways.run("dd if=\"$wxBasePath/EnMicroMsg.db\" of=\"$workDb\" bs=4M status=none 2>/dev/null", 300_000)
                        val sqlScript = "/data/local/tmp/wxhook_backup/rebuild_rowid.sql"
                        val script = ".output /dev/null\n" +
                            "PRAGMA key = '$pwd';\n" +
                            "PRAGMA cipher_compatibility = 3;\n" +
                            "PRAGMA cipher_page_size = 1024;\n" +
                            "PRAGMA kdf_iter = 4000;\n" +
                            "PRAGMA cipher_use_hmac = OFF;\n" +
                            ".output stdout\n" +
                            "SELECT coalesce(max(rowid), 0) FROM message;\n"
                        RootGateways.runQuiet("printf '%s' '${script.replace("'", "'\\''")}' > $sqlScript")
                        val ld = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6'"
                        val r = RootGateways.run("$ld ${BackupEnv.binDir}/sqlcipher \"$workDb\" < $sqlScript 2>/dev/null", 30_000)
                        RootGateways.run("rm -f $workDb $workDb-shm $workDb-wal $sqlScript", 10_000)
                        r.stdout.lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLong() ?: 0L
                    }.getOrDefault(0L)
                    if (rowId > 0) live.put("lastMessageRowId", rowId)
                }

                // Count attachment files live
                val fileCount = wxPaths.flatMap { wxBasePath2 ->
                    FileManifest.scanWeChatAttachments(wxBasePath2,
                        WeChatSourceResolver.extractUserHash(wxBasePath2),
                        listOf("image2", "voice2", "video", "emoji", "avatar", "cdn", "record", "favorite"))
                }.size
                live.put("fileCount", fileCount)
                liveStates[userHash] = live
            }

            // 3. Scan archives and generate records
            val fullArchives = RootGateways.runQuiet(
                "ls ${BackupEnv.backupDataDir}/wxbackup_full_*.tar.zst 2>/dev/null"
            ).lines().filter { it.isNotBlank() }.distinct().sorted()
            val incrArchives = RootGateways.runQuiet(
                "ls ${BackupEnv.backupDataDir}/incr_attachments_*.tar.zst 2>/dev/null"
            ).lines().filter { it.isNotBlank() }.sorted()

            // 4. Build records from archives + live data, update db_state
            val dbStateFile = File(BackupEnv.backupDataDir, "db_state.json")
            val dbState = runCatching {
                JSONObject(BackupEnv.backupRead(dbStateFile.absolutePath))
            }.getOrDefault(JSONObject())

            for ((userHash, live) in liveStates) {
                val state = JSONObject().apply { put("restoredAt", System.currentTimeMillis()) }
                // Use live rowid (most current)
                if (live.has("lastMessageRowId"))
                    state.put("lastMessageRowId", live.getLong("lastMessageRowId"))

                // Full archive records
                for (arc in fullArchives) {
                    val f = File(arc)
                    rebuiltRecords.put(JSONObject().apply {
                        put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(Date(f.lastModified())))
                        put("type", "full")
                        put("time", f.lastModified())
                        put("fileCount", 1)
                        put("totalSize", BackupEnv.backupSize(arc))
                        put("compression", "zstd")
                        put("message", "全量备份: ${f.name}")
                        put("files", JSONArray().put(f.name))
                    })
                }

                // Incremental archive records
                if (incrArchives.isNotEmpty()) {
                    state.put("incrCount", incrArchives.size)
                    for (arc in incrArchives) {
                        val f = File(arc)
                        val size = BackupEnv.backupSize(arc)
                        // Extract db_state.json from archive for historical rowid (for display only)
                        val histRowId = runCatching {
                            val cmd = "${BackupEnv.binDir}/zstd -dc \"${f.absolutePath}\" 2>/dev/null | " +
                                "${BackupEnv.binDir}/tar -xO \"$userHash/db_state.json\" 2>/dev/null | head -c 4096"
                            val out = RootGateways.runQuiet(cmd, 30_000).trim()
                            JSONObject(out).optLong("lastMessageRowId", 0)
                        }.getOrDefault(0L)
                        // Extract file_manifest.json for file count
                        val fileCount = runCatching {
                            val cmd = "${BackupEnv.binDir}/zstd -dc \"${f.absolutePath}\" 2>/dev/null | " +
                                "${BackupEnv.binDir}/tar -xO \"$userHash/file_manifest.json\" 2>/dev/null | head -c 4096"
                            val out = RootGateways.runQuiet(cmd, 30_000).trim()
                            JSONObject(out).optInt("fileCount", 1)
                        }.getOrDefault(1)

                        rebuiltRecords.put(JSONObject().apply {
                            put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(Date(f.lastModified())))
                            put("type", "incremental")
                            put("time", f.lastModified())
                            put("fileCount", fileCount)
                            put("totalSize", size)
                            put("compression", "zstd")
                            put("newFiles", fileCount)
                            put("incrRowId", histRowId)
                            put("message", "增量备份: ${f.name}")
                            put("files", JSONArray().put(f.name))
                        })
                    }
                }

                // Update centralized db_state
                val all = runCatching {
                    JSONObject(BackupEnv.backupRead(dbStateFile.absolutePath))
                }.getOrDefault(JSONObject())
                all.put(userHash, state)
                RootGateways.writeFile(dbStateFile.absolutePath, all.toString())
                results.add("${userHash}: rowId=${state.optLong("lastMessageRowId", 0)}")
            }

            // 5. Sort and save records
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            val outArr = JSONArray()
            for (rec in sorted) outArr.put(rec)
            RootGateways.writeFile(File(BackupEnv.backupDataDir, WxHookPaths.RECORDS_FILE).absolutePath, outArr.toString())
            results.joinToString("\n") +
                "\nrecords=" + sorted.size +
                "\n（rowid 来自微信实时数据库，fileCount 来自实时扫描）"
        } catch (e: Exception) {
            "重建失败: ${e.message}"
        }
    }
}
