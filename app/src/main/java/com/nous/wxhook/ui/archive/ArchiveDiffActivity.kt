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
        // 消息口径：存档=存档链（基线+增量）rowid 区间，手机=count(*) + rowid 区间，差异用 rowid 判断
        val archTo = j.optLong("archiveRowId", 0)
        val phoneTo = j.optLong("phoneRowId", 0)
        val gap = j.optLong("rowIdGap", 0)
        val chainCount = j.optInt("chainCount", 1)
        val chainFrom = j.optLong("chainFrom", 0)
        val chainTo = j.optLong("chainTo", 0)
        val chainHasGap = j.optBoolean("chainHasGap", false)
        infoCard.addView(row("📝 消息", "手机当前", "${group(j.optLong("phoneMsg", 0))} 条"))
        val chainLabel = if (chainCount > 1) "存档链(${chainCount}包)" else "存档"
        // 注意：chainFrom 合法值可以是 0（全量基线起点），不能拿 chainFrom>0 判断是否走链
        val chainRange = if (chainCount > 1) {
            rowIdRange(chainFrom, chainTo)
        } else {
            rowIdRange(j.optLong("archiveRowIdFrom", 0), archTo)
        }
        infoCard.addView(row("🗂️ rowid", chainLabel, chainRange))
        infoCard.addView(row("", "手机", rowIdRange(j.optLong("phoneRowIdFrom", 0), phoneTo)))
        val gapText = when {
            gap > 0 -> "存档领先 ${group(gap)} 个 rowid（存档更新）"
            gap < 0 -> "手机领先 ${group(-gap)} 个 rowid（手机更新）"
            else -> "rowid 一致"
        }
        infoCard.addView(row("", "进度", gapText, M3.colorPrimary(this)))
        if (chainHasGap) {
            infoCard.addView(row("⚠️", "存档链", "包之间 rowid 不连续，可能存在缺口", M3.colorError(this)))
        }
        if (phoneTo <= 0 && j.optLong("phoneMsg", 0) <= 0) {
            infoCard.addView(row("⚠️", "手机数据", "读取失败（请确认微信已登录、root 权限正常）", M3.colorError(this)))
        }
        root.addView(infoCard)

        // Attachments
        val attCard = cardBg()
        attCard.addView(TextView(this).apply { text = "🗂️ 附件对比"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        attCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)) })

        try {
            val atts = JSONObject(j.optString("attachments", "{}"))
            val keys = atts.keys().asSequence().sorted().toList()
            for (k in keys) {
                val v = atts.getJSONObject(k)
                val phoneN = v.optInt("phone", 0)
                val archN = v.optInt("archive", 0)
                val pMiss = v.optInt("phoneMissing", 0)  // 手机缺（存档有手机无）
                val aMiss = v.optInt("archiveMissing", 0) // 存档缺（手机有存档无）
                val miss = when {
                    pMiss > 0 && aMiss > 0 -> "手机缺$pMiss · 存档缺$aMiss"
                    pMiss > 0 -> "手机缺$pMiss"
                    aMiss > 0 -> "存档缺$aMiss"
                    else -> "一致"
                }
                val missColor = if (pMiss > 0 || aMiss > 0) M3.colorError(this) else 0
                attCard.addView(row("  $k", "手机:$phoneN · 存档:$archN", miss, missColor))
            }
        } catch (_: Exception) {}

        attCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)) })
        val phoneTotal = j.optInt("phoneTotalAtt", 0)
        val archiveTotal = j.optInt("archiveTotalAtt", 0)
        attCard.addView(row("合计", "手机 $phoneTotal · 存档 $archiveTotal",
            if (phoneTotal == archiveTotal) "一致" else "不一致", if (phoneTotal == archiveTotal) 0 else M3.colorError(this)))
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

    /** rowid 范围显示：from ~ to（完整数字，不缩写；from=0 时只显示 to）。 */
    private fun rowIdRange(from: Long, to: Long): String =
        if (from > 0 && to >= from) "${group(from)} ~ ${group(to)}" else group(to)

    /** 千分位分组，rowid 需要精确值，不能用 K 缩写。 */
    private fun group(n: Long): String {
        val s = n.toString()
        return s.reversed().chunked(3).joinToString(",").reversed()
    }
}
