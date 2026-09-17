package com.nous.wxhook.backup

import com.nous.wxhook.rootbridge.backup.BackupHookLocal

import android.util.Log
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import com.nous.wxhook.storage.WxHookPaths
import com.nous.wxhook.sync.SyncSettings
import com.nous.wxhook.sync.Syncer
import com.nous.wxhook.sync.WebDavClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates the full backup flow: stop → resolve → archive → verify → record.
 * All su commands go through RootGateways.
 */
object BackupOrchestrator {

    private const val DB_STATE_FILE = WxHookPaths.DB_STATE_FILE
    // 与恢复/对比共用同一份目录清单，避免各处列表漂移
    private val ATT_DIRS = BackupEnv.ATTACHMENT_DIRS

    // ── Progress Stage ──

    /**
     * 备份/恢复的字节级进度状态（给通知的确定态进度条用）。
     *
     * 备份流程大部分时间花在「数据库基线 dump」和「打包 tar」这两步：前者是 shell 里的
     * sqlcipher，后者是 JNI 里的 native 循环，都拿不到回调。这里统一用两个真实信号：
     * - 输出文件大小：sqlcipher 的 dump、native 的 tar.zst 都在往文件里写；
     * - 目标文件数：native 写 `<包>.progress` 文件汇报「已处理/总数」。
     * BackupService 每秒读一次，换算成百分比。
     */
    object ProgressStage {
        @Volatile var label: String = ""
        @Volatile var done: Long = 0L
        @Volatile var total: Long = 0L
        /** "B" = 字节（dump/打包产物大小），"entry" = 打包条目数 */
        @Volatile var unit: String = ""
        @Volatile var startAt: Long = 0L

        fun begin(label: String, total: Long, unit: String) {
            this.label = label
            this.total = total
            this.unit = unit
            this.done = 0L
            this.startAt = System.currentTimeMillis()
        }

        fun update(done: Long) { this.done = done }

        fun percent(): Int = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1

        fun clear() {
            label = ""
            done = 0L
            total = 0L
            unit = ""
            startAt = 0L
        }
    }

    // ── Progress helpers ──

    /**
     * 在 [block] 执行期间每秒轮询真实信号（产物大小或 native 汇报的条目数），
     * 写入 [ProgressStage] 供服务/UI 显示确定态进度条。
     */
    private fun <T> withStageProgress(
        label: String,
        total: Long,
        unit: String,
        sampler: (() -> Long)?,
        block: () -> T,
    ): T {
        ProgressStage.begin(label, total, unit)
        val stop = AtomicBoolean(false)
        val ticker = if (sampler != null) Thread {
            while (!stop.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (stop.get()) break
                runCatching { sampler() }.getOrNull()?.let { ProgressStage.update(it) }
            }
        }.apply {
            isDaemon = true
            name = "wxhook-stage"
            start()
        } else null
        return try {
            block()
        } finally {
            stop.set(true)
            ticker?.interrupt()
            sampler?.let { runCatching { it() }.getOrNull()?.let { d -> ProgressStage.update(d) } }
        }
    }

