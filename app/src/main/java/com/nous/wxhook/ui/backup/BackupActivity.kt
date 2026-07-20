package com.nous.wxhook.ui.backup

import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.ui.M3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "备份管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        val card = M3.card(this)
        card.addView(M3.titleMedium(this, "📦 备份文件"))

        val dir = File("/sdcard/Download/wxhook_backup")
        if (dir.exists()) {
            val files = dir.listFiles()
                ?.filter { it.name.endsWith(".db") || it.name.endsWith(".tar.zst") || it.name.endsWith(".tar.gz") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()

            if (files.isEmpty()) {
                card.addView(M3.body(this, "暂无备份文件").apply {
                    setPadding(0, M3.dp(this@BackupActivity, 8), 0, 0)
                    setTextColor(M3.onSurfaceVariant(this@BackupActivity))
                })
            } else {
                card.addView(M3.label(this, "共 ${files.size} 个备份文件").apply {
                    setPadding(0, M3.dp(this@BackupActivity, 8), 0, 0)
                })

                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                files.forEach { f ->
                    val size = formatSize(f.length())
                    val time = fmt.format(Date(f.lastModified()))
                    val fileCard = M3.outlinedCard(this).apply {
                        val lp = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, M3.dp(this@BackupActivity, 6), 0, 0) }
                        layoutParams = lp
                    }
                    fileCard.addView(M3.bodyMedium(this, f.name))
                    fileCard.addView(M3.label(this, "$size · $time"))
                    card.addView(fileCard)
                }
            }

            // Attachment directories summary
            val attDirs = listOf("image2", "voice2", "video", "cdn")
            card.addView(M3.sp(this, 12))
            card.addView(M3.titleMedium(this, "📁 附件目录"))

            attDirs.forEach { d ->
                val ad = File(dir, d)
                if (ad.exists()) {
                    val filesList = ad.walkTopDown().filter { it.isFile }.toList()
                    val count = filesList.size
                    val totalSize = filesList.sumOf { it.length() }
                    card.addView(M3.label(this, "📂 $d/  ·  $count 文件 ·  ${formatSize(totalSize)}").apply {
                        setPadding(0, M3.dp(this@BackupActivity, 4), 0, 0)
                    })
                }
            }
        } else {
            card.addView(M3.body(this, "暂无备份目录").apply {
                setPadding(0, M3.dp(this@BackupActivity, 8), 0, 0)
            })
            card.addView(M3.label(this, "通过「模块入口 > 备份管理」进行备份").apply {
                setPadding(0, M3.dp(this@BackupActivity, 4), 0, 0)
            })
        }

        root.addView(card)
        root.addView(M3.sp(this, 16))

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / (1024 * 1024 * 1024))
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / (1024 * 1024))
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
