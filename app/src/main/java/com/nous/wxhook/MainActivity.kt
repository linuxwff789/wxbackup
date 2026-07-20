package com.nous.wxhook

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.ui.M3

/**
 * Main launcher — MD3-styled navigation hub.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val modules = listOf(
            Triple("状态检测", "📊", "查看密钥、数据库、环境状态") to com.nous.wxhook.ui.status.StatusActivity::class.java,
            Triple("聊天列表", "💬", "浏览微信会话列表") to com.nous.wxhook.ui.chatlist.ChatListActivity::class.java,
            Triple("搜索消息", "🔍", "全文搜索聊天记录") to com.nous.wxhook.ui.search.SearchActivity::class.java,
            Triple("备份管理", "📦", "全量/增量备份与恢复") to com.nous.wxhook.ui.module.ModuleActivity::class.java,
            Triple("数据合并", "🔗", "合并多个备份文件") to com.nous.wxhook.ui.merge.MergeActivity::class.java,
            Triple("云同步", "☁️", "WebDAV / 阿里云盘同步") to com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java,
            Triple("设置", "⚙️", "WebDAV 配置、备份路径等") to com.nous.wxhook.ui.settings.SettingsActivity::class.java,
        )

        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        // Header
        root.addView(M3.title(this, "wxhook"))
        root.addView(M3.label(this, "微信聊天记录管理工具").apply {
            setPadding(0, M3.dp(this@MainActivity, 4), 0, M3.dp(this@MainActivity, 24))
        })

        // Navigation cards
        for ((info, cls) in modules) {
            val (title, icon, desc) = info
            val card = M3.card(this@MainActivity)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, M3.dp(this@MainActivity, 12)) }
            card.layoutParams = lp

            card.setOnClickListener {
                startActivity(Intent(this@MainActivity, cls))
            }

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // Icon
            row.addView(android.widget.TextView(this@MainActivity).apply {
                text = icon
                textSize = 28f
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    M3.dp(this@MainActivity, 48),
                    M3.dp(this@MainActivity, 48)
                )
            })

            // Text column
            val col = M3.vLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setPadding(0, 0, 0, 0)
            }
            col.addView(M3.titleMedium(this@MainActivity, title))
            col.addView(M3.label(this@MainActivity, desc))
            row.addView(col)

            // Arrow
            row.addView(android.widget.TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                setTextColor(M3.onSurfaceVariant(this@MainActivity))
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    M3.dp(this@MainActivity, 32),
                    M3.dp(this@MainActivity, 48)
                )
            })

            card.addView(row)
            root.addView(card)
        }

        // Version footer
        root.addView(M3.labelSmall(this@MainActivity, "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").apply {
            gravity = Gravity.CENTER
            setPadding(0, M3.dp(this@MainActivity, 16), 0, M3.dp(this@MainActivity, 32))
        })

        scrollView.addView(root)
        setContentView(scrollView)
    }
}
