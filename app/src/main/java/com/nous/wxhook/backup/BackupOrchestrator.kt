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
            val dir = File(BackupEnv.backupDir); if (!dir.exists()) dir.mkdirs()
            var totalFiles = 0L; var totalSize = 0L
            val databaseSources = mutableListOf<NativeArchivePlan.Source>()

            // 1. Find WeChat users
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return BackupHookLocal.Result(false, "微信未运行或未找到数据")

            // 2. Dump each database as raw SQL. The outer libarchive tar.zst is its only compression layer.
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.mkdirs()

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
                sources += NativeArchivePlan.Source(File(dir, "$hash/db_state.json").absolutePath, "$hash/db_state.json")
            }
            sources += NativeArchivePlan.Source(File(dir, "file_manifest.json").absolutePath, "file_manifest.json")
            sources += NativeArchivePlan.Source(File(dir, "db_config.json").absolutePath, "db_config.json")

            // Add files from manifest only (not whole directory trees)
            val manifestObj = FileManifest.load(dir)
            val filesArr = manifestObj.optJSONArray("files") ?: org.json.JSONArray()
            for (i in 0 until filesArr.length()) {
                val entry = filesArr.getJSONObject(i)
                val arcPath = entry.getString("path")
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
                        // Extract last rowid via explicit query, not from dump
                        incrTo = runCatching {
                            val pwd = ArchiveService.getDbPassword()
                            val workDb = "/data/local/tmp/wxhook_backup/wxhook_inc.db"
                            RootGateways.run("mkdir -p /data/local/tmp/wxhook_backup", 5_000)
                            val copiedSize = RootGateways.runQuiet("stat -c %s \"$workDb\" 2>/dev/null").trim().toLongOrNull() ?: 0L
                            if (copiedSize < 1_000_000L) {
                                // Still use fallback from compressed dump
                                val dec = if (BackupEnv.useZstd()) "${BackupEnv.binDir}/zstd -dc" else "gzip -dc"
                                BackupEnv.suOut(
                                    "$dec \"$gzPath\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1"
                                ).trim().toLong()
                            } else {
                                val sqlScript = "/data/local/tmp/wxhook_backup/incr_max_rowid.sql"
                                val scriptContent = ".output /dev/null\n" +
                                    "PRAGMA key = '$pwd';\n" +
                                    "PRAGMA cipher_compatibility = 3;\n" +
                                    "PRAGMA cipher_page_size = 1024;\n" +
                                    "PRAGMA kdf_iter = 4000;\n" +
                                    "PRAGMA cipher_use_hmac = OFF;\n" +
                                    ".output stdout\n" +
                                    "SELECT coalesce(max(rowid), $lastRowId) FROM message WHERE rowid > $lastRowId;\n"
                                RootGateways.runQuiet("printf '%s' '${scriptContent.replace("'", "'\\''")}' > $sqlScript")
                                val ld = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6'"
                                val r = RootGateways.run("$ld ${BackupEnv.binDir}/sqlcipher \"$workDb\" < $sqlScript 2>/dev/null", 30_000)
                                RootGateways.run("rm -f $workDb $workDb-shm $workDb-wal $sqlScript", 10_000)
                                r.stdout.lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLong() ?: lastRowId
                            }
                        }.getOrDefault(lastRowId)

                        val incrName = "incr_${incrFrom}_to_${incrTo}" + BackupEnv.ext()
                        val incrPath = "${userDir.absolutePath}/$incrName"
                        val ok = BackupEnv.suCopyResult(gzPath, incrPath)
                        if (ok && BackupEnv.backupExists(incrPath) && BackupEnv.backupSize(incrPath) > 0) {
                            totalFiles++; totalSize += BackupEnv.backupSize(incrPath); newFiles++
                            BackupManifest.updateDbState(userHash, tag, incrTo.toString())
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

            // 2. Attachments incremental via manifest diff (not find -newermt)
            val oldManifest = FileManifest.load(dir)
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.mkdirs()

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
                            // entry.path is "<hash>/image2/..." relative to wxBasePath root
                            // Source absolute: wxBasePath + "/" + entry.path (without hash prefix)
                            val rel = entry.path.removePrefix("$userHash/")
                            val srcFile = "$wxBasePath/$rel"
                            val dstFile = File(userDir.absolutePath, rel)
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

            // 3. Collect incremental DB files per user (not from root dir)
            val incrFiles = mutableListOf<String>()
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                userDir.listFiles()
                    ?.filter { it.name.startsWith("incr_") && it.name.endsWith(BackupEnv.ext()) }
                    ?.sortedBy { it.name }
                    ?.forEach { incrFiles.add(it.name) }
            }

            // 3b. Package incremental attachments into tar.zst via JNI (same as full backup)
            val incrTarFiles = mutableListOf<String>()
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(dir, userHash)
                for (attDir in ATT_DIRS) {
                    val attDirFile = File(userDir, attDir)
                    if (attDirFile.exists() && attDirFile.listFiles()?.isNotEmpty() == true) {
                        incrTarFiles.add("${userDir.absolutePath}/$attDir")
                    }
                }
            }
            if (incrTarFiles.isNotEmpty()) {
                val incrArchive = File(dir, "incr_attachments_$tag.tar.zst")
                val tmpPkg = incrArchive.absolutePath
                val incrSources = incrTarFiles.map { fullPath ->
                    val rel = fullPath.removePrefix("${BackupEnv.backupDir}/")
                    NativeArchivePlan.Source(fullPath, rel)
                }
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

            // 4. Update source manifest with current WeChat attachment state.
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

            // 5. Cloud sync (after all data committed)
            cloudSync(callback)

            // 6. Save state LAST (after all data/manifest committed)
            BackupManifest.saveState(tag, totalFiles, totalSize)

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
            val usersOutput = RootGateways.runQuiet(
                "find ${BackupEnv.backupDir} -mindepth 1 -maxdepth 1 -type d | " +
                "grep -v '/\\.' | grep -v '/tmp$' | grep -v '/.git$'"
            )
            val userDirs = usersOutput.lines().filter { it.isNotBlank() }
            if (userDirs.isEmpty()) return "备份目录为空"
            for (userPath in userDirs) {
                val userDir = File(userPath)
                val state = JSONObject().apply { put("restoredAt", System.currentTimeMillis()) }

                // Read db_state.json for rowid (single file at backup root)
                val dbStateFile = File(BackupEnv.backupDir, "db_state.json")
                val allStates = if (dbStateFile.exists())
                    runCatching { JSONObject(dbStateFile.readText()) }.getOrDefault(JSONObject())
                else JSONObject()
                val dbState = allStates.optJSONObject(userDir.name) ?: JSONObject()
                if (dbState.has("lastMessageRowId")) {
                    state.put("lastMessageRowId", dbState.getLong("lastMessageRowId"))
                }

                // Full backup archives (new format: inside .tar.zst)
                val fullArchives = RootGateways.runQuiet(
                    "ls ${BackupEnv.backupDir}/wxbackup_full_*.tar.zst 2>/dev/null"
                ).lines().filter { it.isNotBlank() && it.endsWith(".tar.zst") }
                for (arc in fullArchives) {
                    val f = File(arc)
                    val size = BackupEnv.backupSize(arc)
                    rebuiltRecords.put(JSONObject().apply {
                        put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(Date(f.lastModified())))
                        put("type", "full")
                        put("time", f.lastModified())
                        put("fileCount", 1)
                        put("totalSize", size)
                        put("compression", "zstd")
                        put("message", "全量备份: ${f.name}")
                        put("files", JSONArray().put(f.name))
                    })
                }

                // Incremental DB dumps (still separate .sql.zst files)
                val incrFiles = RootGateways.runQuiet(
                    "ls ${userDir.absolutePath}/incr_*.sql.zst 2>/dev/null"
                ).lines().filter { it.isNotBlank() }.sorted()

                if (incrFiles.isNotEmpty()) {
                    state.put("incrCount", incrFiles.size)
                    state.put("incrFiles", JSONArray(incrFiles.map { File(it).name }))
                    val lastFile = File(incrFiles.last())
                    try {
                        val pResult = RootGateways.run(
                            "${BackupEnv.binDir}/zstd -dc \"${lastFile.absolutePath}\" 2>/dev/null | tail -1 | cut -d'(' -f2 | cut -d',' -f1",
                            30_000
                        )
                        val rowId = pResult.stdout.trim().toLongOrNull()
                        val prevRowId = state.optLong("lastMessageRowId", 0)
                        if (rowId != null && rowId > prevRowId) state.put("lastMessageRowId", rowId)
                        else if (prevRowId > 0) state.put("lastMessageRowId", prevRowId)
                    } catch (_: Exception) {}
                    for (f in incrFiles) {
                        val m = Regex("incr_(\\d+)_to_(\\d+)\\.sql\\.zst").find(File(f).name)
                        val from = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val to = m?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
                        rebuiltRecords.put(JSONObject().apply {
                            put("tag", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(Date(File(f).lastModified())))
                            put("type", "incremental")
                            put("time", File(f).lastModified())
                            put("fileCount", 1)
                            put("totalSize", BackupEnv.backupSize(f))
                            put("compression", "zstd")
                            put("newFiles", 1)
                            put("incrFrom", from)
                            put("incrTo", to)
                            put("message", "增量备份: DB:${from}→${to}")
                            put("files", JSONArray().put(File(f).name))
                        })
                    }
                }

                // Update centralized db_state.json at backup root
                val newAll = if (dbStateFile.exists())
                    runCatching { JSONObject(dbStateFile.readText()) }.getOrDefault(JSONObject())
                else JSONObject()
                newAll.put(userDir.name, state)
                RootGateways.writeFile(dbStateFile.absolutePath, newAll.toString())
                results.add(
                    "${userDir.name}: rowId=${state.optLong("lastMessageRowId", 0)}"
                )
            }
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            val outArr = JSONArray()
            for (rec in sorted) outArr.put(rec)
            RootGateways.writeFile(File(BackupEnv.backupDir, WxHookPaths.RECORDS_FILE).absolutePath, outArr.toString())
            results.joinToString("\n") + "\nrecords=" + sorted.size
        } catch (e: Exception) {
            "重建失败: ${e.message}"
        }
    }
}
