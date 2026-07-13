package com.nous.wxhook.ui.search

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SearchActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var searchText: EditText
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        layout.addView(TextView(this).apply { text = "搜索消息"; textSize = 20f })
        searchText = EditText(this).apply { hint = "输入关键词..."; setPadding(0, 32, 0, 0) }
        layout.addView(searchText)
        android.widget.Button(this).apply { text = "搜索"; setOnClickListener { performSearch() } }.let { layout.addView(it) }
        resultText = TextView(this).apply { textSize = 12f; setPadding(0, 32, 0, 0) }
        layout.addView(resultText)
        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun su(cmd: String): String = RootCommandRunner.runSuQuiet(cmd)

    private fun performSearch() {
        val keyword = searchText.text.toString().trim()
        if (keyword.isEmpty()) { resultText.text = "请输入关键词"; return }
        resultText.text = "搜索中..."
        Thread {
            var key: String? = null
            try {
                val hex = File("/data/local/tmp/.wechat_key").readText()
                    .lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            } catch (_: Exception) {}
            if (key == null) { handler.post { resultText.text = "未捕获密钥" }
                return@Thread }

            val dbPath = "/sdcard/Download/EnMicroMsg.db"
            if (!File(dbPath).exists()) { handler.post { resultText.text = "数据库不存在" }
                return@Thread }

            val sqlFile = "/data/data/com.nous.wxhook/cache/q_${UUID.randomUUID()}.sql"
            try {
                val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                File(sqlFile).writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT talker, type, content, createTime, isSend FROM message WHERE content LIKE '%$keyword%' ORDER BY createTime DESC LIMIT 100;")
                su("chmod 666 $sqlFile")
                val output = su("LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher $dbPath < $sqlFile 2>&1")
                su("rm -f $sqlFile")

                val sb = StringBuilder()
                val lines = output.lines().filter { it.contains("|") }
                if (lines.isEmpty()) {
                    sb.appendLine("未找到包含 \"$keyword\" 的消息")
                } else {
                    sb.appendLine("找到 ${lines.size} 条结果\n")
                    for (line in lines) {
                        val parts = line.split("|")
                        if (parts.size >= 4) {
                            val talker = parts[0].trim()
                            val content = parts[2].trim()
                            val timeMs = parts[3].trim().toLongOrNull() ?: 0
                            val isSend = parts[4].trim() == "1"
                            val time = timeFormat.format(Date(timeMs))
                            val dir = if (isSend) "→" else "←"
                            sb.appendLine("$time $dir [$talker]")
                            sb.appendLine("  ${content.take(150)}")
                            sb.appendLine()
                        }
                    }
                }
                handler.post { resultText.text = sb.toString() }
            } catch (e: Exception) {
                su("rm -f $sqlFile")
                handler.post { resultText.text = "错误: ${e.message}" }
            }
        }.start()
    }
}
