package com.nous.wxhook.ui.merge

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.db.MergeEngine
import com.nous.wxhook.ui.M3
import java.io.File

class MergeActivity : AppCompatActivity() {

    private lateinit var logView: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "数据合并"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        // Info card
        val infoCard = M3.card(this)
        infoCard.addView(M3.titleMedium(this, "🔗 合并备份数据"))
        infoCard.addView(M3.body(this, "将两个备份数据库合并，方便统一查看和管理聊天记录。").apply {
            setPadding(0, M3.dp(this@MergeActivity, 8), 0, M3.dp(this@MergeActivity, 12))
        })

        infoCard.addView(M3.filledButton(this, "🔄 合并最近两个备份") {
            mergeRecent()
        })
        root.addView(infoCard)
        root.addView(M3.sp(this, 12))

        // Log card
        val logCard = M3.card(this)
        logCard.addView(M3.titleMedium(this, "📝 合并日志"))
        logView = M3.monoBody(this).apply {
            textSize = 13f
            setPadding(0, M3.dp(this@MergeActivity, 8), 0, 0)
            minLines = 3
        }
        logCard.addView(logView)
        root.addView(logCard)
        root.addView(M3.sp(this, 16))

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun mergeRecent() {
        logView.text = "⏳ 扫描备份文件..."
        Thread {
            try {
                val backupDir = File("/sdcard/Download/wxhook_backup")
                if (!backupDir.exists()) {
                    runOnUiThread { logView.text = "❌ 备份目录不存在" }
                    return@Thread
                }

                // Find DB backup files (decrypted SQLite dumps)
                val dbFiles = backupDir.listFiles()
                    ?.filter { it.name.endsWith(".db") && !it.name.contains("-journal") && !it.name.contains("-wal") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                if (dbFiles.size < 2) {
                    runOnUiThread {
                        logView.text = "❌ 需要至少 2 个备份文件\n" +
                                       "找到: ${dbFiles.size} 个 .db 文件\n" +
                                       "请在「备份管理」页面先创建备份"
                    }
                    return@Thread
                }

                val latest = dbFiles[0]
                val previous = dbFiles[1]
                val outputFile = File(backupDir, "merged_${System.currentTimeMillis()}.db")

                runOnUiThread {
                    logView.text = "✅ 找到两个备份:\n" +
                                   "  1. ${latest.name} (${formatSize(latest.length())})\n" +
                                   "  2. ${previous.name} (${formatSize(previous.length())})\n" +
                                   "⏳ 正在合并 (UNION 策略)..."
                }

                // Execute merge via MergeEngine
                val result = MergeEngine.mergeDatabases(
                    baseDbPath = latest.absolutePath,
                    overlayDbPath = previous.absolutePath,
                    outputPath = outputFile.absolutePath
                )

                runOnUiThread {
                    logView.text = buildString {
                        if (result.mergedMessages > 0 || result.duplicatesRemoved == result.totalMessages) {
                            appendLine("✅ 合并完成")
                            appendLine("  输出文件: ${outputFile.name}")
                            appendLine("  基础库消息: ${MergeEngine.cliCount(latest.absolutePath, "e9cd2ae")}")
                            appendLine("  叠加库消息: ${result.totalMessages}")
                            appendLine("  新增消息: ${result.mergedMessages}")
                            appendLine("  去重: ${result.duplicatesRemoved}")
                        } else {
                            appendLine("❌ 合并失败")
                            if (result.conflicts.isNotEmpty()) {
                                result.conflicts.forEach { appendLine("  $it") }
                            } else {
                                appendLine("  请检查数据库是否有效")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logView.text = "❌ 合并异常: ${e.message}"
                }
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / (1024 * 1024))
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }
}
