package com.nous.wxhook.ui.archive

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.nous.wxhook.backup.ArchiveManager
import com.nous.wxhook.backup.ArchiveManager.ArchiveInfo
import com.nous.wxhook.backup.RestoreEngine
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.service.CloudDownloadService
import com.nous.wxhook.sync.Syncer
import com.nous.wxhook.sync.ArchiveDownloadPlanner
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.runBlocking
import java.io.File

class ArchiveActivity : AppCompatActivity() {

    private val TAG = "wxhook:ArchiveUI"
    private val downloadFinishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == CloudDownloadService.ACTION_FINISH) {
                val ok = intent.getIntExtra(CloudDownloadService.EXTRA_OK, 0)
                val fail = intent.getIntExtra(CloudDownloadService.EXTRA_FAIL, 0)
                android.widget.Toast.makeText(
                    this@ArchiveActivity,
                    "下载完成: $ok 成功, $fail 失败",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                refreshList(rootLayout ?: return)
            }
        }
    }
    private var rootLayout: LinearLayout? = null
    /** 多选高亮的存档 tag 集合（内存态，单击切换，长按出详情操作）。 */
    private val selectedTags = LinkedHashSet<String>()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(M3.colorSurface(this@ArchiveActivity)); setStroke(1, M3.colorOutline(this@ArchiveActivity)) }
        elevation = dp(2).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ 通知需要运行时权限（前台服务进度依赖通知栏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001) } catch (_: Exception) {}
            }
        }
        supportActionBar?.title = "存档管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // Selected archive status
        val statusCard = cardBg()
        statusCard.tag = "status"
        statusCard.addView(TextView(this).apply { text = "🗂️ 存档管理"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        statusCard.addView(selectedStatusLine())
        statusCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        statusCard.addView(MaterialButton(this).apply {
            text = "🔄 刷新存档列表"
            tag = "refreshBtn"
            insetTop = 0; insetBottom = 0; setOnClickListener { refreshList(root) }
        })
        root.addView(statusCard)

        // Action buttons
        val actionCard = cardBg()
        actionCard.addView(TextView(this).apply { text = "📋 操作提示"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        actionCard.addView(TextView(this).apply {
            text = "点击存档可选中/取消（支持多选，高亮显示）；长按存档查看详情并进行对比、恢复、删除等操作。"
            textSize = 14f; setPadding(0, dp(8), 0, dp(12)); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
        })
        root.addView(actionCard)

        // Archive list (populated by refreshList)
        val listCard = cardBg()
        listCard.tag = "archives"
        listCard.id = View.generateViewId()
        listCard.addView(TextView(this).apply {
            text = "📦 本地 + ☁️ 云端存档"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })
        listCard.addView(TextView(this).apply {
            text = "点击「刷新」加载存档列表"
            textSize = 14f; setPadding(0, dp(8), 0, 0); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
        })
        root.addView(listCard)

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })
        sv.addView(root)
        setContentView(sv)
        rootLayout = root
        // 监听下载服务完成广播，自动刷新列表
        android.content.IntentFilter().also { filter ->
            filter.addAction(CloudDownloadService.ACTION_FINISH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadFinishReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(downloadFinishReceiver, filter)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadFinishReceiver) } catch (_: Exception) {}
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** 顶部状态卡的选中信息行（随刷新同步更新） */
    private fun selectedStatusLine(): View {
        return if (selectedTags.isNotEmpty()) {
            TextView(this).apply {
                text = "已选 ${selectedTags.size} 个存档（点击切换，长按查看详情操作）"
                textSize = 14f; setPadding(0, dp(4), 0, 0); typeface = Typeface.DEFAULT_BOLD
            }
        } else {
            TextView(this).apply {
                text = "未选中存档（点击选中，长按查看详情）"
                textSize = 14f; setPadding(0, dp(4), 0, 0); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
            }
        }
    }

    private fun refreshStatusCard(root: LinearLayout) {
        val oldCard = root.findViewWithTag<View>("status") ?: return
        val idx = root.indexOfChild(oldCard)
        root.removeView(oldCard)
        val card = cardBg().apply { tag = "status" }
        card.addView(TextView(this).apply { text = "🗂️ 存档管理"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        card.addView(selectedStatusLine())
        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        card.addView(MaterialButton(this).apply {
            text = "🔄 刷新存档列表"
            tag = "refreshBtn"
            insetTop = 0; insetBottom = 0; setOnClickListener { refreshList(root) }
        })
        root.addView(card, idx)
    }

    private fun refreshList(root: LinearLayout) {
        // 刷新按钮转圈提示，防止重复点击
        val refreshBtn = root.findViewWithTag<View>("refreshBtn")
        refreshBtn?.isEnabled = false
        refreshBtn?.alpha = 0.5f
        android.widget.Toast.makeText(this, "正在刷新存档列表...", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val localArchives: List<ArchiveInfo>
            val cloudArchives: List<ArchiveInfo>
            val phoneMsgCount: Long
            val phoneAttTotal: Int
            val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                // 本地扫描 / 云端列表 / 手机统计 三路并行，任一失败回退默认值，不阻塞整体
                val localFuture = executor.submit(java.util.concurrent.Callable {
                    runCatching { ArchiveManager.scanLocalArchives() }.getOrDefault(emptyList())
                })
                val cloudFuture = executor.submit(java.util.concurrent.Callable {
                    runCatching { fetchCloudArchives() }.getOrDefault(emptyList())
                })
                val phoneFuture = executor.submit(java.util.concurrent.Callable {
                    runCatching { ArchiveManager.getPhoneStats() }.getOrNull()
                })
                localArchives = try { localFuture.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
                cloudArchives = try { cloudFuture.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
                val phone = try { phoneFuture.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
                phoneMsgCount = phone?.msgCount ?: 0L
                phoneAttTotal = phone?.totalAttachments ?: 0
            } finally {
                executor.shutdownNow()
            }

            val allArchives = mutableListOf<ArchiveInfo>()
            // Current phone data shown first
            allArchives.add(ArchiveInfo(
                tag = "📱 当前手机数据",
                backupTime = System.currentTimeMillis(),
                backupTimeStr = "实时",
                messageCount = phoneMsgCount,
                totalAttachmentFiles = phoneAttTotal,
                source = "phone",
            ))
            allArchives.addAll((localArchives + cloudArchives).sortedByDescending { it.backupTime })

            runOnUiThread {
                val oldCard = root.findViewWithTag<View>("archives") ?: return@runOnUiThread
                val idx = root.indexOfChild(oldCard)
                root.removeView(oldCard)

                val localCount = localArchives.size
                val cloudCount = cloudArchives.size
                val card = cardBg().apply { tag = "archives" }
                card.addView(TextView(this).apply {
                    text = "存档列表 (本地 $localCount · 云端 $cloudCount)"
                    textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                })

                if (allArchives.isEmpty()) {
                    val hint = if (cloudArchives.isEmpty() && !Syncer.loadConfig().isValid)
                        "暂无存档\\n请先通过备份功能创建备份\\n或在「设置→云存储驱动」配置云端"
                    else "暂无存档"
                    card.addView(TextView(this).apply {
                        text = hint; textSize = 14f; setPadding(0, dp(8), 0, 0)
                        setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
                    })
                } else {
                    for (a in allArchives) {
                        card.addView(archiveRow(this, a, root, phoneMsgCount, phoneAttTotal))
                    }

                    // Download all cloud archives button
                    if (cloudArchives.isNotEmpty()) {
                        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
                        card.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text = "☁️⬇ 下载所有云端存档到本地"
                            insetTop = 0; insetBottom = 0
                            setOnClickListener {
                                android.app.AlertDialog.Builder(this@ArchiveActivity)
                                    .setTitle("下载云端存档")
                                    .setMessage("将 ${cloudArchives.size} 个云端文件下载到本地备份目录？")
                                    .setPositiveButton("下载") { _, _ -> downloadAllCloud(cloudArchives, root) }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        })
                    }
                }
                root.addView(card, idx)
                refreshStatusCard(root)
            }
        }.start()
    }

    private fun fetchCloudArchives(): List<ArchiveInfo> {
        val config = Syncer.loadConfig()
        if (!config.isValid) return emptyList()
        val client = Syncer.createClient(config) ?: return emptyList()
        return try {
            val result = runBlocking { client.list(config.remotePath) }
            if (result.isFailure) return emptyList()
            result.getOrNull()?.filter { f ->
                f.path.endsWith(".tar.zst") || f.path.endsWith(".tar.gz")
            }?.map { f ->
                ArchiveInfo(
                    tag = File(f.path).name.removeSuffix(".tar.zst").removeSuffix(".tar.gz"),
                    backupTime = f.modTime,
                    backupTimeStr = ArchiveManager.formatTime(f.modTime),
                    totalAttachmentSize = f.size,
                    path = f.path,
                    source = "cloud",
                )
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** 单行存档：单击切换高亮多选（不做处理），长按显示详情对话框。 */
    private fun archiveRow(
        ctx: android.content.Context,
        a: ArchiveInfo,
        root: LinearLayout,
        phoneMsgCount: Long,
        phoneAttTotal: Int,
    ): View {
        val isPhone = a.source == "phone"
        val srcSuffix = when (a.source) {
            "cloud" -> " ☁️云端"
            "phone" -> " 📱"
            else -> " 📦本地"
        }
        val srcColor = when (a.source) {
            "cloud" -> M3.colorPrimary(ctx)
            "phone" -> M3.colorSecondary(ctx)
            else -> M3.onSurface(ctx)
        }
        val hlBg = M3.colorPrimaryContainer(ctx)
        val hlFg = M3.onPrimaryContainer(ctx)

        val nameLine = TextView(ctx).apply {
            text = "${a.tag}$srcSuffix"
            textSize = 14f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            if (!isPhone && selectedTags.contains(a.tag)) {
                setBackgroundColor(hlBg)
                setTextColor(hlFg)
                typeface = Typeface.DEFAULT_BOLD
            } else {
                setTextColor(srcColor)
                typeface = if (isPhone) Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) else Typeface.DEFAULT
            }
        }
        if (!isPhone) {
            // 单击：仅切换高亮选中，不做任何处理
            nameLine.setOnClickListener {
                val sel = !selectedTags.remove(a.tag)
                if (sel) selectedTags.add(a.tag)
                nameLine.setBackgroundColor(if (sel) hlBg else android.graphics.Color.TRANSPARENT)
                nameLine.setTextColor(if (sel) hlFg else srcColor)
                nameLine.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                refreshStatusCard(root)
            }
            // 长按：显示详情，操作（对比/恢复/删除/下载）都在详情里
            nameLine.setOnLongClickListener {
                showArchiveDetail(a, root)
                true
            }
        }

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)) })
        col.addView(nameLine)
        col.addView(TextView(ctx).apply {
            val detail = when (a.source) {
                "phone" -> "  ${phoneMsgCount}条消息 · ${phoneAttTotal}个附件"
                "cloud" -> "  ${ArchiveManager.formatSize(a.totalAttachmentSize)}"
                else -> "  ${a.backupTimeStr} · rowid ${a.messageRowId} · ${a.totalAttachmentFiles}个附件 · ${ArchiveManager.formatSize(a.totalAttachmentSize)}"
            }
            text = "  $detail"
            textSize = 12f; setTextColor(M3.onSurfaceVariant(ctx))
        })
        return col
    }

    /** 长按详情对话框：展示存档信息，操作按钮都在这里触发。 */
    private fun showArchiveDetail(a: ArchiveInfo, root: LinearLayout) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val infoText = TextView(this).apply { textSize = 14f }
        content.addView(infoText)
        val progressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE; setPadding(0, dp(12), 0, 0) }
        val progressBar = android.widget.ProgressBar(this)
        val progressLabel = TextView(this).apply { text = "正在准备存档数据..."; textSize = 13f; setTextColor(M3.onSurfaceVariant(this@ArchiveActivity)); setPadding(dp(12), 0, 0, 0) }
        progressRow.addView(progressBar)
        progressRow.addView(progressLabel)
        content.addView(progressRow)
        val btnCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(btnCol)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("📋 ${a.tag}")
            .setView(content)
            .setNegativeButton("关闭", null)
            .create()

        // 本地包：用 JNI 读包内 JSON 填充完整信息（不 shell 解压）
        val needsPrep = a.source == "local" &&
            (a.path.endsWith(".tar.zst") || a.path.endsWith(".tar.gz")) &&
            (a.messageRowId <= 0 || a.totalAttachmentFiles <= 0)

        fun renderButtons(archive: ArchiveInfo) {
            btnCol.removeAllViews()
            if (archive.source == "cloud") {
                btnCol.addView(MaterialButton(this).apply {
                    text = "⬇️ 下载并选中"; insetTop = 0; insetBottom = 0
                    setOnClickListener { dialog.dismiss(); downloadAndSelect(archive, root) }
                })
            } else {
                btnCol.addView(MaterialButton(this).apply {
                    text = "📊 对比差异"; insetTop = 0; insetBottom = 0
                    setOnClickListener { dialog.dismiss(); showDiff(archive) }
                })
                btnCol.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
                btnCol.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "▶️ 恢复"; insetTop = 0; insetBottom = 0
                    setOnClickListener { dialog.dismiss(); startRestore(archive) }
                })
                btnCol.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
                btnCol.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "🗑️ 删除本地存档"; insetTop = 0; insetBottom = 0
                    setTextColor(M3.colorError(this@ArchiveActivity))
                    setOnClickListener {
                        dialog.dismiss()
                        android.app.AlertDialog.Builder(this@ArchiveActivity)
                            .setTitle("删除本地存档")
                            .setMessage("删除 ${archive.tag}？此操作不可撤销。")
                            .setPositiveButton("删除") { _, _ ->
                                Thread {
                                    val ok = ArchiveManager.deleteLocalArchive(archive)
                                    runOnUiThread {
                                        android.widget.Toast.makeText(this@ArchiveActivity, if (ok) "已删除" else "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                                        if (ok) selectedTags.remove(archive.tag)
                                        refreshList(root)
                                    }
                                }.start()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                })
            }
        }

        fun fillInfo(archive: ArchiveInfo) {
            infoText.text = when (archive.source) {
                "cloud" -> "云端存档 ☁️\n时间: ${archive.backupTimeStr}\n大小: ${ArchiveManager.formatSize(archive.totalAttachmentSize)}"
                else -> "本地存档 📦\n时间: ${archive.backupTimeStr}\nrowid: ${archive.messageRowIdFrom} ~ ${archive.messageRowId}\n消息数: ${archive.messageCount}\n附件数: ${archive.totalAttachmentFiles}\n大小: ${ArchiveManager.formatSize(archive.totalAttachmentSize)}"
            }
        }

        fillInfo(a)
        if (needsPrep) {
            progressRow.visibility = android.view.View.VISIBLE
            dialog.show()
            Thread {
                val full = ArchiveManager.refreshPackageMeta(a)
                runOnUiThread {
                    progressRow.visibility = android.view.View.GONE
                    val ready = full ?: a
                    if (full == null) {
                        infoText.text = "本地存档 📦\n时间: ${a.backupTimeStr}\nrowid: ${a.messageRowId}\n消息数: ${a.messageCount}\n附件数: ${a.totalAttachmentFiles}\n大小: ${ArchiveManager.formatSize(a.totalAttachmentSize)}\n\n⚠️ 读取包内信息失败，可先尝试在列表中刷新"
                    } else {
                        fillInfo(ready)
                    }
                    renderButtons(ready)
                }
            }.start()
        } else {
            dialog.show()
            renderButtons(a)
        }
    }

    private fun downloadAndSelect(archive: ArchiveInfo, root: LinearLayout) {
        val localName = File(archive.path).name
        val localPath = File(com.nous.wxhook.backup.BackupEnv.backupDataDir, localName)
        val jobs = ArchiveDownloadPlanner.missingOrChanged(
            listOf(ArchiveDownloadPlanner.Candidate(archive.path, localPath.absolutePath, archive.totalAttachmentSize))
        )
        if (jobs.isEmpty()) {
            ArchiveManager.selectArchive(archive.tag)
            refreshList(root)
            android.widget.Toast.makeText(this, "本地已有完整存档，跳过下载", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        CloudDownloadService.start(this, jobs.map { it.remotePath to it.name })
        android.widget.Toast.makeText(this, "开始下载: ${archive.tag}（通知栏查看进度）", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun downloadAllCloud(cloudArchives: List<ArchiveInfo>, root: LinearLayout) {
        val candidates = cloudArchives.map { archive ->
            val name = File(archive.path).name
            ArchiveDownloadPlanner.Candidate(
                remotePath = archive.path,
                localPath = File(com.nous.wxhook.backup.BackupEnv.backupDataDir, name).absolutePath,
                remoteSize = archive.totalAttachmentSize,
            )
        }
        val jobs = ArchiveDownloadPlanner.missingOrChanged(candidates)
        if (jobs.isEmpty()) {
            android.widget.Toast.makeText(this, "本地已具备全部云端存档，跳过下载", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        CloudDownloadService.start(this, jobs.map { it.remotePath to it.name })
        android.widget.Toast.makeText(this, "开始下载 ${jobs.size} 个新增或变更存档（通知栏查看进度）", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun showDiff(archive: ArchiveInfo) {
        if (archive.source == "cloud") {
            android.widget.Toast.makeText(this, "云端存档请先下载到本地后再对比", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val phoneMsg = ArchiveManager.getPhoneMsgCount()
            val phoneAtt = ArchiveManager.getPhoneAttachmentCounts()
            val diff = ArchiveManager.diffArchive(archive, phoneMsg, phoneAtt)
            runOnUiThread {
                startActivity(android.content.Intent(this, ArchiveDiffActivity::class.java).apply {
                    putExtra("diff_json", org.json.JSONObject().apply {
                        put("archiveTag", archive.tag)
                        put("archiveMsg", diff.archiveMsgCount)
                        put("archiveRowIdFrom", diff.archiveMsgRowIdFrom)
                        put("archiveRowId", diff.archiveMsgRowId)
                        put("phoneMsg", diff.phoneMsgCount)
                        put("phoneRowIdFrom", diff.phoneMsgRowIdFrom)
                        put("phoneRowId", diff.phoneMsgRowId)
                        put("unionMsg", diff.unionMsg)
                        put("onlyInArchive", diff.onlyInArchive)
                        put("onlyInPhone", diff.onlyInPhone)
                        put("phoneTotalAtt", diff.phoneTotalAttachments)
                        put("archiveTotalAtt", diff.archiveTotalAttachments)
                        put("attachments", org.json.JSONObject(diff.attachments.mapValues {
                            org.json.JSONObject().apply {
                                put("phone", it.value.phone)
                                put("archive", it.value.archive)
                                put("phoneMissing", it.value.phoneMissing)
                                put("archiveMissing", it.value.archiveMissing)
                                put("union", it.value.union)
                            }
                        }).toString())
                    }.toString())
                })
            }
        }.start()
    }

    private fun startRestore(archive: ArchiveInfo) {
        if (archive.source == "cloud") {
            android.widget.Toast.makeText(this, "云端存档请先下载到本地后再恢复", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "startRestore: ${archive.tag}")
        android.app.AlertDialog.Builder(this)
            .setTitle("🗂️ 恢复存档")
            .setMessage("即将恢复存档 ${archive.tag}\n\n" +
                "操作步骤:\n" +
                "1. 停止微信\n" +
                "2. 合并数据库\n" +
                "3. 替换手机 DB\n" +
                "4. 复制附件文件\n" +
                "5. 清理校验文件\n\n" +
                "继续？")
            .setPositiveButton("开始恢复") { _, _ -> doRestore(archive) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRestore(archive: ArchiveInfo) {
        val logCard = cardBg()
        logCard.id = View.generateViewId()
        val logView = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; minLines = 5 }
        logCard.addView(TextView(this).apply { text = "📝 恢复日志"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        logCard.addView(logView)
        val root = findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as? android.widget.ScrollView
        root?.let { sv ->
            val ll = sv.getChildAt(0) as? LinearLayout
            ll?.addView(logCard)
        }

        Thread {
            try {
                runOnUiThread { logView.text = "⏳ 停止微信..." }
                Log.i(TAG, "doRestore: force-stopping WeChat")
                RootGateways.run("am force-stop com.tencent.mm 2>/dev/null")
                Thread.sleep(1000)

                val result = RestoreEngine.restore(archive) { msg ->
                    runOnUiThread {
                        logView.append("\n$msg")
                        Log.d(TAG, "restore progress: $msg")
                    }
                }

                runOnUiThread {
                    if (result) logView.append("\n\n✅ 恢复完成！请启动微信验证")
                    else logView.append("\n\n❌ 恢复失败，查看日志")
                }
            } catch (e: Exception) {
                Log.e(TAG, "doRestore: ${e.message}", e)
                runOnUiThread { logView.append("\n❌ ${e.message}") }
            }
        }.start()
    }
}
