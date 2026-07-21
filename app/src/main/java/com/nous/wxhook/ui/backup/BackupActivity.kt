package com.nous.wxhook.ui.backup

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.ui.M3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(1, 0xFFE0E0E0.toInt()) }
        elevation = dp(2).toFloat()
    }

    private fun formatSize(bytes: Long) = when {
        bytes > 1024*1024 -> "%.1f MB".format(bytes.toFloat()/(1024*1024))
        bytes > 1024 -> "%.1f KB".format(bytes.toFloat()/1024)
        else -> "$bytes B"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "备份管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val dir = File("/sdcard/Download/wxhook_backup")
        val files = dir.listFiles()?.filter { it.name.endsWith(".db") || it.name.endsWith(".tar.zst") || it.name.endsWith(".tar.gz") }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val card = cardBg()
        card.addView(TextView(this).apply { text = "📦 备份文件 (${files.size})"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        if (files.isEmpty()) {
            card.addView(TextView(this).apply { text = "暂无备份文件"; textSize = 14f; setPadding(0, dp(8), 0, 0); setTextColor(0xFF9E9E9E.toInt()) })
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            for (f in files) {
                card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)) })
                card.addView(TextView(this).apply { text = "📦 ${f.name}"; textSize = 14f })
                card.addView(TextView(this).apply { text = "${formatSize(f.length())} · ${fmt.format(Date(f.lastModified()))}"; textSize = 12f; setTextColor(0xFF757575.toInt()) })
            }
        }
        root.addView(card)

        // Attachment dirs
        if (dir.exists()) {
            val attCard = cardBg()
            attCard.addView(TextView(this).apply { text = "📁 附件目录"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
            for (d in listOf("image2", "voice2", "video", "cdn")) {
                val ad = File(dir, d)
                if (ad.exists()) {
                    val cnt = ad.walkTopDown().filter { it.isFile }.count()
                    val sz = ad.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    attCard.addView(TextView(this).apply { text = "📂 $d/  · $cnt 文件 · ${formatSize(sz)}"; textSize = 13f; setPadding(0, dp(4), 0, 0) })
                }
            }
            root.addView(attCard)
        }

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })
        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
