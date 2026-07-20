package com.nous.wxhook

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.nous.wxhook.ui.M3
import com.google.android.material.textview.MaterialTextView

/**
 * Main launcher — MD3-styled navigation hub.
 *
 * Displays feature modules as elevated cards in a scrollable layout.
 */
class MainActivity : AppCompatActivity() {

    data class Module(
        val title: String,
        val icon: String,
        val desc: String,
        val activityClass: Class<*>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val modules = listOf(
            Module("状态检测", "📊", "查看密钥、数据库、环境状态", StatusActivity::class.java),
            Module("聊天列表", "💬", "浏览微信会话列表", com.nous.wxhook.ui.chatlist.ChatListActivity::class.java),
            Module("搜索消息", "🔍", "全文搜索聊天记录", com.nous.wxhook.ui.search.SearchActivity::class.java),
            Module("备份管理", "📦", "全量/增量备份与恢复", com.nous.wxhook.ui.module.ModuleActivity::class.java),
            Module("数据合并", "🔗", "合并多个备份文件", com.nous.wxhook.ui.merge.MergeActivity::class.java),
            Module("云同步", "☁️", "WebDAV / 阿里云盘同步", com.nous.wxhook.ui.cloud.CloudConfigActivity::class.java),
            Module("设置", "⚙️", "WebDAV、备份路径等配置", com.nous.wxhook.ui.settings.SettingsActivity::class.java),
        )

        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this) {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // App header
            addView(M3.title(this@MainActivity, "wxhook"))
            addView(M3.label(this@MainActivity, "微信聊天记录管理工具").apply {
                setPadding(0, M3.dp(this@MainActivity, 4), 0, M3.dp(this@MainActivity, 24))
            })
        }

        modules.forEach { mod ->
            val card = M3.card(this).apply {
                val lp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, M3.dp(this@MainActivity, 12)) }
                layoutParams = lp

                setOnClickListener {
                    startActivity(Intent(this@MainActivity, mod.activityClass))
                }

                // Row: icon | text | arrow
                val row = M3.hLayout(this@MainActivity) {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // Icon
                row.addView(android.widget.TextView(this@MainActivity).apply {
                    text = mod.icon
                    textSize = 28f
                    layoutParams = ViewGroup.LayoutParams(
                        M3.dp(this@MainActivity, 48),
                        M3.dp(this@MainActivity, 48)
                    )
                    gravity = android.view.Gravity.CENTER
                })

                // Text column
                val col = M3.vLayout(this@MainActivity) {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    setPadding(0, 0, 0, 0)
                }
                col.addView(M3.titleMedium(this@MainActivity, mod.title))
                col.addView(M3.label(this@MainActivity, mod.desc))

                row.addView(col)

                // Arrow
                row.addView(android.widget.TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 24f
                    setTextColor(M3.onSurfaceVariant(this@MainActivity))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        M3.dp(this@MainActivity, 32),
                        M3.dp(this@MainActivity, 48)
                    )
                })

                card.addView(row)
            }
            root.addView(card)
        }

        // Version footer
        root.addView(M3.labelSmall(this, "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, M3.dp(this, 16), 0, M3.dp(this, 32))
        })

        scrollView.addView(root)
        setContentView(scrollView)
    }
}
