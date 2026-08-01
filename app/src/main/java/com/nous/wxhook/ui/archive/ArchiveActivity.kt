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
        val selected = ArchiveManager.getSelectedArchive()
        val statusCard = cardBg()
        statusCard.tag = "status"
        statusCard.addView(TextView(this).apply { text = "🗂️ 存档管理"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        statusCard.addView(selectedStatusLine())
        statusCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        statusCard.addView(MaterialButton(this).apply {
            text = "🔄 刷新存档列表"
            insetTop = 0; insetBottom = 0; setOnClickListener { refreshList(root) }
        })
        root.addView(statusCard)

        // Action buttons
        val actionCard = cardBg()
        actionCard.addView(TextView(this).apply { text = "📋 操作"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        actionCard.addView(TextView(this).apply {
            text = "选择一个存档后，可以对比手机当前数据或执行恢复。"
            textSize = 14f; setPadding(0, dp(8), 0, dp(12)); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(MaterialButton(this).apply {
            text = "📊 对比差异"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            insetTop = 0; insetBottom = 0
            isEnabled = selected != null
            setOnClickListener { showDiff() }
        })
        btnRow.addView(MaterialButton(this).apply {
            text = "▶️ 恢复"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            insetTop = 0; insetBottom = 0
            isEnabled = selected != null
            setOnClickListener { startRestore() }
        })
        actionCard.addView(btnRow)
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

    /** 顶部状态卡的选中信息行（下载/选中后随刷新同步更新） */
    private fun selectedStatusLine(): TextView {
        val selected = ArchiveManager.getSelectedArchive()
        return if (selected != null) {
            TextView(this).apply {
                text = "已选中: ${selected.tag}"
                textSize = 14f; setPadding(0, dp(4), 0, 0); typeface = Typeface.DEFAULT_BOLD
            }
        } else {
            TextView(this).apply {
                text = "未选中存档"
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
            insetTop = 0; insetBottom = 0; setOnClickListener { refreshList(root) }
        })
        root.addView(card, idx)
    }

    private fun refreshList(root: LinearLayout) {
        Thread {
            val localArchives = ArchiveManager.scanLocalArchives()
            val cloudArchives = fetchCloudArchives()

            // Phone current state
            val phoneMsgCount = ArchiveManager.getPhoneMsgCount()
            val phoneAttCounts = ArchiveManager.getPhoneAttachmentCounts()
            val phoneAttTotal = phoneAttCounts.values.sum()

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
            val selected = ArchiveManager.getSelectedArchive()

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
                        val isSelected = selected?.tag == a.tag && a.source != "phone"
                        val marker = if (isSelected) "→ " else "  "
                        val srcIcon = when (a.source) {
                            "cloud" -> "☁️云端 "
                            "phone" -> "📱"
                            else -> "📦本地 "
                        }
                        val srcColor = when (a.source) {
                            "cloud" -> M3.colorPrimary(this)
                            "phone" -> M3.colorSecondary(this)
                            else -> M3.onSurface(this)
                        }
                        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)) })

                        val tf = if (isSelected) Typeface.DEFAULT_BOLD else if (a.source == "phone") Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) else Typeface.DEFAULT
                        val nameLine = TextView(this).apply {
                            text = "$marker$srcIcon${a.tag}"
                            textSize = 14f; typeface = tf
                            setTextColor(srcColor)
                        }
                        if (a.source != "phone") {
                            nameLine.setOnClickListener {
                                if (a.source == "cloud") {
                                    android.app.AlertDialog.Builder(this@ArchiveActivity)
                                        .setTitle("☁️ 云端存档")
                                        .setMessage("是否下载 ${a.tag} 到本地后再恢复？\\n\\n大小: ${ArchiveManager.formatSize(a.totalAttachmentSize)}")
                                        .setPositiveButton("下载并选中") { _, _ ->
                                            downloadAndSelect(a, root)
                                        }
                                        .setNegativeButton("取消", null)
                                        .show()
                                } else {
                                    ArchiveManager.selectArchive(a.tag)
                                    refreshList(root)
                                }
                            }
                        }
                        card.addView(nameLine)
                        card.addView(TextView(this).apply {
                            val detail = when (a.source) {
                                "phone" -> "  ${phoneMsgCount}条消息 · ${phoneAttTotal}个附件"
                                "cloud" -> "  ${ArchiveManager.formatSize(a.totalAttachmentSize)}"
                                else -> "  ${a.backupTimeStr} · ${a.messageCount}条消息 · ${a.totalAttachmentFiles}个附件 · ${ArchiveManager.formatSize(a.totalAttachmentSize)}"
                            }
                            text = "  $detail"
                            textSize = 12f; setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
                        })
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

    private fun downloadAndSelect(archive: ArchiveInfo, root: LinearLayout) {
        val localName = File(archive.path).name
        CloudDownloadService.start(this, listOf(archive.path to localName))
        android.widget.Toast.makeText(this, "开始下载: ${archive.tag}（通知栏查看进度）", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun downloadAllCloud(cloudArchives: List<ArchiveInfo>, root: LinearLayout) {
        val jobs = cloudArchives.map { it.path to File(it.path).name }
        CloudDownloadService.start(this, jobs)
        android.widget.Toast.makeText(this, "开始下载 ${jobs.size} 个云端存档（通知栏查看进度）", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun showDiff() {
        val selected = ArchiveManager.getSelectedArchive() ?: return
        if (selected.source == "cloud") {
            android.widget.Toast.makeText(this, "云端存档请先下载到本地后再对比", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val phoneMsg = ArchiveManager.getPhoneMsgCount()
            val phoneAtt = ArchiveManager.getPhoneAttachmentCounts()
            val diff = ArchiveManager.diffArchive(selected, phoneMsg, phoneAtt)
            runOnUiThread {
                startActivity(android.content.Intent(this, ArchiveDiffActivity::class.java).apply {
                    putExtra("diff_json", org.json.JSONObject().apply {
                        put("archiveTag", selected.tag)
                        put("archiveMsg", diff.archiveMsgCount)
                        put("phoneMsg", diff.phoneMsgCount)
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

    private fun startRestore() {
        val selected = ArchiveManager.getSelectedArchive() ?: return
        if (selected.source == "cloud") {
            android.widget.Toast.makeText(this, "云端存档请先下载到本地后再恢复", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "startRestore: ${selected.tag}")
        android.app.AlertDialog.Builder(this)
            .setTitle("🗂️ 恢复存档")
            .setMessage("即将恢复存档 ${selected.tag}\n\n" +
                "操作步骤:\n" +
                "1. 停止微信\n" +
                "2. 合并数据库\n" +
                "3. 替换手机 DB\n" +
                "4. 复制附件文件\n" +
                "5. 清理校验文件\n\n" +
                "继续？")
            .setPositiveButton("开始恢复") { _, _ -> doRestore(selected) }
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
