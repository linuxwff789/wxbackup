package com.nous.wxhook.ui.archive

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.backup.ArchiveManager
import com.nous.wxhook.ui.M3
import org.json.JSONObject

class ArchiveDiffActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(M3.colorSurface(this@ArchiveDiffActivity)); setStroke(1, M3.colorOutline(this@ArchiveDiffActivity)) }
        elevation = dp(2).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "存档对比"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val jsonStr = intent.getStringExtra("diff_json") ?: "{}"
        val j = try { JSONObject(jsonStr) } catch (_: Exception) { JSONObject() }

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // Archive info
        val infoCard = cardBg()
        infoCard.addView(TextView(this).apply { text = "📊 ${j.optString("archiveTag", "?")} vs 手机当前"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        infoCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)) })
        infoCard.addView(row("📝 消息", "存档", formatNum(j.optLong("archiveMsg", 0))))
        infoCard.addView(row("", "手机", formatNum(j.optLong("phoneMsg", 0))))
        infoCard.addView(row("🗂️ rowid", "存档", formatNum(j.optLong("archiveRowId", 0))))
        infoCard.addView(row("", "手机", formatNum(j.optLong("phoneRowId", 0))))
        infoCard.addView(row("", "仅存档有", formatNum(j.optLong("onlyInArchive", 0)), M3.colorPrimary(this)))
        infoCard.addView(row("", "仅手机有", formatNum(j.optLong("onlyInPhone", 0)), M3.colorPrimary(this)))
        infoCard.addView(row("", "合并后", formatNum(j.optLong("unionMsg", 0)), M3.colorPrimary(this)))
        root.addView(infoCard)

        // Attachments
        val attCard = cardBg()
        attCard.addView(TextView(this).apply { text = "🗂️ 附件对比"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        attCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)) })

        try {
            val atts = JSONObject(j.optString("attachments", "{}"))
            val keys = atts.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = atts.getJSONObject(k)
                attCard.addView(row("  $k", "手机:${v.optInt("phone",0)} · 存档:${v.optInt("archive",0)}",
                    "缺${v.optInt("phoneMissing",0)}个"))
            }
        } catch (_: Exception) {}

        attCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)) })
        attCard.addView(row("合计", "手机 ${j.optInt("phoneTotalAtt",0)} · 存档 ${j.optInt("archiveTotalAtt",0)}"))
        root.addView(attCard)

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })
        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun row(prefix: String, label: String, value: String = "", color: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
        addView(TextView(this@ArchiveDiffActivity).apply {
            text = prefix; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(this@ArchiveDiffActivity).apply {
            text = label; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (color != 0) setTextColor(color)
        })
        if (value.isNotEmpty()) {
            addView(TextView(this@ArchiveDiffActivity).apply {
                text = value; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                if (color != 0) setTextColor(color)
            })
        }
    }

    private fun formatNum(n: Long) = if (n >= 10000) "${n / 1000}K" else "$n"
}
