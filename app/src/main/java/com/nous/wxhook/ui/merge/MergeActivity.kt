package com.nous.wxhook.ui.merge

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.db.MergeEngine
import java.io.File

class MergeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "数据合并"; textSize = 20f })

        layout.addView(Button(this).apply {
            text = "合并最近两个备份"
            setOnClickListener { mergeRecent() }
        })

        contentText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(contentText)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun mergeRecent() {
        contentText.text = "需要至少 2 个备份才能合并"
    }
}