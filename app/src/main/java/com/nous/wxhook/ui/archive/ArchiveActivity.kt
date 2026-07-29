package com.nous.wxhook.ui.archive

import android.graphics.Typeface
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
import com.nous.wxhook.ui.M3

class ArchiveActivity : AppCompatActivity() {

    private val TAG = "wxhook:ArchiveUI"
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
        supportActionBar?.title = "存档管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // Selected archive status
        val selected = ArchiveManager.getSelectedArchive()
        val statusCard = cardBg()
        statusCard.addView(TextView(this).apply { text = "🗂️ 存档管理"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        if (selected != null) {
            statusCard.addView(TextView(this).apply {
                text = "已选中: ${selected.tag}"
                textSize = 14f; setPadding(0, dp(4), 0, 0); typeface = Typeface.DEFAULT_BOLD
            })
            statusCard.addView(TextView(this).apply {
                text = "消息: ${selected.messageCount} · 附件: ${selected.totalAttachmentFiles} 文件"
                textSize = 13f; setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
            })
        } else {
            statusCard.addView(TextView(this).apply {
                text = "未选中存档"
                textSize = 14f; setPadding(0, dp(4), 0, 0); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
            })
        }
        statusCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        statusCard.addView(MaterialButton(this).apply {
            text = "🔄 刷新存档列表"
            insetTop = 0; insetBottom = 0; setOnClickListener { refreshList(root) }
        })
        root.addView(statusCard)

        // Refresh + Diff buttons
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
            text = "☁️ 云端列表"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            insetTop = 0; insetBottom = 0
            setOnClickListener { showCloudArchives() }
        })
        actionCard.addView(btnRow)
        actionCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        actionCard.addView(MaterialButton(this).apply {
            text = "▶️ 恢复选中存档"
            insetTop = 0; insetBottom = 0
            isEnabled = selected != null
            setOnClickListener { startRestore() }
        })
        root.addView(actionCard)

        // Archive list placeholder (populated by refreshList)
        val listCard = cardBg()
        listCard.id = View.generateViewId()
        listCard.addView(TextView(this).apply { text = "📦 本地存档"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        listCard.addView(TextView(this).apply {
            text = "点击「刷新」加载存档列表"
            textSize = 14f; setPadding(0, dp(8), 0, 0); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
        })
        root.addView(listCard)

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })
        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refreshList(root: LinearLayout) {
        Thread {
            val archives = ArchiveManager.scanLocalArchives()
            val selected = ArchiveManager.getSelectedArchive()
            runOnUiThread {
                // Remove old list card and rebuild
                val oldCard = root.findViewWithTag<View>("archives") ?: return@runOnUiThread
                val idx = root.indexOfChild(oldCard)
                root.removeView(oldCard)

                val card = cardBg().apply { tag = "archives" }
                card.addView(TextView(this).apply { text = "📦 本地存档 (${archives.size})"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })

                if (archives.isEmpty()) {
                    card.addView(TextView(this).apply {
                        text = "暂无存档\n请先通过备份功能创建备份"
                        textSize = 14f; setPadding(0, dp(8), 0, 0); setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
                    })
                } else {
                    for (a in archives) {
                        val isSelected = selected?.tag == a.tag
                        val marker = if (isSelected) "→ " else "  "
                        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)) })
                        val line = TextView(this).apply {
                            text = "$marker${a.tag}"
                            textSize = 14f; typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        }
                        line.setOnClickListener {
                            ArchiveManager.selectArchive(a.tag)
                            refreshList(root)
                        }
                        card.addView(line)
                        card.addView(TextView(this).apply {
                            text = "  ${a.backupTimeStr} · ${a.messageCount}条消息 · ${a.totalAttachmentFiles}个附件 · ${ArchiveManager.formatSize(a.totalAttachmentSize)}"
                            textSize = 12f; setTextColor(M3.onSurfaceVariant(this@ArchiveActivity))
                        })
                    }
                }
                root.addView(card, idx)
            }
        }.start()
    }

    private fun showDiff() {
        val selected = ArchiveManager.getSelectedArchive() ?: return
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

    private fun showCloudArchives() {
        Thread {
            val config = com.nous.wxhook.sync.Syncer.loadConfig()
            if (!config.isValid) {
                runOnUiThread {
                    android.app.AlertDialog.Builder(this@ArchiveActivity)
                        .setTitle("☁️ 云端存档")
                        .setMessage("云端功能需要配置阿里云盘或 WebDAV。\n请在「设置」→「云存储驱动」中添加账号。")
                        .setPositiveButton("去配置") { _, _ ->
                            startActivity(android.content.Intent(this@ArchiveActivity, com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java))
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                return@Thread
            }
            val client = com.nous.wxhook.sync.Syncer.createClient(config)
            if (client == null) {
                runOnUiThread {
                    android.app.AlertDialog.Builder(this@ArchiveActivity)
                        .setTitle("☁️ 云端存档")
                        .setMessage("创建云存储客户端失败")
                        .setPositiveButton("确定", null)
                        .show()
                }
                return@Thread
            }
            runOnUiThread {
                val dialog = android.app.AlertDialog.Builder(this@ArchiveActivity)
                    .setTitle("☁️ 云端存档")
                    .setMessage("正在获取远端文件列表...")
                    .setNegativeButton("取消", null)
                    .show()
                Thread {
                    val result = kotlinx.coroutines.runBlocking { client.list(config.remotePath) }
                    runOnUiThread {
                        dialog.dismiss()
                        if (result.isSuccess) {
                            val files = result.getOrNull() ?: emptyList()
                            if (files.isEmpty()) {
                                android.widget.Toast.makeText(this@ArchiveActivity, "远端无存档文件", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                val sb = StringBuilder()
                                for (f in files.take(20)) {
                                    val name = java.io.File(f.path).name
                                    val size = when {
                                        f.size > 1024*1024*1024 -> "%.1fGB".format(f.size.toFloat()/(1024*1024*1024))
                                        f.size > 1024*1024 -> "%.1fMB".format(f.size.toFloat()/(1024*1024))
                                        else -> "${f.size}B"
                                    }
                                    sb.appendLine("📄 $name ($size)")
                                }
                                if (files.size > 20) sb.appendLine("... 还有 ${files.size - 20} 个文件")
                                android.app.AlertDialog.Builder(this@ArchiveActivity)
                                    .setTitle("☁️ 云端存档 (${files.size})")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("确定", null)
                                    .show()
                            }
                        } else {
                            val err = result.exceptionOrNull()?.message ?: "未知错误"
                            android.app.AlertDialog.Builder(this@ArchiveActivity)
                                .setTitle("☁️ 云端存档")
                                .setMessage("获取失败: $err")
                                .setPositiveButton("确定", null)
                                .show()
                        }
                    }
                }.start()
            }
        }.start()
    }

    private fun startRestore() {
        val selected = ArchiveManager.getSelectedArchive() ?: return
        Log.i(TAG, "startRestore: ${selected.tag}")

        val dialog = android.app.AlertDialog.Builder(this)
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
                    if (result) {
                        logView.append("\n\n✅ 恢复完成！请启动微信验证")
                    } else {
                        logView.append("\n\n❌ 恢复失败，查看日志")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "doRestore: ${e.message}", e)
                runOnUiThread { logView.append("\n❌ ${e.message}") }
            }
        }.start()
    }
}
