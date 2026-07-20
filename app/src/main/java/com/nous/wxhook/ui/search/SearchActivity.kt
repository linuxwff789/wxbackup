package com.nous.wxhook.ui.search

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textview.MaterialTextView
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.ui.M3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SearchActivity : AppCompatActivity() {

    private lateinit var resultView: MaterialTextView
    private lateinit var keywordInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var progressOverlay: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "搜索消息"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        // Search card
        val searchCard = M3.card(this)
        searchCard.addView(M3.titleMedium(this, "🔍 全文搜索"))

        // Search input with M3 TextInputLayout
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputStyle
        ).apply {
            hint = "输入搜索关键词..."
            setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        keywordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            maxLines = 1
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    performSearch()
                    true
                } else false
            }
        }
        textInputLayout.addView(keywordInput)
        searchCard.addView(textInputLayout)

        searchCard.addView(M3.sp(this, 12))
        searchCard.addView(M3.filledButton(this, "搜索全部消息") { performSearch() })
        root.addView(searchCard)
        root.addView(M3.sp(this, 12))

        // Results card
        val resultCard = M3.card(this)
        resultCard.addView(M3.titleMedium(this, "结果"))
        resultView = M3.monoBody(this).apply {
            setPadding(0, M3.dp(this@SearchActivity, 8), 0, 0)
            textSize = 13f
        }
        resultCard.addView(resultView)
        root.addView(resultCard)
        root.addView(M3.sp(this, 16))

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun performSearch() {
        val keyword = keywordInput.text?.toString()?.trim() ?: ""
        if (keyword.isEmpty()) {
            resultView.text = "请输入关键词"
            return
        }

        resultView.text = "⏳ 搜索中..."
        keywordInput.isEnabled = false

        Thread {
            // Get key
            var key: String? = null
            try {
                val hex = File("/data/local/tmp/.wechat_key").readText()
                    .lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            } catch (_: Exception) {}

            if (key == null) {
                runOnUiThread {
                    resultView.text = "❌ 未捕获密钥\n请先确保微信运行中且 Xposed 模块已加载"
                    keywordInput.isEnabled = true
                }
                return@Thread
            }

            val dbPath = "/sdcard/Download/EnMicroMsg.db"
            if (!File(dbPath).exists()) {
                runOnUiThread {
                    resultView.text = "❌ 数据库不存在\n请先复制 EnMicroMsg.db 到 /sdcard/Download/"
                    keywordInput.isEnabled = true
                }
                return@Thread
            }

            val sqlFile = "/data/local/tmp/_search_${System.currentTimeMillis()}.sql"
            try {
                val safeKw = keyword.replace("'", "''")
                File(sqlFile).writeText(
                    "PRAGMA key='$key';PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT talker, type, content, createTime, isSend " +
                    "FROM message WHERE content LIKE '%$safeKw%' " +
                    "ORDER BY createTime DESC LIMIT 100;"
                )
                RootGateways.run("chmod 666 $sqlFile")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:" +
                         "/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val output = RootGateways.runQuiet("$sc '$dbPath' < '$sqlFile'")
                RootGateways.run("rm -f $sqlFile")

                val sb = StringBuilder()
                val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val lines = output.lines().filter { it.contains("|") && !it.startsWith("ok") }

                if (lines.isEmpty()) {
                    sb.appendLine("未找到包含 \"$keyword\" 的消息")
                } else {
                    sb.appendLine("找到 ${lines.size} 条结果")
                    sb.appendLine()
                    var count = 0
                    for (line in lines) {
                        if (count >= 30) {
                            sb.appendLine("... 还有 ${lines.size - count} 条被截断")
                            break
                        }
                        val parts = line.split("|")
                        if (parts.size >= 4) {
                            val talker = parts[0].trim()
                            val content = parts.getOrNull(2)?.trim() ?: ""
                            val timeMs = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0
                            val isSend = parts.getOrNull(4)?.trim() == "1"
                            val time = timeFormat.format(Date(timeMs))
                            val dir = if (isSend) "→" else "←"
                            sb.appendLine("$time $dir [$talker]")
                            sb.appendLine("  ${content.take(200)}")
                            sb.appendLine()
                            count++
                        }
                    }
                }

                runOnUiThread {
                    resultView.text = sb.toString()
                    keywordInput.isEnabled = true
                }
            } catch (e: Exception) {
                RootGateways.run("rm -f $sqlFile")
                runOnUiThread {
                    resultView.text = "错误: ${e.message}"
                    keywordInput.isEnabled = true
                }
            }
        }.start()
    }
}
