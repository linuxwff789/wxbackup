package com.nous.wxhook.ui.backup

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }

        layout.addView(TextView(this).apply { text = "备份管理"; textSize = 20f })

        val dir = File("/sdcard/Download/wxhook_backup")
        if (dir.exists()) {
            val files = dir.listFiles()?.filter { it.name.endsWith(".db") }?.sortedByDescending { it.lastModified() } ?: emptyList()
            layout.addView(TextView(this).apply { text = "\n备份文件 (${files.size}个)"; textSize = 16f })
            if (files.isEmpty()) {
                layout.addView(TextView(this).apply { text = "  暂无备份"; textSize = 14f; setPadding(0, 8, 0, 0) })
            } else {
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                files.forEach { f ->
                    val sizeMB = "%.1f".format(f.length().toFloat() / 1024 / 1024)
                    val time = fmt.format(Date(f.lastModified()))
                    layout.addView(TextView(this).apply {
                        text = "  📦 ${f.name}\n     ${sizeMB}MB · $time"
                        textSize = 13f; setPadding(0, 8, 0, 0)
                    })
                }
            }
            // 附件目录
            val attDirs = listOf("image2", "voice2", "video", "cdn")
            attDirs.forEach { d ->
                val ad = File(dir, d)
                if (ad.exists()) {
                    val count = ad.walkTopDown().filter { it.isFile }.count()
                    val sizeMB = "%.1f".format(ad.walkTopDown().filter { it.isFile }.sumOf { it.length() }.toFloat() / 1024 / 1024)
                    layout.addView(TextView(this).apply { text = "  📁 $d/ ($count 文件, ${sizeMB}MB)"; textSize = 13f; setPadding(0, 4, 0, 0) })
                }
            }
        } else {
            layout.addView(TextView(this).apply { text = "\n暂无备份\n通过模块入口页面进行备份"; textSize = 14f })
        }

        sv.addView(layout)
        setContentView(sv)
    }
}
