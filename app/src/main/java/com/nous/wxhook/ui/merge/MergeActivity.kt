package com.nous.wxhook.ui.merge

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.nous.wxhook.db.MergeEngine
import com.nous.wxhook.ui.M3
import java.io.File

class MergeActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(M3.colorSurface(this@MergeActivity)); setStroke(1, M3.colorOutline(this@MergeActivity)) }
        elevation = dp(2).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "数据合并"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        val infoCard = cardBg()
        infoCard.addView(TextView(this).apply { text = "🔗 合并备份数据"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        infoCard.addView(TextView(this).apply { text = "将两个备份数据库合并，方便统一查看和管理聊天记录。"; textSize = 14f; setPadding(0, dp(8), 0, dp(12)); setTextColor(M3.onSurfaceVariant(this@MergeActivity)) })
        infoCard.addView(MaterialButton(this).apply { text = "🔄 合并最近两个备份"; insetTop = 0; insetBottom = 0; setOnClickListener { mergeRecent() } })
        root.addView(infoCard)

        val logCard = cardBg()
        logCard.addView(TextView(this).apply { text = "📝 合并日志"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        logView = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0); minLines = 3 }
        logCard.addView(logView)
        root.addView(logCard)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })

        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun mergeRecent() {
        logView.text = "⏳ 扫描备份文件..."
        Thread {
            try {
                val dir = File("/sdcard/Download/wxhook_backup")
                if (!dir.exists()) { runOnUiThread { logView.text = "❌ 备份目录不存在" }; return@Thread }
                val dbs = dir.listFiles()?.filter { it.name.endsWith(".db") && !it.name.contains("-journal") && !it.name.contains("-wal") }?.sortedByDescending { it.lastModified() } ?: emptyList()
                if (dbs.size < 2) { runOnUiThread { logView.text = "❌ 需要至少 2 个备份文件\n找到: ${dbs.size} 个" }; return@Thread }
                val latest = dbs[0]; val previous = dbs[1]
                val out = File(dir, "merged_${System.currentTimeMillis()}.db")
                runOnUiThread { logView.text = "✅ 找到:\n  ${latest.name}\n  ${previous.name}\n⏳ 合并中..." }
                val result = MergeEngine.mergeDatabases(latest.absolutePath, previous.absolutePath, out.absolutePath)
                runOnUiThread {
                    logView.text = if (result.mergedMessages > 0 || result.duplicatesRemoved == result.totalMessages)
                        "✅ 合并完成\n新增: ${result.mergedMessages}\n去重: ${result.duplicatesRemoved}\n输出: ${out.name}"
                    else "❌ 合并失败\n${result.conflicts.joinToString("\n")}"
                }
            } catch (e: Exception) { runOnUiThread { logView.text = "❌ ${e.message}" } }
        }.start()
    }
}
