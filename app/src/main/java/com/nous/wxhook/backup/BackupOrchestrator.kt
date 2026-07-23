package com.nous.wxhook.backup

import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.core.command.CommandResult

import android.util.Base64
import android.util.Log
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import com.nous.wxhook.storage.WxHookPaths
import kotlinx.coroutines.runBlocking
import com.nous.wxhook.sync.Syncer
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
            val dir = File(BackupEnv.backupDataDir).apply { if (!exists()) mkdirs() }
            var totalFiles = 0L
            var totalSize = 0L
            val databaseSources = mutableListOf<NativeArchivePlan.Source>()

            // 1. Find WeChat users
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return BackupHookLocal.Result(false, "微信未运行或未找到数据")

            // 2. Dump each database as raw SQL.
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                callback?.onProgress("[$userHash] 数据库基线...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val dumpResult = ArchiveService.decryptAndDump(dbSrc)
                val dumpPath = dumpResult.removePrefix("OK:").takeIf { dumpResult.startsWith("OK:") }
                if (dumpPath == null) return BackupHookLocal.Result(false, "数据库导出失败: $userHash")
                databaseSources += NativeArchivePlan.Source(dumpPath, "$userHash/${FullBackupLayout.databaseDumpName()}")

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
                    RootGateways.runQuiet("printf '%s' '${scriptContent.replace("'", "'\\'\'")}'> $sqlScript")
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
                BackupManifest.createRecord(tag, "full", totalFiles, totalSize, "全量备份完成", durationMs = System.currentTimeMillis() - startTime)
            )

            // 5. Package sources into one tar.zst
            val pkgFile = File(dir, "wxbackup_full_$tag.tar.zst")
            val tmpPkg = pkgFile.absolutePath
            val sources = mutableListOf<NativeArchivePlan.Source>()
            sources += databaseSources
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                sources += NativeArchivePlan.Source(File(BackupEnv.backupDataDir, "$hash/db_state.json").absolutePath, "$hash/db_state.json")
                sources += NativeArchivePlan.Source(File(BackupEnv.backupDataDir, "$hash/file_manifest.json").absolutePath, "$hash/file_manifest.json")
                sources += NativeArchivePlan.Source(File(BackupEnv.backupDir, "db_config.json").absolutePath, "$hash/db_config.json")
            }
            // Add files from scan results directly
            for (entry in sourceFiles) {
                val arcPath = entry.path
                val slashIdx = arcPath.indexOf('/')
                if (slashIdx < 0) continue
                val fileHash = arcPath.substring(0, slashIdx)
                val relPath = arcPath.substring(slashIdx + 1)
                val base = wxPaths.firstOrNull { WeChatSourceResolver.extractUserHash(it) == fileHash } ?: continue
                sources += NativeArchivePlan.Source("$base/$relPath", arcPath)
            }
            val plan = NativeArchivePlan(tmpPkg, sources)
            val pairsFile = File(dir, "archive_pairs.txt").absolutePath
            val localPairs = File(BackupEnv.filesDirPath, "archive_pairs.txt")
            localPairs.writeText(plan.toPairsContent())
            if (!RootGateways.copy(localPairs.absolutePath, pairsFile)) {
                RootGateways.delete(tmpPkg)
                localPairs.delete()
                return BackupHookLocal.Result(false, "写入源文件清单失败")
            }
            localPairs.delete()
            val writeResult = RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd())
            val verifyResult = if (writeResult == 0) RootGateways.verifyTarZstd(tmpPkg) else -1
            val pkgSize = BackupEnv.suOut("stat -c %s \"$tmpPkg\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (writeResult != 0 || verifyResult <= 0 || pkgSize <= 0L) {
                RootGateways.delete(tmpPkg)
                RootGateways.delete(pairsFile)
                return BackupHookLocal.Result(false, "打包失败: native=$writeResult verify=$verifyResult")
            }
            RootGateways.delete(pairsFile)

            // 6. Cloud sync
            cloudSync(callback)

            BackupHookLocal.Result(true, "全量备份完成: ${totalFiles}个文件, ${BackupManifest.formatSize(totalSize)}, 包: ${pkgFile.name}")
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
                    callback?.onProgress("[${userHash}] 无基线数据，请先全量备份", totalFiles, totalSize)
                    continue
                }

                callback?.onProgress("[${userHash}] DB增量...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val incResult = ArchiveService.decryptIncremental(dbSrc, lastRowId)
                incrFrom = lastRowId
                incrTo = lastRowId
                if (incResult.startsWith("OK:")) {
                    val gzPath = incResult.substring(3)
                    if (BackupEnv.backupExists(gzPath) && BackupEnv.backupSize(gzPath) > 0) {
                        incrTo = runCatching {
                            BackupEnv.suOut("tail -1 \"$gzPath\" 2>/dev/null | cut -d'(' -f2 | cut -d',' -f1").trim().toLong()
                        }.getOrDefault(lastRowId)

                        val incrSqlName = "incr_${incrFrom}_to_${incrTo}.sql"
                        val tmpDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}"
                        val tmpSql = "$tmpDir/$incrSqlName"
                        RootGateways.run("mkdir -p \"$tmpDir\"", 5_000)
                        val ok = RootGateways.run("cp \"$gzPath\" \"$tmpSql\" 2>/dev/null", 10_000).isSuccess
                        if (ok && BackupEnv.backupExists(tmpSql) && BackupEnv.backupSize(tmpSql) > 0) {
                            totalFiles++; newFiles++
                            incrSources += NativeArchivePlan.Source(tmpSql, "$userHash/$incrSqlName")
                            callback?.onProgress("[${userHash}] DB增量: ${incrTo - incrFrom}条新消息", totalFiles, totalSize)
                        } else {
                            callback?.onProgress("[${userHash}] DB增量文件无效", totalFiles, totalSize)
                        }
                        BackupManifest.updateDbState(userHash, tag, incrFrom, incrTo)
                    } else {
                        callback?.onProgress("[${userHash}] DB增量输出为空", totalFiles, totalSize)
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
                        val currentFiles = FileManifest.scanWeChatAttachments(wxBasePath, userHash, listOf(attDir))
                        val toCopy = currentFiles.filter { entry ->
                            val oldEntry = FileManifest.findEntry(userOldManifest, entry.path)
                            oldEntry == null || oldEntry.size != entry.size || oldEntry.mtime != entry.mtime
                        }
                        if (toCopy.isEmpty()) continue

                        callback?.onProgress("[${userHash}] 增量 $attDir: ${toCopy.size}个", totalFiles, totalSize)
                        for (entry in toCopy) {
                            val rel = entry.path.removePrefix("${userHash}/")
                            val srcFile = "$wxBasePath/$rel"
                            val dstFile = File(BackupEnv.backupDataDir, "tmp/${tag}_${userHash}/$rel")
                            dstFile.parentFile?.mkdirs()
                            val cpResult = BackupEnv.su("cp \"$srcFile\" \"${dstFile.absolutePath}\" && chmod 644 \"${dstFile.absolutePath}\"")
                            if (cpResult.isSuccess && BackupEnv.backupExists(dstFile.absolutePath) && BackupEnv.backupSize(dstFile.absolutePath) > 0) {
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
                    val userUpdatedManifest = FileManifest.toManifest(userCurrentFiles, tag)
                    userUpdatedManifest.put("incrFrom", incrFrom)
                    userUpdatedManifest.put("incrTo", incrTo)
                    FileManifest.save(userDir, userUpdatedManifest)

                    val incrFiles = userDiff.added + userDiff.modified
                    if (incrFiles.isNotEmpty()) {
                        val incrOnlyManifest = FileManifest.toManifest(incrFiles, tag)
                        incrOnlyManifest.put("incrFrom", incrFrom)
                        incrOnlyManifest.put("incrTo", incrTo)
                        val tmpManifestDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${hash}"
                        RootGateways.mkdirs(tmpManifestDir)
                        RootGateways.writeFile("$tmpManifestDir/file_manifest.json", incrOnlyManifest.toString())
                    }

                    callback?.onProgress("[${hash}] 清单已更新: +${userDiff.added.size} ~${userDiff.modified.size} -${userDiff.deleted.size}", totalFiles, totalSize)
                }
            }

            // Update global manifest
            val globalManifest = FileManifest.toManifest(allCurrentFiles, tag)
            FileManifest.save(dir, globalManifest)

            // 3b. Package incremental changes via JNI
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                incrSources += NativeArchivePlan.Source(File(BackupEnv.backupDataDir, "${userHash}/db_state.json").absolutePath, "$userHash/db_state.json")
                incrSources += NativeArchivePlan.Source(File(BackupEnv.backupDir, "db_config.json").absolutePath, "$userHash/db_config.json")
                val incrManifestPath = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}/file_manifest.json"
                if (RootGateways.exists(incrManifestPath)) {
                    incrSources += NativeArchivePlan.Source(incrManifestPath, "$userHash/file_manifest.json")
                } else {
                    incrSources += NativeArchivePlan.Source(File(BackupEnv.backupDataDir, "${userHash}/file_manifest.json").absolutePath, "$userHash/file_manifest.json")
                }
            }
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val tmpDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}"
                val files = RootGateways.runQuiet("find \"$tmpDir\" -type f 2>/dev/null").lines().filter { it.isNotBlank() }
                for (f in files) {
                    val arcPath = f.removePrefix("${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}/")
                    incrSources += NativeArchivePlan.Source(f, "$userHash/$arcPath")
                }
            }
            if (incrSources.isNotEmpty()) {
                val incrArchive = File(dir, "incr_attachments_${tag}.tar.zst")
                val tmpPkg = incrArchive.absolutePath
                val plan = NativeArchivePlan(tmpPkg, incrSources)
                val pairsFile = File(dir, "incr_pairs.txt").absolutePath
                val localPairs = File(BackupEnv.filesDirPath, "incr_pairs.txt")
                localPairs.writeText(plan.toPairsContent())
                if (!RootGateways.copy(localPairs.absolutePath, pairsFile) || RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd()) != 0) {
                    localPairs.delete()
                    return BackupHookLocal.Result(false, "增量打包失败")
                }
                localPairs.delete()
                RootGateways.delete(pairsFile)
                val pkgSize = BackupEnv.backupSize(tmpPkg)
                if (pkgSize > 0L) {
                    totalFiles++; totalSize += pkgSize; newFiles++
                    callback?.onProgress("增量附件: ${incrArchive.name}", totalFiles, totalSize)
                }
            }

            // Save incremental SQL files
            val sqlFiles = RootGateways.runQuiet("find ${BackupEnv.backupDataDir}/tmp -name '*.sql' -path '*${tag}*' 2>/dev/null").lines().filter { it.isNotBlank() }
            for (f in sqlFiles) {
                val n = "incr_${tag}.sql"
                RootGateways.run("cp '$f' '${BackupEnv.backupDataDir}/$n' && chmod 644 '${BackupEnv.backupDataDir}/$n' 2>/dev/null")
            }

            // Cleanup tmp
            RootGateways.runQuiet("rm -rf ${BackupEnv.backupDataDir}/tmp/${tag}_* 2>/dev/null")

            // Cloud sync
            cloudSync(callback)

            // Save state
            BackupManifest.saveState(tag, totalFiles, totalSize)

            val rec = BackupManifest.createRecord(tag, "incremental", totalFiles, totalSize,
                if (newFiles > 0) "增量: ${newFiles}个文件, ${BackupManifest.formatSize(totalSize)}" else "无新文件",
                durationMs = System.currentTimeMillis() - startTime)
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
        val config = Syncer.loadConfig()
        if (!config.isValid) return
        val configFile = File(BackupEnv.backupDir, "remote_config.json")
        if (configFile.exists()) {
            val rc = try { JSONObject(BackupEnv.suOut("cat \"${configFile.absolutePath}\" 2>/dev/null").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            if (!rc.optBoolean("enabled", true)) return
        }
        val archives = if (archivePath != null && BackupEnv.backupExists(archivePath)) listOf(archivePath) else emptyList()
        val result = Syncer.sync(config, specificArchives = archives) { p ->
            callback?.onProgress(p.message, p.current.toLong(), p.total.toLong())
        }
        if (result.uploaded > 0 || result.skipped > 0) {
            callback?.onProgress(result.message, 1, 1)
        }
    }

    // ── Test remote connection ──

    fun testRemoteConnection(remote: String, configPath: String = ""): String {
        val settingsCfg = try { JSONObject(File(BackupEnv.filesDirPath, "settings_config.json").readText()) } catch (_: Exception) { JSONObject() }
        val aliyunToken = settingsCfg.optString("aliyundrive_refresh_token", "")
        val webdavUrl = settingsCfg.optString("webdav_url", "")
        if (aliyunToken.isNotBlank()) return testAliyundriveConnection(aliyunToken, settingsCfg)
        if (webdavUrl.isBlank()) {
            val webdavUser = settingsCfg.optString("webdav_user", "")
            if (webdavUser.isBlank()) return "⚠️ 未配置云存储（请先添加 WebDAV 或阿里云盘）"
        }
        return testWebdavConnection(settingsCfg, remote)
    }

    private fun testWebdavConnection(settingsCfg: JSONObject, remote: String): String {
        val webdavUrl = settingsCfg.optString("webdav_url", "")
        val webdavUser = settingsCfg.optString("webdav_user", "")
        val webdavPass = settingsCfg.optString("webdav_pass", "")
        return try {
            val client = WebDavClient(webdavUrl, webdavUser, webdavPass)
            val result = kotlinx.coroutines.runBlocking { client.testConnection() }
            if (result.isSuccess) {
                val listResult = kotlinx.coroutines.runBlocking { client.list(remote.ifBlank { "." }) }
                if (listResult.isSuccess) {
                    val dirs = listResult.getOrNull()?.take(10) ?: emptyList()
                    if (dirs.isEmpty()) "✅ 连接成功（远端无文件）" else "✅ 连接成功\n${dirs.joinToString("\n") { "📦 ${it.path}" }}"
                } else "✅ 连接成功"
            } else "连接失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
        } catch (e: Exception) { "启动失败: ${e.message}" }
    }

    private fun testAliyundriveConnection(token: String, settingsCfg: JSONObject): String {
        val apiUrl = settingsCfg.optString("aliyundrive_api_url", "https://api.oplist.org/alicloud/renewapi")
        return try {
            val configJson = com.nous.wxhook.sync.OpenListCloudClient.aliyunConfig(token, apiUrl)
            val client = com.nous.wxhook.sync.OpenListCloudClient("AliyundriveOpen", configJson)
            val result = kotlinx.coroutines.runBlocking { client.testConnection() }
            if (result.isSuccess) "✅ 阿里云盘连接成功" else "连接失败: ${result.exceptionOrNull()?.message}"
        } catch (e: Exception) { "启动失败: ${e.message}" }
    }

    // ── Rebuild DB State ──

    private fun hasStoragePermission(): Boolean = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()
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
            val wxPaths = WeChatSourceResolver.findWxPaths()
            if (wxPaths.isEmpty()) return "微信未运行，请先打开微信再重建"
            callback?.onProgress("扫描备份文件...", 0, 0)
            val fullArchives = RootGateways.runQuiet("ls ${BackupEnv.backupDataDir}/wxbackup_full_*.tar.zst 2>/dev/null").lines().filter { it.isNotBlank() }.sorted()
            val incrArchives = RootGateways.runQuiet("ls ${BackupEnv.backupDataDir}/incr_attachments_*.tar.zst 2>/dev/null").lines().filter { it.isNotBlank() }.sorted()
            callback?.onProgress("全量: ${fullArchives.size}个, 增量: ${incrArchives.size}个", 0, 0)
            data class ChainPoint(val from: Long, val to: Long, val time: Long, val name: String, val isFull: Boolean, val hash: String)
            val centralizedStates = mutableMapOf<String, JSONObject>()
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                centralizedStates[hash] = BackupManifest.loadDbState(hash)
            }
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val centralized = centralizedStates[hash] ?: JSONObject()
                callback?.onProgress("处理用户: $hash...", 0, 0)
                val points = mutableListOf<ChainPoint>()
                callback?.onProgress("[${hash}] 分析全量包...", 0, 0)
                for (arc in fullArchives) {
                    val f = File(arc)
                    val rowId = try { NativeArchive.getFullArchiveRowId(arc, hash) } catch (e: Throwable) { Log.e("wxhook:rebuild", "getFullArchiveRowId failed for ${f.name}", e); 0L }
                    if (rowId > 0) points += ChainPoint(centralized.optLong("lastMessageRowIdFrom", 0L), rowId, f.lastModified(), f.name, true, hash)
                }
                callback?.onProgress("[${hash}] 分析增量包...", 0, 0)
                for (arc in incrArchives) {
                    val f = File(arc)
                    var incrFrom = 0L; var incrTo = 0L
                    try {
                        val dbJson = NativeArchive.readFileFromTar(arc, "$hash/db_state.json")
                        incrFrom = JSONObject(dbJson).optLong("lastMessageRowIdFrom", 0)
                        incrTo = JSONObject(dbJson).optLong("lastMessageRowId", 0)
                    } catch (_: Throwable) {}
                    if (incrFrom > 0 && incrTo > 0) {
                        points += ChainPoint(incrFrom, incrTo, f.lastModified(), f.name, false, hash)
                    } else if (incrTo > 0) {
                        try {
                            val listing = NativeArchive.listTar(arc)
                            val m = Regex("incr_(\\d+)_to_(\\d+)\\.sql").find(listing)
                            incrFrom = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                            points += ChainPoint(incrFrom, incrTo, f.lastModified(), f.name, false, hash)
                        } catch (_: Throwable) {}
                    }
                }
                callback?.onProgress("[${hash}] 计算最长链...", 0, 0)
                points.sortBy { it.time }
                var chainEnd = 0L; var chainPoints = mutableListOf<ChainPoint>(); var bestChain = mutableListOf<ChainPoint>()
                for (p in points) {
                    if (p.from <= chainEnd || chainEnd == 0L) {
                        chainEnd = maxOf(chainEnd, p.to)
                        chainPoints.add(p)
                        if (chainPoints.size > bestChain.size) bestChain = mutableListOf<ChainPoint>().apply { addAll(chainPoints) }
                    } else {
                        chainEnd = p.to
                        chainPoints = mutableListOf(p)
                    }
                }
                val safeFrom = if (bestChain.isNotEmpty()) bestChain.minOf { it.from } else 0L
                val safeRowId = if (bestChain.isNotEmpty()) bestChain.maxOf { it.to } else 0L
                // Per-user db_state (only if chain has data)
                if (bestChain.isNotEmpty()) {
                    callback?.onProgress("[${hash}] 保存状态: $safeFrom→$safeRowId (链=${bestChain.size})", 0, 0)
                    if (!BackupManifest.saveDbState(hash, "rebuild", safeFrom, safeRowId)) {
                        runBlocking { (RootGateways.gateway as? RootGatewayImpl)?.ensureRootService() }
                        BackupManifest.saveDbState(hash, "rebuild", safeFrom, safeRowId)
                    }
                } else {
                    callback?.onProgress("[${hash}] ⚠️ 链为空，跳过保存", 0, 0)
                }
                // Per-user manifest: merge from all archives in chain
                callback?.onProgress("[${hash}] 提取附件清单...", 0, 0)
                val userDir = File(BackupEnv.backupDataDir, hash)
                RootGateways.mkdirs(userDir.absolutePath)
                val mergedFiles = mutableListOf<JSONObject>()
                for (cp in bestChain) {
                    val arcPath = File(BackupEnv.backupDataDir, cp.name).absolutePath
                    try {
                        val json = try { NativeArchive.readFileFromTar(arcPath, "${hash}/file_manifest.json") } catch (e: Throwable) { "" }
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
                // Save merged manifest to disk
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
                // Records
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
                results.add("$hash: rowId=$safeRowId (链=${bestChain.size})")
            }
            // 4. Save backup records (may need to reconnect Binder)
            callback?.onProgress("保存备份记录...", 0, 0)
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            var recordsOk = writeSortedRecords(sorted)
            if (!recordsOk) {
                runBlocking { (RootGateways.gateway as? RootGatewayImpl)?.ensureRootService() }
                recordsOk = writeSortedRecords(sorted)
            }
            if (!recordsOk) android.util.Log.e("wxhook:rebuild", "Failed to write backup_records.json")
            callback?.onProgress("✅ 重建完成: ${sorted.size}条记录", 0, 0)
            results.joinToString("\n") + "\nrecords=" + sorted.size
        } catch (e: Exception) {
            Log.e("wxhook:rebuild", "重建失败: ${e.message}")
            "重建失败: ${e.message}"
        }
    }

    // ── Restore from backup ──

    data class RestoreMeta(
        val userHash: String,
        val password: String,
        val fullArchive: File,
        val incrArchives: List<File>,
        val wxBasePath: String
    )

    /** Execute a shell script written via Base64 encoding (avoids special chars). */
    private fun execRootScript(script: String): Boolean {
        return try {
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
            val cmd = "echo '$b64' | base64 -d | su -c 'sh -s'"
            val result = RootGateways.run(cmd, 120_000)
            result.isSuccess
        } catch (e: Exception) {
            Log.e("wxhook:restore", "execRootScript failed", e)
            false
        }
    }

    /** Scan backup directory for full archives and return candidates sorted by time. */
    private fun scanBackupArchives(): List<File> {
        val dir = File(BackupEnv.backupDataDir)
        val files = dir.listFiles { f -> f.name.startsWith("wxbackup_full_") && f.name.endsWith(".tar.zst") }
        return files?.sortedBy { it.lastModified() } ?: emptyList()
    }

    /** Parse metadata from a full archive: return userHash and password. */
    private fun parseMetadata(archive: File): Pair<String, String>? {
        return try {
            val listing = NativeArchive.listTar(archive.absolutePath)
            // Find the first user hash directory
            val hashDirs = listing.lines().filter { it.contains('/') && it.endsWith("/") }
            val hash = hashDirs.firstOrNull()?.trimEnd('/') ?: return null

            // Try to get db_config.json from archive
            val dbConfigJson = try {
                NativeArchive.readFileFromTar(archive.absolutePath, "$hash/db_config.json")
            } catch (_: Exception) { "" }

            val password = if (dbConfigJson.isNotBlank()) {
                try { JSONObject(dbConfigJson).optString("password", "") } catch (_: Exception) { "" }
            } else ""

            if (password.isEmpty()) Pair(hash, ArchiveService.getDbPassword()) else Pair(hash, password)
        } catch (e: Exception) {
            Log.e("wxhook:restore", "parseMetadata failed", e)
            null
        }
    }

    /** Prepare environment: stop WeChat, back up current DB. */
    private fun prepareEnvironment(meta: RestoreMeta, callback: BackupHookLocal.ProgressCallback?): Boolean {
        return try {
            callback?.onProgress("⏹️ 停止微信...", 0, 0)
            // Stop WeChat
            RootGateways.run("am force-stop com.tencent.mm 2>/dev/null", 10_000)
            Thread.sleep(2000)

            // Back up current DB
            callback?.onProgress("💾 备份当前数据库...", 0, 0)
            val dbDir = File(meta.wxBasePath)
            val backupDir = File(BackupEnv.backupDataDir, "restore_before")
            RootGateways.mkdirs(backupDir.absolutePath)
            for (ext in listOf("db", "db-wal", "db-shm")) {
                val src = File(dbDir, "EnMicroMsg.$ext")
                if (!RootGateways.exists(src.absolutePath)) continue
                val dst = File(backupDir, "EnMicroMsg.$ext.restore_before")
                RootGateways.copy(src.absolutePath, dst.absolutePath)
            }
            callback?.onProgress("✅ 环境准备完成", 0, 0)
            true
        } catch (e: Exception) {
            Log.e("wxhook:restore", "prepareEnvironment failed", e)
            false
        }
    }

    /** Restore database from archive: extract SQL, rebuild encrypted DB via sqlcipher. */
    private fun restoreDatabase(meta: RestoreMeta, callback: BackupHookLocal.ProgressCallback?): Boolean {
        return try {
            callback?.onProgress("🗄️ 解压数据库...", 0, 0)
            val workDir = "/data/local/tmp/wxhook_restore"
            RootGateways.run("rm -rf \"$workDir\" && mkdir -p \"$workDir\"", 10_000)

            // Extract SQL dump from full archive
            val dumpName = "EnMicroMsg_baseline.sql"
            val sqlContent = NativeArchive.readFileFromTar(meta.fullArchive.absolutePath, "${meta.userHash}/$dumpName")
            if (sqlContent.isBlank()) {
                Log.e("wxhook:restore", "SQL dump is empty in archive")
                return false
            }
            val sqlFile = "$workDir/$dumpName"
            RootGateways.writeFile(sqlFile, sqlContent)

            // Apply incremental SQLs
            for (incrArc in meta.incrArchives) {
                val listing = NativeArchive.listTar(incrArc.absolutePath)
                for (line in listing.lines()) {
                    if (line.contains(meta.userHash) && line.contains(".sql")) {
                        val incrSql = NativeArchive.readFileFromTar(incrArc.absolutePath, line.trim())
                        if (incrSql.isNotBlank()) {
                            RootGateways.run("echo '${incrSql.replace("'", "'\\''")}' >> \"$workDir/incr.sql\"", 30_000)
                        }
                    }
                }
            }

            callback?.onProgress("🔐 重建加密数据库...", 0, 0)
            val pwd = meta.password
            val ld = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6'"
            val sqlcipher = "${BackupEnv.binDir}/sqlcipher"

            val script = """
                #!/system/bin/sh
                set -e
                WORKDIR="$workDir"
                PWD='$pwd'
                SQLCIPHER="$sqlcipher"
                LD="$ld"
                DEC_DB="\$WORKDIR/EnMicroMsg_dec.db"
                OUT_DB="\$WORKDIR/EnMicroMsg.db"

                # 1. Create empty encrypted DB
                \$LD \$SQLCIPHER "\$DEC_DB" <<-EOS
                PRAGMA key = '\$PWD';
                PRAGMA cipher_compatibility = 3;
                PRAGMA cipher_page_size = 1024;
                PRAGMA kdf_iter = 4000;
                PRAGMA cipher_use_hmac = OFF;
                CREATE TABLE IF NOT EXISTS _restore_marker (id INTEGER PRIMARY KEY);
                .quit
EOS

                # 2. Attach and import baseline SQL
                \$LD \$SQLCIPHER "\$DEC_DB" <<-EOS
                PRAGMA key = '\$PWD';
                PRAGMA cipher_compatibility = 3;
                PRAGMA cipher_page_size = 1024;
                PRAGMA kdf_iter = 4000;
                PRAGMA cipher_use_hmac = OFF;
                .import "\$WORKDIR/$dumpName" _restore_marker
                .quit
EOS

                # 3. Apply incremental SQL if exists
                if [ -f "\$WORKDIR/incr.sql" ]; then
                    \$LD \$SQLCIPHER "\$DEC_DB" <<-EOS
                    PRAGMA key = '\$PWD';
                    .read "\$WORKDIR/incr.sql"
                    .quit
EOS
                fi

                # 4. Export as encrypted DB
                \$LD \$SQLCIPHER "\$DEC_DB" <<-EOS
                PRAGMA key = '\$PWD';
                PRAGMA cipher_compatibility = 3;
                PRAGMA cipher_page_size = 1024;
                PRAGMA kdf_iter = 4000;
                PRAGMA cipher_use_hmac = OFF;
                .clone "\$OUT_DB"
                .quit
EOS

                echo "OK"
            """.trimIndent()

            val result = execRootScript(script)
            if (!result) {
                // Fallback: use simpler approach
                val cmd = "$ld $sqlcipher \"$workDir/EnMicroMsg_dec.db\" <<'ENDSCRIPT'\n" +
                    "PRAGMA key = '$pwd';\n" +
                    "PRAGMA cipher_compatibility = 3;\n" +
                    "PRAGMA cipher_page_size = 1024;\n" +
                    "PRAGMA kdf_iter = 4000;\n" +
                    "PRAGMA cipher_use_hmac = OFF;\n" +
                    ".read \"$workDir/$dumpName\"\n" +
                    ".clone \"$workDir/EnMicroMsg.db\"\n" +
                    ".quit\n" +
                    "ENDSCRIPT"
                val cmdResult = RootGateways.run(cmd, 120_000)
                if (!cmdResult.isSuccess) {
                    Log.e("wxhook:restore", "DB restore failed: ${cmdResult.stderr}")
                    return false
                }
            }

            // Verify output DB exists
            val outDb = "$workDir/EnMicroMsg.db"
            if (!RootGateways.exists(outDb) || BackupEnv.backupSize(outDb) <= 0) {
                Log.e("wxhook:restore", "Output DB not found or empty")
                return false
            }

            callback?.onProgress("✅ 数据库恢复完成", 0, 0)
            true
        } catch (e: Exception) {
            Log.e("wxhook:restore", "restoreDatabase failed", e)
            false
        }
    }

    /** Restore attachments from full and incremental archives using shell tar. */
    private fun restoreAttachments(meta: RestoreMeta, callback: BackupHookLocal.ProgressCallback?): Boolean {
        return try {
            callback?.onProgress("📎 恢复附件...", 0, 0)
            val workDir = "/data/local/tmp/wxhook_restore/attachments"
            val zstd = if (BackupEnv.useZstd()) "--zstd" else ""
            RootGateways.run("rm -rf \"$workDir\" && mkdir -p \"$workDir\"", 10_000)

            // Extract full archive via tar
            RootGateways.run("tar -I zstd -xf \"${meta.fullArchive.absolutePath}\" -C \"$workDir\" 2>/dev/null", 120_000)

            // Extract incremental archives
            for (incrArc in meta.incrArchives) {
                RootGateways.run("tar -I zstd -xf \"${incrArc.absolutePath}\" -C \"$workDir\" 2>/dev/null", 120_000)
            }

            // Copy attachment dirs to WeChat data dir
            for (attDir in ATT_DIRS) {
                val srcDir = "$workDir/${meta.userHash}/$attDir"
                val exists = RootGateways.run("test -d \"$srcDir\" && echo 1 || echo 0", 5_000)
                if (exists.stdout.trim() != "1") continue
                val dstDir = "${meta.wxBasePath}/$attDir"
                RootGateways.mkdirs(dstDir)
                RootGateways.run("cp -r \"$srcDir/.\" \"$dstDir/\" 2>/dev/null && chown -R u0_a620:u0_a620 \"$dstDir\" 2>/dev/null", 60_000)
                Log.i("wxhook:restore", "附件: $attDir -> $dstDir")
            }
            callback?.onProgress("✅ 附件恢复完成", 0, 0)
            true
        } catch (e: Exception) {
            Log.e("wxhook:restore", "restoreAttachments failed", e)
            false
        }
    }

    /** Finalize: copy DB back to WeChat dir, fix owner and permissions. */
    private fun finalizeDatabase(meta: RestoreMeta, callback: BackupHookLocal.ProgressCallback?): Boolean {
        return try {
            callback?.onProgress("📋 写入数据库...", 0, 0)
            val workDir = "/data/local/tmp/wxhook_restore"
            val srcDb = "$workDir/EnMicroMsg.db"
            val dstDb = "${meta.wxBasePath}/EnMicroMsg.db"

            // Check owner of existing files in WeChat dir
            val ownerResult = RootGateways.run("stat -c '%U:%G' \"$dstDb\" 2>/dev/null", 10_000)
            val owner = if (ownerResult.isSuccess) ownerResult.stdout.trim() else "u0_a620:u0_a620"

            // Copy the restored DB
            RootGateways.run("cp \"$srcDb\" \"$dstDb\" && chmod 660 \"$dstDb\" && chown $owner \"$dstDb\"", 30_000)

            // Copy WAL/SHM if they came from restore
            for (ext in listOf("db-wal", "db-shm")) {
                val src = "$workDir/EnMicroMsg.$ext"
                val dst = "${meta.wxBasePath}/EnMicroMsg.$ext"
                if (RootGateways.exists(src)) {
                    RootGateways.run("cp \"$src\" \"$dst\" && chmod 660 \"$dst\" && chown $owner \"$dst\"", 30_000)
                }
            }

            callback?.onProgress("✅ 数据库写入完成", 0, 0)
            true
        } catch (e: Exception) {
            Log.e("wxhook:restore", "finalizeDatabase failed", e)
            false
        }
    }

    /** Clean up temporary working directory. */
    private fun cleanupWorkDir(callback: BackupHookLocal.ProgressCallback?) {
        RootGateways.run("rm -rf /data/local/tmp/wxhook_restore 2>/dev/null", 10_000)
        callback?.onProgress("🧹 清理临时目录", 0, 0)
    }

    /** Main doRestore entry point. */
    fun doRestore(callback: BackupHookLocal.ProgressCallback? = null): BackupHookLocal.Result {
        return try {
            callback?.onProgress("🔍 扫描备份文件...", 0, 0)

            // Phase 1: Scan archives
            val fullArchives = scanBackupArchives()
            if (fullArchives.isEmpty()) return BackupHookLocal.Result(false, "未找到全量备份文件")

            val fullArc = fullArchives.last()
            callback?.onProgress("找到全量包: ${fullArc.name}", 0, 0)

            // Phase 2: Parse metadata
            val metaPair = parseMetadata(fullArc) ?: return BackupHookLocal.Result(false, "无法解析备份元数据")
            val userHash = metaPair.first
            val password = metaPair.second
            if (password.isEmpty()) return BackupHookLocal.Result(false, "无法获取数据库密码")

            // Find WeChat data path
            val wxPaths = WeChatSourceResolver.findWxPaths()
            val wxBasePath = wxPaths.firstOrNull { WeChatSourceResolver.extractUserHash(it) == userHash }
                ?: wxPaths.firstOrNull()
                ?: return BackupHookLocal.Result(false, "微信数据目录未找到")

            // Find incremental archives for this user
            val incrArchives = File(BackupEnv.backupDataDir).listFiles { f ->
                f.name.startsWith("incr_attachments_") && f.name.endsWith(".tar.zst")
            }?.sortedBy { it.lastModified() }?.filter { arc ->
                try {
                    NativeArchive.listTar(arc.absolutePath).contains(userHash)
                } catch (_: Exception) { false }
            } ?: emptyList()

            val meta = RestoreMeta(userHash, password, fullArc, incrArchives, wxBasePath)

            // Phase 3: Prepare environment
            if (!prepareEnvironment(meta, callback)) {
                cleanupWorkDir(callback)
                return BackupHookLocal.Result(false, "环境准备失败")
            }

            // Phase 4: Restore database
            if (!restoreDatabase(meta, callback)) {
                cleanupWorkDir(callback)
                return BackupHookLocal.Result(false, "数据库恢复失败")
            }

            // Phase 5: Restore attachments
            restoreAttachments(meta, callback)

            // Phase 6: Finalize (copy back, set permissions)
            if (!finalizeDatabase(meta, callback)) {
                cleanupWorkDir(callback)
                return BackupHookLocal.Result(false, "数据库写入失败")
            }

            // Phase 7: Cleanup
            cleanupWorkDir(callback)

            callback?.onProgress("✅ 恢复完成", 0, 0)
            BackupHookLocal.Result(true, "恢复成功: $userHash")
        } catch (e: Exception) {
            Log.e("wxhook:restore", "doRestore failed", e)
            cleanupWorkDir(callback)
            BackupHookLocal.Result(false, "恢复失败: ${e.message}")
        }
    }
}