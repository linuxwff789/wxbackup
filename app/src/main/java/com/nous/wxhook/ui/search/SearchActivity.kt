package com.nous.wxhook.ui.search

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.ui.M3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SearchActivity : AppCompatActivity() {

    private lateinit var resultView: TextView
    private lateinit var keywordInput: EditText
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(M3.colorSurface(this@SearchActivity)); setStroke(1, M3.colorOutline(this@SearchActivity)) }
        elevation = dp(2).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "搜索消息"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        val searchCard = cardBg()
        searchCard.addView(TextView(this).apply { text = "🔍 全文搜索"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        keywordInput = EditText(this).apply {
            hint = "输入搜索关键词..."
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { performSearch(); true } else false }
        }
        searchCard.addView(keywordInput)
        searchCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        searchCard.addView(MaterialButton(this).apply {
            text = "搜索全部消息"; insetTop = 0; insetBottom = 0; setOnClickListener { performSearch() }
        })
        root.addView(searchCard)

        val resultCard = cardBg()
        resultCard.addView(TextView(this).apply { text = "结果"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        resultView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0) }
        resultCard.addView(resultView)
        root.addView(resultCard)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })

        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun performSearch() {
        val keyword = keywordInput.text?.toString()?.trim() ?: ""
        if (keyword.isEmpty()) { resultView.text = "请输入关键词"; return }
        resultView.text = "⏳ 搜索中..."
        Thread {
            var key: String? = null
            try {
                val hex = File("/data/local/tmp/.wechat_key").readText().lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            } catch (_: Exception) {}
            if (key == null) { handler.post { resultView.text = "❌ 未捕获密钥" }; return@Thread }
            val dbPath = "/sdcard/Download/EnMicroMsg.db"
            if (!File(dbPath).exists()) { handler.post { resultView.text = "❌ 数据库不存在" }; return@Thread }
            val sqlFile = "/data/local/tmp/_search_${System.currentTimeMillis()}.sql"
            try {
                val safeKw = keyword.replace("'", "''")
                File(sqlFile).writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT talker,type,content,createTime,isSend FROM message WHERE content LIKE '%$safeKw%' ORDER BY createTime DESC LIMIT 100;")
                RootGateways.run("chmod 666 $sqlFile")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val output = RootGateways.runQuiet("$sc '$dbPath' < '$sqlFile'")
                RootGateways.run("rm -f $sqlFile")
                val sb = StringBuilder()
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val lines = output.lines().filter { it.contains("|") && !it.startsWith("ok") }
                if (lines.isEmpty()) { sb.appendLine("未找到包含 \"$keyword\" 的消息") }
                else {
                    sb.appendLine("找到 ${lines.size} 条结果\n")
                    var c = 0
                    for (line in lines) {
                        if (c >= 30) { sb.appendLine("... 还有 ${lines.size - c} 条"); break }
                        val p = line.split("|")
                        if (p.size >= 4) {
                            sb.appendLine("${fmt.format(Date(p[3].trim().toLongOrNull() ?: 0))} ${if (p[4].trim() == "1") "→" else "←"} [${p[0].trim()}]")
                            sb.appendLine("  ${p[2].trim().take(200)}\n")
                            c++
                        }
                    }
                }
                handler.post { resultView.text = sb.toString() }
            } catch (e: Exception) { RootGateways.run("rm -f $sqlFile"); handler.post { resultView.text = "错误: ${e.message}" } }
        }.start()
    }
}
