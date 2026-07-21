package com.nous.wxhook.ui.status

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.nous.wxhook.db.DbCleanup
import com.nous.wxhook.ui.M3

class StatusActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(1, 0xFFE0E0E0.toInt())
        }
        elevation = dp(2).toFloat()
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "状态检测"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // Status card
        val statusCard = cardBg()
        statusCard.addView(TextView(this).apply {
            text = "📊 当前状态"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })

        val sb = StringBuilder()
        var key: String? = null
        try {
            val hex = java.io.File("/data/local/tmp/.wechat_key").readText()
                .lines().find { it.startsWith("key=") }?.removePrefix("key=")
            if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) {}
        sb.appendLine("密钥: ${if (key != null) "✅ $key" else "❌ 未捕获"}")
        val dbFile = java.io.File("/sdcard/Download/EnMicroMsg.db")
        if (dbFile.exists()) sb.appendLine("数据库: ✅ ${"%.1f".format(dbFile.length().toFloat() / (1024*1024))}MB")
        else sb.appendLine("数据库: ❌ 不存在")
        try { sb.appendLine(DbCleanup.getDiskInfo()) } catch (_: Exception) {}

        statusCard.addView(TextView(this).apply {
            text = sb.toString(); textSize = 13f; typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(statusCard)

        // Help card
        if (key != null) {
            val helpCard = cardBg()
            helpCard.addView(TextView(this).apply {
                text = "📋 快捷命令"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            })
            helpCard.addView(TextView(this).apply {
                textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
                text = "解密查询:\necho \"PRAGMA key='$key';PRAGMA cipher_compatibility=3;...\" | sqlcipher /sdcard/Download/EnMicroMsg.db"
            })
            root.addView(helpCard)
        }

        // Buttons
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(MaterialButton(this).apply {
            text = "🔄 刷新"; insetTop = 0; insetBottom = 0
            setOnClickListener { recreate() }
        })
        btns.addView(spacer(12))
        btns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📁 文件管理"; insetTop = 0; insetBottom = 0
            setOnClickListener {
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { android.widget.Toast.makeText(this@StatusActivity, "无法打开", android.widget.Toast.LENGTH_SHORT).show() }
            }
        })
        root.addView(btns)
        root.addView(spacer(16))

        sv.addView(root)
        setContentView(sv)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