    /** native 打包汇报的 sidecar：`<包>.progress` 里是 "已处理 总数"。 */
    private fun readTarProgress(progressPath: String): Long {
        val raw = try {
            RootGateways.readFile(progressPath).trim()
        } catch (_: Exception) {
            ""
        }
        if (raw.isEmpty()) return 0L
        return raw.substringBefore(' ').trim().toLongOrNull() ?: 0L
    }

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
                // 数据库基线是最长的一步（分钟级），用 dump 产物大小 / 库大小做确定态进度
                val dumpFile = "/data/local/tmp/wxhook_backup/${FullBackupLayout.databaseDumpName()}"
                val dbSize = runCatching { RootGateways.fileSize(dbSrc) }.getOrDefault(0L)
                val dumpResult = withStageProgress("数据库基线", dbSize, "B", { RootGateways.fileSize(dumpFile) }) {
                    ArchiveService.decryptAndDump(dbSrc)
                }
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
            // 扫描阶段唯一可得的真实信号：root 进程边扫边写 /data/local/tmp/wxhook_scan/attachments.txt，
            // 用上一次清单大小的字节数作分母（两者量级接近），至少百分比是单调前进的
            val scanOut = "/data/local/tmp/wxhook_scan/attachments.txt"
            val sourceFiles = wxPaths.flatMap { wxBasePath ->
                val hash = WeChatSourceResolver.extractUserHash(wxBasePath)
                val prevBytes = runCatching {
                    BackupEnv.backupSize(File(BackupEnv.backupDataDir, "$hash/file_manifest.json").absolutePath)
                }.getOrDefault(0L)
                withStageProgress("扫描附件", prevBytes, "B", { RootGateways.fileSize(scanOut) }) {
                    FileManifest.scanWeChatAttachments(wxBasePath, hash, ATT_DIRS)
                }
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
                if (!BackupEnv.writeFileSafe(snapshotPath, userManifest.toString())) {
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
            val writeResult = withStageProgress("打包附件", sources.size.toLong(), "entry", { readTarProgress("$tmpPkg.progress") }) {
                RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd())
            }
            RootGateways.delete("$tmpPkg.progress")
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
                val prevBytes = runCatching {
                    BackupEnv.backupSize(File(userDir, "file_manifest.json").absolutePath)
                }.getOrDefault(0L)
                val currentFiles = withStageProgress(
                    "扫描附件", prevBytes, "B",
                    { RootGateways.fileSize("/data/local/tmp/wxhook_scan/attachments.txt") },
                ) {
                    FileManifest.scanWeChatAttachments(wxBasePath, userHash, ATT_DIRS)
                }
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
                // 先算好每个目录要复制的清单，才能给出"已复制/总数"这种真实百分比
                val copyPlans = ATT_DIRS.map { attDir ->
                    val currentFiles = allCurrent.filter { it.path.startsWith("$userHash/$attDir/") }
                    val toCopy = currentFiles.filter { entry ->
                        val oldEntry = FileManifest.findEntry(userOldManifest, entry.path)
                        oldEntry == null || oldEntry.size != entry.size || (!sizeOnly && oldEntry.mtime != entry.mtime)
                    }
                    attDir to toCopy
                }
                val totalToCopy = copyPlans.sumOf { it.second.size }
                ProgressStage.begin("复制附件", totalToCopy.toLong(), "file")
                var copied = 0L
                for ((attDir, toCopy) in copyPlans) {
                    val src = "$wxBasePath/$attDir"
                    try {
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
                            copied++
                            ProgressStage.update(copied)
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
                        if (BackupEnv.writeFileSafe(incrManifestPath, incrOnlyManifest.toString())) {
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
                val writeResult = if (copied) {
                    withStageProgress("打包附件", incrSources.size.toLong(), "entry", { readTarProgress("$tmpPkg.progress") }) {
                        RootGateways.writeTarZstd(tmpPkg, pairsFile, BackupEnv.useZstd())
                    }
                } else -1
                RootGateways.delete("$tmpPkg.progress")
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
        // 开关与设置页同源（SyncSettings 会兼容旧版 /sdcard 的 remote_config.json）
        if (!SyncSettings.isRemoteEnabled()) return
        // archivePath 未指定时传 null，让 Syncer 走 scanArchives() 兜底扫描全部备份包。
        // 传空列表会让 Syncer 里 `specificArchives?.filter{...} ?: scanArchives()` 的兜底失效，
        // 自动同步永远停在"无备份包可同步"（全量/增量备份后的自动云同步一直是空跑）。
        val archives = if (archivePath != null && BackupEnv.backupExists(archivePath)) listOf(archivePath) else null
        val result = Syncer.sync(config, specificArchives = archives) { p ->
            // tick 只刷进度，不进日志（大包上传时每秒一条会把日志刷满）
            if (p.tick) callback?.onTickProgress(p.message, p.current.toLong(), p.total.toLong())
            else callback?.onProgress(p.message, p.current.toLong(), p.total.toLong())
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
                        val json = try { com.nous.wxhook.backup.TarMemberReader.readText(arcPath, "${hash}/file_manifest.json") } catch (e: Throwable) { "" }
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
    //
    // 两条入口共用这一套实现（以前是两份，语义还相反）：
    // - 存档管理：长按某个存档 → 恢复 → BackupService.startRestore(ctx, tag) 传 targetTag
    // - 备份管理：「从备份恢复微信」→ targetTag = null，自动取最新全量包 + 其后的增量
    // 合并语义统一为 **union**（手机现有数据保留，存档历史补进来）——整库替换会丢手机
    // 在最近一次备份之后新收到的消息（技能里 2026-09-13 实测丢了 87 条）。

    /** 恢复源：一个全量包 + 它之后的增量包（同一 hash，按时间升序） */
    data class RestoreSources(
        val userHash: String,
        val password: String,
        val fullArchive: File,
        val incrArchives: List<File>,
        val wxBasePath: String,
    )

    /** 本地全量包（按时间升序）。 */
    private fun scanBackupArchives(): List<File> {
        val dir = File(BackupEnv.backupDataDir)
        val files = dir.listFiles { f -> f.name.startsWith("wxbackup_full_") && BackupEnv.isArchiveFile(f.name) }
        return files?.sortedBy { it.lastModified() } ?: emptyList()
    }

    /** 本地增量包（按时间升序）。 */
    private fun scanIncrArchives(): List<File> {
        val dir = File(BackupEnv.backupDataDir)
        val files = dir.listFiles { f -> f.name.startsWith("incr_attachments_") && BackupEnv.isArchiveFile(f.name) }
        return files?.sortedBy { it.lastModified() } ?: emptyList()
    }

    /** 包内用户 hash = 第一条路径的 <32位hex>/ 前缀。
     *  注意全量包不含目录条目（打包只写文件），所以不能只认以 "/" 结尾的行。 */
    private val USER_HASH_RE = Regex("(?:^|/)([0-9a-f]{32})(?=/)")

    private fun userHashFromListing(listing: String): String? =
        listing.lineSequence()
            .map { it.trim() }
            .mapNotNull { USER_HASH_RE.find(it)?.groupValues?.get(1) }
            .firstOrNull()

    private fun parseMetadata(archive: File): Pair<String, String>? {
        return try {
            val listing = NativeArchive.listTar(archive.absolutePath)
            val derived = userHashFromListing(listing)
            val hash = derived ?: BackupEnv.WX_USER_HASH
            val source = if (derived == null) "包内无 hash 前缀, 用 BackupEnv.WX_USER_HASH 兜底" else "包内推导"
            Log.i("wxhook:restore", "parseMetadata: hash=$hash ($source, ${listing.lines().size} entries)")

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

    /** 包内是否含该用户 hash（只对增量包用，包小，listTar 可接受）。 */
    private fun packageHasHash(arc: File, hash: String): Boolean = try {
        NativeArchive.listTar(arc.absolutePath).contains(hash)
    } catch (_: Exception) { false }

    /**
     * 选恢复源。
     * @param targetTag 存档管理长按的那个包（文件名里的 tag 片段）；null = 自动
     */
    private fun selectRestoreSources(targetTag: String?): RestoreSources? {
        val fulls = scanBackupArchives()
        if (fulls.isEmpty()) return null
        val incrs = scanIncrArchives()

        val target = targetTag?.takeIf { it.isNotBlank() }
            ?.let { t -> (fulls + incrs).firstOrNull { it.name.contains(t) } }

        val full: File
        val upTo: Long
        when {
            target == null -> { full = fulls.last(); upTo = Long.MAX_VALUE }
            target.name.startsWith("wxbackup_full_") -> { full = target; upTo = Long.MAX_VALUE }
            else -> {
                // 选中的是增量包：回到它所属的全量包，并只应用到它为止
                full = fulls.lastOrNull { it.lastModified() <= target.lastModified() } ?: return null
                upTo = target.lastModified()
            }
        }

        val (hash, password) = parseMetadata(full) ?: return null
        val chain = incrs.filter {
            it.lastModified() > full.lastModified() && it.lastModified() <= upTo && packageHasHash(it, hash)
        }
        val wxPaths = WeChatSourceResolver.findWxPaths()
        val wxBasePath = wxPaths.firstOrNull { WeChatSourceResolver.extractUserHash(it) == hash }
            ?: wxPaths.firstOrNull() ?: return null
        return RestoreSources(hash, password, full, chain, wxBasePath)
    }

    /** 解出恢复要用的 SQL：全量包里的 baseline dump + 各增量包的 incr sql。 */
    private fun extractRestoreSql(
        src: RestoreSources,
        callback: BackupHookLocal.ProgressCallback?,
    ): Pair<String?, List<String>>? {
        val workDir = "/data/local/tmp/wxhook_restore"
        val srcDir = "$workDir/src"
        RootGateways.run("rm -rf \"$srcDir\" && mkdir -p \"$srcDir\"", 10_000)
        val zstd = "${BackupEnv.binDir}/zstd"

        // baseline：只解这一个成员（整份 ~2GB 读进 String 会 OOM）
        val dumpName = FullBackupLayout.databaseDumpName()
        val dumpMember = "${src.userHash}/$dumpName"
        RootGateways.run(
            "cd \"$srcDir\" && tar -I '$zstd' -xf \"${src.fullArchive.absolutePath}\" \"$dumpMember\" 2>/dev/null",
            1_800_000
        )
        val dumpPath = "$srcDir/$dumpMember"
        val dumpSize = if (RootGateways.exists(dumpPath)) BackupEnv.backupSize(dumpPath) else 0L
        if (dumpSize <= 0L) {
            Log.e("wxhook:restore", "基线 SQL 解包失败: $dumpMember")
            return null
        }
        callback?.onProgress("🗄️ 基线 SQL ${dumpSize / 1048576}MB", 0, 0)

        // 增量 SQL：按包 mtime 顺序解到编号目录，再交给合并实现按序应用
        val incrRoot = "$srcDir/incr_parts"
        RootGateways.run("mkdir -p \"$incrRoot\"", 5_000)
        val incrFiles = mutableListOf<String>()
        src.incrArchives.forEachIndexed { idx, arc ->
            val members = try {
                NativeArchive.listTar(arc.absolutePath).lines().map { it.trim() }
                    .filter { it.contains(src.userHash) && it.endsWith(".sql") }
            } catch (_: Exception) { emptyList() }
            if (members.isEmpty()) return@forEachIndexed
            val dst = "$incrRoot/" + "%04d".format(idx)
            RootGateways.run("mkdir -p \"$dst\"", 5_000)
            val quoted = members.joinToString(" ") { "\"$it\"" }
            RootGateways.run("cd \"$dst\" && tar -I '$zstd' -xf \"${arc.absolutePath}\" $quoted 2>/dev/null", 600_000)
            members.sorted().forEach { m -> if (RootGateways.exists("$dst/$m")) incrFiles.add("$dst/$m") }
        }
        callback?.onProgress("🗄️ 增量 SQL ${incrFiles.size} 个", 0, 0)
        return dumpPath to incrFiles
    }

    /** 停微信 + 备份当前库（换库前的安全网）。 */
    private fun prepareEnvironment(src: RestoreSources, callback: BackupHookLocal.ProgressCallback?): Boolean {
        return try {
            callback?.onProgress("⏹️ 停止微信...", 0, 0)
            RootGateways.run("am force-stop com.tencent.mm 2>/dev/null", 10_000)
            Thread.sleep(2000)

            callback?.onProgress("💾 备份当前数据库...", 0, 0)
            val dbDir = File(src.wxBasePath)
            val backupDir = File(BackupEnv.backupDataDir, "restore_before")
            RootGateways.mkdirs(backupDir.absolutePath)
            for (ext in listOf("db", "db-wal", "db-shm")) {
                val from = File(dbDir, "EnMicroMsg.$ext")
                if (!RootGateways.exists(from.absolutePath)) continue
                RootGateways.copy(from.absolutePath, File(backupDir, "EnMicroMsg.$ext.restore_before").absolutePath)
            }
            callback?.onProgress("✅ 环境准备完成", 0, 0)
            true
        } catch (e: Exception) {
            Log.e("wxhook:restore", "prepareEnvironment failed", e)
            false
        }
    }

    /** Clean up temporary working directory. */
    private fun cleanupWorkDir(callback: BackupHookLocal.ProgressCallback?) {
        RootGateways.run("rm -rf /data/local/tmp/wxhook_restore 2>/dev/null", 30_000)
        callback?.onProgress("🧹 清理临时目录", 0, 0)
    }

    /**
     * 恢复入口（两条 UI 入口共用）。
     * @param targetTag 指定存档（存档管理长按的包）；null = 自动取最新链（备份管理）
     */
    fun doRestore(callback: BackupHookLocal.ProgressCallback? = null, targetTag: String? = null): BackupHookLocal.Result {
        return try {
            callback?.onProgress("🔍 选择存档...", 0, 0)
            val src = selectRestoreSources(targetTag)
                ?: return BackupHookLocal.Result(false, "未找到可恢复的存档")
            callback?.onProgress(
                "源: ${src.fullArchive.name} + ${src.incrArchives.size} 个增量（合并语义：手机现有消息保留）", 0, 0
            )
            Log.i("wxhook:restore", "doRestore: target=${targetTag ?: "auto"} full=${src.fullArchive.name} incr=${src.incrArchives.size} hash=${src.userHash}")

            if (!prepareEnvironment(src, callback)) return BackupHookLocal.Result(false, "环境准备失败")

            val sql = extractRestoreSql(src, callback)
                ?: run { cleanupWorkDir(callback); return BackupHookLocal.Result(false, "解包存档 SQL 失败") }

            val mergedDb = "/data/local/tmp/wxhook_restore/merged.db"
            val phoneDb = "${src.wxBasePath}/EnMicroMsg.db"
            if (!RestoreEngine.mergePhoneWithArchive(
                    sql.first, sql.second, phoneDb, mergedDb, src.password,
                ) { msg -> callback?.onProgress(msg, 0, 0) }
            ) {
                cleanupWorkDir(callback)
                return BackupHookLocal.Result(false, "数据库合并失败")
            }

            if (!RestoreEngine.replaceDb(mergedDb, src.password)) {
                cleanupWorkDir(callback)
                return BackupHookLocal.Result(false, "数据库写入失败")
            }

            // 附件：全量包 + 增量包都解到同一个目录，再交给与存档管理共用的复制实现
            callback?.onProgress("📎 恢复附件...", 0, 0)
            val attRoot = "/data/local/tmp/wxhook_restore/attachments"
            RootGateways.run("rm -rf \"$attRoot\" && mkdir -p \"$attRoot\"", 10_000)
            RootGateways.run("${BackupEnv.tarExtractCommand(src.fullArchive.absolutePath, attRoot)} 2>/dev/null", 1_800_000)
            for (arc in src.incrArchives) {
                RootGateways.run("${BackupEnv.tarExtractCommand(arc.absolutePath, attRoot)} 2>/dev/null", 600_000)
            }
            RestoreEngine.copyAttachments("$attRoot/${src.userHash}")

            RootGateways.run("rm -rf \"${src.wxBasePath}/corrupted\" 2>/dev/null", 30_000)
            cleanupWorkDir(callback)
            callback?.onProgress("✅ 恢复完成（手机现有消息保留 + 存档历史补入）", 0, 0)
            BackupHookLocal.Result(true, "恢复成功: ${src.userHash}（全量 1 + 增量 ${src.incrArchives.size}）")
        } catch (e: Exception) {
            Log.e("wxhook:restore", "doRestore failed", e)
            cleanupWorkDir(callback)
            BackupHookLocal.Result(false, "恢复失败: ${e.message}")
        }
    }
}
