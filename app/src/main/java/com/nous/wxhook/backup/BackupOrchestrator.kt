package com.nous.wxhook.backup

import com.nous.wxhook.rootbridge.backup.BackupHookLocal

import android.util.Log
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import com.nous.wxhook.storage.WxHookPaths
import com.nous.wxhook.sync.Syncer
import com.nous.wxhook.sync.WebDavClient
import kotlinx.coroutines.runBlocking
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
            val fullDbStates = mutableListOf<Triple<String, Long, Long>>()

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
                val rowIdRange = runCatching {
                    val pwd = ArchiveService.getDbPassword()
                    val decDb = "/data/local/tmp/wxhook_backup/wxhook_dec.db"
                    val exists = RootGateways.runQuiet("test -e \"$decDb\" && echo 1").trim() == "1"
                    if (!exists || pwd.isEmpty()) return@runCatching 0L to 0L
                    val sqlScript = "/data/local/tmp/wxhook_backup/rowid_query.sql"
                    RootGateways.run("mkdir -p /data/local/tmp/wxhook_backup", 5_000)
                    val scriptContent = ".output /dev/null\n" +
                        "PRAGMA key = '$pwd';\n" +
                        "PRAGMA cipher_compatibility = 3;\n" +
                        "PRAGMA cipher_page_size = 1024;\n" +
                        "PRAGMA kdf_iter = 4000;\n" +
                        "PRAGMA cipher_use_hmac = OFF;\n" +
                        ".output stdout\n" +
                        "SELECT coalesce(min(rowid), 0) FROM message;\n" +
                        "SELECT coalesce(max(rowid), 0) FROM message;\n"
                    RootGateways.runQuiet("printf '%s' '${scriptContent.replace("'", "'\\'\'")}'> $sqlScript")
                    val ld = "LD_PRELOAD='${BackupEnv.binDir}/libz.so.1:${BackupEnv.binDir}/libcrypto.so.3:${BackupEnv.binDir}/libedit.so:${BackupEnv.binDir}/libncursesw.so.6'"
                    val result = RootGateways.run("$ld ${BackupEnv.binDir}/sqlcipher \"$decDb\" < $sqlScript 2>/dev/null", 30_000)
                    RootGateways.run("rm -f $sqlScript", 5_000)
                    // 清理解密副本（可能很大，避免残留）
                    RootGateways.run("rm -f $decDb $decDb-shm $decDb-wal", 5_000)
                    // 输出顺序：min, max；取最后两个纯数字行
                    val digits = result.stdout.lines().filter { it.all { c -> c.isDigit() } }
                    val minRowId = digits.getOrNull(digits.size - 2)?.toLongOrNull() ?: 0L
                    val maxRowId = digits.lastOrNull()?.toLongOrNull() ?: 0L
                    minRowId to maxRowId
                }.getOrDefault(0L to 0L)
                // 基线包记录真实起始 rowid（之前写死 0，导致对比界面只显示最大 rowid）
                fullDbStates += Triple(userHash, rowIdRange.first, rowIdRange.second)
            }

            // 3. Scan source files for manifest
            val sourceFiles = wxPaths.flatMap { wxBasePath ->
                FileManifest.scanWeChatAttachments(wxBasePath, WeChatSourceResolver.extractUserHash(wxBasePath), ATT_DIRS)
            }
            val manifest = FileManifest.toManifest(sourceFiles, tag)
            val pendingFullUserManifests = mutableListOf<Pair<File, JSONObject>>()
            val fullManifestSnapshots = mutableMapOf<String, String>()
            // Build archive snapshots now, but commit manifests only after verification.
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, hash)
                val userManifest = FileManifest.toManifest(sourceFiles.filter { it.path.startsWith("$hash/") }, tag)
                pendingFullUserManifests += userDir to userManifest
                val snapshotPath = "${BackupEnv.backupDataDir}/tmp/${tag}_${hash}/file_manifest.json"
                RootGateways.mkdirs(File(snapshotPath).parent ?: return BackupHookLocal.Result(false, "创建清单快照目录失败"))
                if (!RootGateways.writeFile(snapshotPath, userManifest.toString())) {
                    return BackupHookLocal.Result(false, "写入清单快照失败")
                }
                fullManifestSnapshots[hash] = snapshotPath
            }
            totalFiles += sourceFiles.size

            // 4. Save config needed by the archive. Backup state is committed only
            // after the archive has been written and verified.
            BackupManifest.saveDbConfig()

            // 5. Package sources into one tar.zst
            val pkgFile = File(dir, "wxbackup_full_$tag${BackupEnv.archiveExtension()}")
            val tmpPkg = pkgFile.absolutePath
            val sources = mutableListOf<NativeArchivePlan.Source>()
            sources += databaseSources
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val (_, fromRowId, toRowId) = fullDbStates.first { it.first == hash }
                val stateSnapshotPath = "${BackupEnv.backupDataDir}/tmp/${tag}_${hash}/db_state.json"
                RootGateways.mkdirs(File(stateSnapshotPath).parent ?: return BackupHookLocal.Result(false, "创建状态快照目录失败"))
                if (!RootGateways.writeFile(stateSnapshotPath, BackupManifest.dbStateSnapshot(hash, tag, fromRowId, toRowId, incremental = false).toString())) {
                    return BackupHookLocal.Result(false, "写入数据库状态快照失败")
                }
                sources += NativeArchivePlan.Source(stateSnapshotPath, "$hash/db_state.json")
                sources += NativeArchivePlan.Source(fullManifestSnapshots[hash] ?: return BackupHookLocal.Result(false, "缺少清单快照"), "$hash/file_manifest.json")
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
            totalSize += pkgSize

            // Commit cursors and visible backup state only after a verified archive.
            // 保护：附件扫描全空但 DB 正常时，多半是扫描失败（微信目录暂不可读），
            // 不覆盖已有清单，避免把历史备份清单清空。
            if (sourceFiles.isNotEmpty()) {
                FileManifest.save(dir, manifest)
                for ((userDir, userManifest) in pendingFullUserManifests) {
                    if (userManifest.optJSONArray("files")?.length() ?: 0 > 0) {
                        FileManifest.save(userDir, userManifest)
                    }
                }
            } else {
                android.util.Log.e("wxhook:Backup", "全量备份附件扫描为空，跳过清单提交（DB 备份已生成）")
                callback?.onProgress("⚠️ 附件扫描为空，跳过清单提交", totalFiles, totalSize)
            }
            for ((userHash, fromRowId, maxRowId) in fullDbStates) {
                if (!BackupManifest.saveDbState(userHash, tag, fromRowId, maxRowId)) {
                    return BackupHookLocal.Result(false, "保存数据库备份状态失败")
                }
            }
            BackupManifest.saveState(tag, totalFiles, totalSize)
            // Cleanup tmp：清整个 tmp 目录（含失败/中断残留）
            RootGateways.runQuiet("rm -rf ${BackupEnv.backupDataDir}/tmp 2>/dev/null")
            RootGateways.run("mkdir -p ${BackupEnv.backupDataDir}/tmp", 5_000)
            BackupManifest.addRecord(
                BackupManifest.createRecord(tag, "full", totalFiles, totalSize, "全量备份完成", durationMs = System.currentTimeMillis() - startTime)
            )

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
            val pendingDbStates = mutableListOf<Triple<String, Long, Long>>()

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
                            pendingDbStates += Triple(userHash, incrFrom, incrTo)
                        } else {
                            callback?.onProgress("[${userHash}] DB增量文件无效", totalFiles, totalSize)
                        }
                    } else {
                        callback?.onProgress("[${userHash}] DB增量输出为空", totalFiles, totalSize)
                    }
                }
            }

            // 2.0 预扫描 + 基准健康检测：一次性全目录扫描，供复制/清单两阶段复用
            // （原逻辑复制阶段逐目录扫、清单阶段再全扫一次，共 9 次 find；这里合并为 1 次）
            val preScanned = mutableMapOf<String, List<FileEntry>>()
            val staleBaseline = mutableMapOf<String, Boolean>()
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, userHash)
                callback?.onProgress("[${userHash}] 扫描附件清单...", totalFiles, totalSize)
                val currentFiles = FileManifest.scanWeChatAttachments(wxBasePath, userHash, ATT_DIRS)
                preScanned[userHash] = currentFiles
                val userOldManifest = FileManifest.load(userDir)
                val oldCount = (userOldManifest.optJSONArray("files") ?: JSONArray()).length()
                val d = FileManifest.diff(userOldManifest, currentFiles)
                // modified 占比 >50%：清单基准疑似过期（微信恢复/迁移导致 mtime 全变，
                // 或 rebuild 后首次扫描）。告警 + 降级为 size-only 判断，避免重复备份。
                val stale = oldCount > 0 && d.modified.size > oldCount / 2
                staleBaseline[userHash] = stale
                if (stale) {
                    android.util.Log.w("wxhook:Backup", "[$userHash] 清单基准疑似过期：modified ${d.modified.size}/${oldCount}，降级 size-only 判断")
                    callback?.onProgress("[${userHash}] ⚠️ 检测到附件 mtime 大规模变化（疑似恢复/迁移），本次仅备份大小变化的文件", totalFiles, totalSize)
                }
            }

            // 2. Attachments incremental via per-user manifest diff
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, userHash)
                val userOldManifest = FileManifest.load(userDir)
                val sizeOnly = staleBaseline[userHash] == true
                val allCurrent = preScanned[userHash] ?: emptyList()
                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    try {
                        val currentFiles = allCurrent.filter { it.path.startsWith("$userHash/$attDir/") }
                        val toCopy = currentFiles.filter { entry ->
                            val oldEntry = FileManifest.findEntry(userOldManifest, entry.path)
                            oldEntry == null || oldEntry.size != entry.size || (!sizeOnly && oldEntry.mtime != entry.mtime)
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
                                // 直接记录到内存 sources（不再依赖打包时 find tmpDir——
                                // find 输出大时超过 Binder 事务限制会静默丢附件）
                                incrSources += NativeArchivePlan.Source(dstFile.absolutePath, "$userHash/$rel")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("wxhook:Backup", "Incr $userHash/$attDir failed: $e")
                    }
                }
            }

            // 3. Build updated manifests, but do not commit them until the archive
            // is verified; otherwise a failed archive would make files look backed up.
            val allCurrentFiles = mutableListOf<FileEntry>()
            val pendingUserManifests = mutableListOf<Pair<File, JSONObject>>()
            // 增量 file_manifest 路径（userHash -> tmp 路径），打包时用内存记录，避免 exists 检查受 FUSE 缓存影响
            val incrManifestPaths = mutableMapOf<String, String>()
            for (wxBasePath in wxPaths) {
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val userDir = File(BackupEnv.backupDataDir, hash)
                RootGateways.mkdirs(userDir.absolutePath)

                val userCurrentFiles = preScanned[hash] ?: FileManifest.scanWeChatAttachments(wxBasePath, hash, ATT_DIRS)
                allCurrentFiles.addAll(userCurrentFiles)

                val userOldManifest = FileManifest.load(userDir)
                val oldCount = (userOldManifest.optJSONArray("files") ?: JSONArray()).length()
                val sizeOnly = staleBaseline[hash] == true
                val userDiff = FileManifest.diff(userOldManifest, userCurrentFiles, sizeOnly)
                // 保护：扫描结果为空且旧清单非空（全部判删、无新增/修改），几乎可以断定附件
                // 扫描失败（目录可读但 find 无输出）。此时提交空清单会把历史备份清单清空，
                // 必须跳过并告警，等下次扫描正常再更新。
                if (userCurrentFiles.isEmpty() && oldCount > 0 &&
                    userDiff.added.isEmpty() && userDiff.modified.isEmpty() && userDiff.deleted.size >= oldCount
                ) {
                    android.util.Log.e("wxhook:Backup", "[${hash}] 附件扫描为空但旧清单有 $oldCount 条，疑似扫描失败，跳过清空清单")
                    callback?.onProgress("[${hash}] ⚠️ 附件扫描为空（旧清单 $oldCount 条），跳过清单更新", totalFiles, totalSize)
                    continue
                }
                if (userDiff.added.isNotEmpty() || userDiff.modified.isNotEmpty() || userDiff.deleted.isNotEmpty()) {
                    val userUpdatedManifest = FileManifest.toManifest(userCurrentFiles, tag)
                    userUpdatedManifest.put("incrFrom", incrFrom)
                    userUpdatedManifest.put("incrTo", incrTo)
                    pendingUserManifests += userDir to userUpdatedManifest

                    val incrFiles = userDiff.added + userDiff.modified
                    if (incrFiles.isNotEmpty()) {
                        val incrOnlyManifest = FileManifest.toManifest(incrFiles, tag)
                        incrOnlyManifest.put("incrFrom", incrFrom)
                        incrOnlyManifest.put("incrTo", incrTo)
                        val tmpManifestDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${hash}"
                        RootGateways.mkdirs(tmpManifestDir)
                        val incrManifestPath = "$tmpManifestDir/file_manifest.json"
                        if (RootGateways.writeFile(incrManifestPath, incrOnlyManifest.toString())) {
                            incrManifestPaths[hash] = incrManifestPath
                        } else {
                            android.util.Log.e("wxhook:Backup", "[${hash}] 写入增量清单失败，打包时回退 userDir 全量清单")
                        }
                    }

                    callback?.onProgress("[${hash}] 清单已更新: +${userDiff.added.size} ~${userDiff.modified.size} -${userDiff.deleted.size}", totalFiles, totalSize)
                    // 防复发：modified 占比异常高（>50%）说明清单基准可能过期（如 rebuild 后
                    // 首次扫描、或之前扫描失败保留了旧 mtime），本次增量会偏大但清单会被校准。
                    if (oldCount > 0 && userDiff.modified.size > oldCount / 2) {
                        android.util.Log.w("wxhook:Backup", "[${hash}] 清单基准疑似过期：modified ${userDiff.modified.size}/${oldCount}，本次增量偏大（一次性）")
                    }
                }
            }

            val globalManifest = FileManifest.toManifest(allCurrentFiles, tag)
            // 保护：全目录扫描全空时，不覆盖已有的全局清单（可能是附件扫描失败，
            // 而不是附件真的被清空）。扫描正常（至少一个有文件）时才写。
            if (allCurrentFiles.isEmpty()) {
                val oldGlobal = File(BackupEnv.backupDataDir, "file_manifest.json")
                val oldGlobalCount = try {
                    JSONObject(RootGateways.runQuiet("cat '${oldGlobal.absolutePath}' 2>/dev/null")).optJSONArray("files")?.length() ?: 0
                } catch (_: Exception) { 0 }
                if (oldGlobalCount > 0) {
                    android.util.Log.e("wxhook:Backup", "全目录扫描为空但全局清单有 $oldGlobalCount 条，跳过写空全局清单")
                } else {
                    FileManifest.save(dir, globalManifest)
                }
            } else {
                FileManifest.save(dir, globalManifest)
            }

            // 3b. Package incremental changes via JNI
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val pendingState = pendingDbStates.firstOrNull { it.first == userHash }
                val stateSource = if (pendingState != null) {
                    val (_, fromRowId, toRowId) = pendingState
                    val path = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}/db_state.json"
                    RootGateways.mkdirs(File(path).parent ?: return BackupHookLocal.Result(false, "创建状态快照目录失败"))
                    if (!RootGateways.writeFile(path, BackupManifest.dbStateSnapshot(userHash, tag, fromRowId, toRowId, incremental = true).toString())) {
                        return BackupHookLocal.Result(false, "写入数据库状态快照失败")
                    }
                    path
                } else {
                    File(BackupEnv.backupDataDir, "${userHash}/db_state.json").absolutePath
                }
                incrSources += NativeArchivePlan.Source(stateSource, "$userHash/db_state.json")
                incrSources += NativeArchivePlan.Source(File(BackupEnv.backupDir, "db_config.json").absolutePath, "$userHash/db_config.json")
                // 有增量文件时用增量清单，否则回退 userDir 全量清单
                val incrManifestPath = incrManifestPaths[userHash]
                    ?: File(BackupEnv.backupDataDir, "${userHash}/file_manifest.json").absolutePath
                incrSources += NativeArchivePlan.Source(incrManifestPath, "$userHash/file_manifest.json")
            }
            // 附件已在上面的复制循环里直接加入 incrSources（内存），不再 find tmpDir——
            // find 输出大时超过 Binder 事务限制会静默丢失全部附件（历史 bug 根因）。
            // 这里只统计 tmp 残留用于诊断日志。
            for (wxBasePath in wxPaths) {
                val userHash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val tmpDir = "${BackupEnv.backupDataDir}/tmp/${tag}_${userHash}"
                val leftover = RootGateways.runQuiet("find \"$tmpDir\" -type f 2>/dev/null | wc -l").trim()
                android.util.Log.i("wxhook:Backup", "tmp残留: $tmpDir -> $leftover 文件")
            }
            if (incrSources.isNotEmpty()) {
                val incrArchive = File(dir, "incr_attachments_${tag}${BackupEnv.archiveExtension()}")
                val tmpPkg = incrArchive.absolutePath
                val plan = NativeArchivePlan(tmpPkg, incrSources)
                val pairsFile = File(dir, "incr_pairs.txt").absolutePath
                val localPairs = File(BackupEnv.filesDirPath, "incr_pairs.txt")
                localPairs.writeText(plan.toPairsContent())
                val copied = RootGateways.copy(localPairs.absolutePath, pairsFile)
                localPairs.delete()
                val writeResult = if (copied) RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd()) else -1
                val verifyResult = if (writeResult == 0) RootGateways.verifyTarZstd(tmpPkg) else -1
                RootGateways.delete(pairsFile)
                val pkgSize = BackupEnv.backupSize(tmpPkg)
                if (writeResult != 0 || verifyResult <= 0 || pkgSize <= 0L) {
                    RootGateways.delete(tmpPkg)
                    return BackupHookLocal.Result(false, "增量打包失败: native=$writeResult verify=$verifyResult")
                }
                if (pkgSize > 0L) {
                    totalFiles++; totalSize += pkgSize; newFiles++
                    callback?.onProgress("增量附件: ${incrArchive.name}", totalFiles, totalSize)
                }
            }

            // Commit manifests and DB cursors only after the incremental archive is verified.
            for ((userDir, manifest) in pendingUserManifests) {
                FileManifest.save(userDir, manifest)
            }
            FileManifest.save(dir, globalManifest)
            for ((userHash, fromRowId, toRowId) in pendingDbStates) {
                BackupManifest.updateDbState(userHash, tag, fromRowId, toRowId)
            }

            // 增量 SQL 已打进 incr_attachments_*.tar.zst 包内（incr_<from>_to_<to>.sql），
            // 不再单独复制到 backupdata/ 根目录（冗余且占空间，之前每次备份残留 18-22MB）

            // Cleanup tmp：清整个 tmp 目录（含历史上失败备份的残留，如打包失败/中断留下的附件副本）
            RootGateways.runQuiet("rm -rf ${BackupEnv.backupDataDir}/tmp 2>/dev/null")
            RootGateways.run("mkdir -p ${BackupEnv.backupDataDir}/tmp", 5_000)

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
        // 所有用户去重后的附件并集，最后写入根目录全局清单（与备份时 globalManifest 语义一致）
        val globalMergedFiles = mutableListOf<JSONObject>()
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
            val fullArchives = RootGateways.runQuiet("find ${BackupEnv.backupDataDir} -maxdepth 1 -type f \\( -name 'wxbackup_full_*.tar.zst' -o -name 'wxbackup_full_*.tar.gz' \\) 2>/dev/null").lines().filter { it.isNotBlank() }.sorted()
            val incrArchives = RootGateways.runQuiet("find ${BackupEnv.backupDataDir} -maxdepth 1 -type f \\( -name 'incr_attachments_*.tar.zst' -o -name 'incr_attachments_*.tar.gz' \\) 2>/dev/null").lines().filter { it.isNotBlank() }.sorted()
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
                // 按 path 去重：同一文件可能同时出现在基线（modified 前）和增量包（modified 后）清单里，
                // 保留 mtime 较新的条目（增量包里的条目是修改后的最新状态）。
                val mergedByPath = mutableMapOf<String, JSONObject>()
                for (cp in bestChain) {
                    val arcPath = File(BackupEnv.backupDataDir, cp.name).absolutePath
                    try {
                        val json = try { NativeArchive.readFileFromTar(arcPath, "${hash}/file_manifest.json") } catch (e: Throwable) { "" }
                        if (json.isNotBlank()) {
                            val manifest = JSONObject(json)
                            val files = manifest.optJSONArray("files") ?: manifest.optJSONArray("entries")
                            if (files != null) {
                                var added = 0
                                for (i in 0 until files.length()) {
                                    val entry = files.getJSONObject(i)
                                    val path = entry.optString("path", "")
                                    if (path.isEmpty()) continue
                                    val prev = mergedByPath[path]
                                    if (prev == null || entry.optLong("mtime", 0) >= prev.optLong("mtime", 0)) {
                                        mergedByPath[path] = entry
                                        added++
                                    }
                                }
                                Log.i("wxhook:rebuild", "manifest from ${cp.name}: +${files.length()} files (去重后净增 $added)")
                            }
                        } else {
                            Log.e("wxhook:rebuild", "manifest shell pipe empty for ${cp.name}")
                        }
                    } catch (e: Throwable) {
                        Log.e("wxhook:rebuild", "manifest extract failed for ${cp.name}", e)
                    }
                }
                // Save merged manifest to disk
                if (mergedByPath.isNotEmpty()) {
                    val mergedManifest = JSONObject().apply {
                        put("version", 1)
                        put("tag", "rebuild_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}")
                        put("fileCount", mergedByPath.size)
                        put("files", JSONArray(mergedByPath.values.toList()))
                    }
                    FileManifest.save(userDir, mergedManifest)
                    globalMergedFiles.addAll(mergedByPath.values)
                    Log.i("wxhook:rebuild", "merged manifest saved: ${mergedByPath.size} files to $userDir")
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
            // 重建根目录全局清单：所有用户去重后的附件并集（与备份时 globalManifest 语义一致）
            if (globalMergedFiles.isNotEmpty()) {
                val globalManifest = JSONObject().apply {
                    put("version", 1)
                    put("tag", "rebuild_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}")
                    put("fileCount", globalMergedFiles.size)
                    put("files", JSONArray(globalMergedFiles))
                }
                FileManifest.save(File(BackupEnv.backupDataDir), globalManifest)
                Log.i("wxhook:rebuild", "global manifest saved: ${globalMergedFiles.size} files")
            }
            val sorted = (0 until rebuiltRecords.length())
                .map { rebuiltRecords.getJSONObject(it) }
                .sortedBy { it.optLong("time", 0L) }
            var recordsOk = BackupManifest.writeSortedRecords(sorted)
            if (!recordsOk) {
                runBlocking { (RootGateways.gateway as? RootGatewayImpl)?.ensureRootService() }
                recordsOk = BackupManifest.writeSortedRecords(sorted)
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

    /** Scan backup directory for full archives and return candidates sorted by time. */
    private fun scanBackupArchives(): List<File> {
        val dir = File(BackupEnv.backupDataDir)
        val files = dir.listFiles { f -> f.name.startsWith("wxbackup_full_") && BackupEnv.isArchiveFile(f.name) }
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
            val binDir = BackupEnv.binDir
            val decDb = "$workDir/EnMicroMsg_dec.db"
            val outDb = "$workDir/EnMicroMsg.db"

            // Write restore script to a file using writeFile (no shell escaping issues)
            val restoreScript = buildString {
                appendLine("#!/system/bin/sh")
                appendLine("set -e")
                appendLine("LD_PRELOAD='${binDir}/libz.so.1:${binDir}/libcrypto.so.3:${binDir}/libedit.so:${binDir}/libncursesw.so.6'")
                appendLine("SQLCIPHER=\"${binDir}/sqlcipher\"")
                appendLine("DEC_DB=\"$decDb\"")
                appendLine("OUT_DB=\"$outDb\"")
                appendLine("PWD='$pwd'")
                appendLine("DUMP=\"$workDir/$dumpName\"")
                appendLine("INCR=\"$workDir/incr.sql\"")
                appendLine("touch \"\$INCR\"")
                appendLine("")
                // Use heredoc inside the shell script to pipe SQL
                appendLine("\$SQLCIPHER \"\$DEC_DB\" <<'ENDSQL'")
                appendLine("PRAGMA key = '\$PWD';")
                appendLine("PRAGMA cipher_compatibility = 3;")
                appendLine("PRAGMA cipher_page_size = 1024;")
                appendLine("PRAGMA kdf_iter = 4000;")
                appendLine("PRAGMA cipher_use_hmac = OFF;")
                appendLine(".read \"\$DUMP\"")
                appendLine("")
                appendLine("-- Apply incremental if exists")
                appendLine(".read \"\$INCR\"")
                appendLine("")
                appendLine(".clone \"\$OUT_DB\"")
                appendLine(".quit")
                appendLine("ENDSQL")
                appendLine("echo \"OK\"")
            }

            val scriptPath = "$workDir/restore.sh"
            RootGateways.writeFile(scriptPath, restoreScript)
            RootGateways.run("chmod 755 \"$scriptPath\"", 5_000)

            // Execute the script via su
            val cmd = "$scriptPath 2>&1"
            val result = RootGateways.run(cmd, 120_000)

            if (!result.isSuccess) {
                Log.e("wxhook:restore", "DB restore script failed: ${result.stderr}")
                return false
            }

            // Verify output DB exists
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
            RootGateways.run("rm -rf \"$workDir\" && mkdir -p \"$workDir\"", 10_000)

            // Extract full archive via tar
            RootGateways.run("${BackupEnv.tarExtractCommand(meta.fullArchive.absolutePath, workDir)} 2>/dev/null", 120_000)

            // Extract incremental archives
            for (incrArc in meta.incrArchives) {
                RootGateways.run("${BackupEnv.tarExtractCommand(incrArc.absolutePath, workDir)} 2>/dev/null", 120_000)
            }

            // Copy attachment dirs to WeChat data dir
            // 动态获取微信进程 UID
            val ownerResult = RootGateways.run("stat -c '%U:%G' \"${meta.wxBasePath}/EnMicroMsg.db\" 2>/dev/null", 5_000)
            val owner = if (ownerResult.isSuccess && ownerResult.stdout.isNotBlank()) ownerResult.stdout.trim() else "u0_a620:u0_a620"
            for (attDir in ATT_DIRS) {
                val srcDir = "$workDir/${meta.userHash}/$attDir"
                val exists = RootGateways.run("test -d \"$srcDir\" && echo 1 || echo 0", 5_000)
                if (exists.stdout.trim() != "1") continue
                val dstDir = "${meta.wxBasePath}/$attDir"
                RootGateways.mkdirs(dstDir)
                RootGateways.run("cp -r \"$srcDir/.\" \"$dstDir/\" 2>/dev/null && chown -R $owner \"$dstDir\" 2>/dev/null", 60_000)
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
                f.name.startsWith("incr_attachments_") && BackupEnv.isArchiveFile(f.name)
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