package com.nous.wxhook.ui.cloud

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.nous.wxhook.service.SyncService
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class CloudConfigActivity : AppCompatActivity() {

    private val viewModel: CloudConfigViewModel by viewModels()
    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private val drivers = listOf("无（关闭同步）", "WebDAV", "阿里云盘", "S3 对象存储")
    // 与 drivers 索引对应
    private val driverKeys = listOf("", "webdav", "aliyundrive", "s3")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(M3.colorSurface(this@CloudConfigActivity))
            setStroke(1, M3.colorOutline(this@CloudConfigActivity))
        }
        elevation = dp(2).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "云同步"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        buildUI()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.statusText.isNotEmpty()) statusText.text = state.statusText
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); viewModel.loadAll() }

    private fun buildUI() {
        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // ═══ 选择驱动 ═══
        val selectCard = cardBg()
        selectCard.addView(TextView(this).apply {
            text = "📡 选择同步驱动"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.onSurface(this@CloudConfigActivity))
        })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@CloudConfigActivity, android.R.layout.simple_spinner_dropdown_item, drivers)
            setPadding(0, dp(4), 0, 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    saveDriverSelection(driverKeys[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        selectCard.addView(spinner)
        root.addView(selectCard)

        // ═══ 状态 ═══
        val statusCard = cardBg()
        statusCard.addView(TextView(this).apply {
            text = "📊 同步状态"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.onSurface(this@CloudConfigActivity))
        })
        statusText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0); minLines = 3 }
        statusCard.addView(statusText)
        root.addView(statusCard)

        // ═══ 操作提示 ═══
        val tipCard = cardBg()
        tipCard.addView(TextView(this).apply {
            textSize = 13f; setTextColor(M3.onSurfaceVariant(this@CloudConfigActivity))
            text = "💡 各驱动的账号配置请在「设置」→「云存储驱动」中填写和测试。"
        })
        root.addView(tipCard)

        // ═══ 同步控制 ═══
        val syncCard = cardBg()
        syncCard.addView(TextView(this).apply {
            text = "▶️ 同步控制"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(M3.onSurface(this@CloudConfigActivity))
        })
        syncCard.addView(MaterialButton(this).apply {
            text = "☁️ 立即同步"; insetTop = 0; insetBottom = 0
            setOnClickListener { SyncService.start(this@CloudConfigActivity) }
        })
        root.addView(syncCard)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)) })

        sv.addView(root)
        setContentView(sv)
    }

    private fun saveDriverSelection(driver: String) {
        try {
            val f = File(filesDir, "settings_config.json")
            val cfg = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            cfg.put("sync_driver", driver)
            f.writeText(cfg.toString())
        } catch (_: Exception) {}
        viewModel.loadAll()
    }

    // 初始化时选中已保存的 driver
    override fun onStart() {
        super.onStart()
        try {
            val cfg = JSONObject(File(filesDir, "settings_config.json").readText())
            val saved = cfg.optString("sync_driver", "")
            val idx = driverKeys.indexOf(saved).coerceAtLeast(0)
            spinner.setSelection(idx)
        } catch (_: Exception) {}
    }
}
