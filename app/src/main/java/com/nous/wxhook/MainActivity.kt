package com.nous.wxhook

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        layout.addView(TextView(this).apply {
            text = "wxhook"
            textSize = 28f
            setTextColor(0xFF6200EE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        val modules = listOf(
            "📊 状态检测" to "com.nous.wxhook.ui.status.StatusActivity",
            "💬 聊天列表" to "com.nous.wxhook.ui.chatlist.ChatListActivity",
            "🔍 搜索" to "com.nous.wxhook.ui.search.SearchActivity",
            "📦 备份管理" to "com.nous.wxhook.ui.module.ModuleActivity",
            "🔄 模块入口" to "com.nous.wxhook.ui.module.ModuleActivity",
            "🔗 数据合并" to "com.nous.wxhook.ui.merge.MergeActivity",
            "⚙️ 设置" to "com.nous.wxhook.ui.settings.SettingsActivity",
        )

        modules.forEach { (label, cls) ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
                setOnClickListener {
                    try { startActivity(Intent(this@MainActivity, Class.forName(cls))) }
                    catch (e: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, "功能开发中", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            layout.addView(btn)
        }

        layout.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 12f
            setTextColor(0x8A000000.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })

        setContentView(layout)
    }
}