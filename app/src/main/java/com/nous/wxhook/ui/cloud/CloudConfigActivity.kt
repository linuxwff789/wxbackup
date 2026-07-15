package com.nous.wxhook.ui.cloud

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.service.SyncService
import kotlinx.coroutines.launch
import java.io.File

class CloudConfigActivity : AppCompatActivity() {

    private val viewModel: CloudConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "云同步配置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BackupHookLocal.init(this)
        buildUI()

        // Observe ViewModel state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Handle toast messages
                    if (state.toastMessage.isNotEmpty()) {
                        Toast.makeText(this@CloudConfigActivity, state.toastMessage, Toast.LENGTH_LONG).show()
                        viewModel.clearToast()
                    }
                    // Update title from test results
                    if (state.testResult.isNotEmpty()) {
                        supportActionBar?.title = state.testResult
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); viewModel.loadAll() }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Header
        root.addView(TextView(this).apply {
            text = "☁️ 云同步"; textSize = 22f
            setTextColor(0xFF6200EE.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })

        // ── Status section ──
        root.addView(cardTitle("📊 状态"))
        val statusCard = card()
        val statusText = TextView(this).apply {
            textSize = 13f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        statusCard.addView(statusText)
        root.addView(statusCard)

        // ── Config section ──
        root.addView(cardTitle("🔑 远端配置"))
        val configCard = card()
        val remotes = viewModel.uiState.value.remotes
        if (remotes.isEmpty()) {
            configCard.addView(TextView(this).apply {
                text = "暂无配置，请添加远端存储"; textSize = 13f
                setPadding(dp(12), dp(16), dp(12), dp(16))
                setTextColor(0xFF9E9E9E.toInt())
            })
        } else {
            for (remote in remotes) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setBackgroundColor(Color.WHITE)
                }
                row.addView(TextView(this).apply {
                    text = "📦 ${remote.name} (${remote.type})"; textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val testBtn = Button(this).apply {
                    text = "测试"; textSize = 12f
                    setTextColor(0xFF6200EE.toInt())
                    setBackgroundColor(Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { viewModel.testRemote(remote.name) }
                }
                row.addView(testBtn)
                configCard.addView(row)
            }
        }
        // Add remote buttons
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        for ((label, provider) in listOf("+ WebDAV" to "webdav", "+ S3" to "s3")) {
            addRow.addView(Button(this).apply {
                text = label; textSize = 13f
                setTextColor(0xFF6200EE.toInt())
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { addRemote(provider) }
            })
        }
        configCard.addView(addRow)
        root.addView(configCard)

        // ── Sync button + schedule ──
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { setMargins(dp(12), 0, dp(12), dp(4)) }
            addView(Button(this@CloudConfigActivity).apply {
                text = "☁️ 立即同步"; textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF6200EE.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 2f).apply { setMargins(0, 0, dp(8), 0) }
                setOnClickListener {
                    SyncService.start(this@CloudConfigActivity)
                }
            })
            val intervalEt = android.widget.EditText(this@CloudConfigActivity).apply {
                textSize = 14f; inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "间隔分"
                gravity = Gravity.CENTER
                setText(runCatching { org.json.JSONObject(File(applicationContext.filesDir, "settings_config.json").readText()) }.getOrDefault(org.json.JSONObject()).optString("sync_interval_min", ""))
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = 4f
                    setStroke(1, 0xFFBDBDBD.toInt())
                }
            }
            addView(intervalEt)
            addView(Button(this@CloudConfigActivity).apply {
                text = "定时"; textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF03A9F4.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(48)).apply { setMargins(dp(8), 0, 0, 0) }
                setOnClickListener {
                    val min = intervalEt.text.toString().trim().toIntOrNull()
                    if (min != null && min > 0) {
                        viewModel.setSyncInterval(min); SyncService.start(this@CloudConfigActivity)
                    }
                }
            })
        })

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)

        // Update status text from ViewModel
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.statusText.isNotEmpty()) {
                        statusText.text = state.statusText
                    }
                }
            }
        }
    }

    private fun addRemote(provider: String) {
        val name = viewModel.addRemote(provider)
        when (provider) {
            "webdav" -> showWebdavDialog(name)
            "s3" -> showS3Dialog(name)
        }
    }

    // ── S3 Dialog ──
    private fun showS3Dialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 16) }
        val provLabels = listOf("AWS S3","Cloudflare R2","MinIO","阿里云OSS","腾讯COS","华为OBS","其他")
        col.addView(TextView(ctx).apply { text = "服务商"; textSize = 13f })
        val pSpin = android.widget.Spinner(ctx)
        pSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, provLabels)
        col.addView(pSpin)
        val ek = android.widget.EditText(ctx).apply { hint = "Access Key ID"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        val sk = android.widget.EditText(ctx).apply { hint = "Secret Access Key"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(TextView(ctx).apply { text = "Access Key ID"; textSize = 13f }); col.addView(ek)
        col.addView(TextView(ctx).apply { text = "Secret Access Key"; textSize = 13f }); col.addView(sk)
        val allRegions = listOf("us-east-1","ap-southeast-1","cn-north-1","oss-cn-hangzhou","ap-beijing","auto")
        col.addView(TextView(ctx).apply { text = "区域"; textSize = 13f })
        val rSpin = android.widget.Spinner(ctx)
        rSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, allRegions)
        col.addView(rSpin)
        col.addView(TextView(ctx).apply { text = "Endpoint（留空自动）"; textSize = 13f })
        val ep = android.widget.EditText(ctx).apply { hint = "留空自动填充"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(ep)
        android.app.AlertDialog.Builder(ctx).setTitle("S3 对象存储").setView(col)
            .setPositiveButton("保存") { _, _ ->
                val pi = pSpin.selectedItemPosition
                val s3Prov = listOf("AWS","Cloudflare","Minio","Alibaba","TencentCOS","HuaweiOBS","Other")[pi]
                val region = rSpin.selectedItem.toString()
                var endpoint = ep.text.toString().trim()
                val ak = ek.text.toString().trim(); val ask = sk.text.toString().trim()
                if (ak.isEmpty() || ask.isEmpty()) return@setPositiveButton
                if (endpoint.isEmpty()) endpoint = viewModel.getS3Endpoint(s3Prov, region)
                viewModel.saveS3Config(name, s3Prov, region, endpoint, ak, ask)
            }.setNegativeButton("取消", null).show()
    }

    // ── WebDAV Dialog ──
    private fun showWebdavDialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 16) }
        col.addView(TextView(ctx).apply { text = "服务类型"; textSize = 13f })
        val vSpin = android.widget.Spinner(ctx)
        vSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("nextcloud","owncloud","sharepoint","fastmail","other"))
        col.addView(vSpin)
        col.addView(TextView(ctx).apply { text = "WebDAV 地址"; textSize = 13f })
        val urlEt = android.widget.EditText(ctx).apply { hint = "https://example.com/dav/"; textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(urlEt)
        col.addView(TextView(ctx).apply { text = "用户名"; textSize = 13f })
        val userEt = android.widget.EditText(ctx).apply { textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(userEt)
        col.addView(TextView(ctx).apply { text = "密码"; textSize = 13f })
        val passEt = android.widget.EditText(ctx).apply { textSize = 14f; setSingleLine(); setPadding(0,4,0,8) }
        col.addView(passEt)
        android.app.AlertDialog.Builder(ctx).setTitle("WebDAV").setView(col)
            .setPositiveButton("保存") { _, _ ->
                var url = urlEt.text.toString().trim()
                val user = userEt.text.toString().trim(); val pass = passEt.text.toString().trim()
                val vendor = vSpin.selectedItem.toString()
                if (url.isEmpty() || user.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http")) url = "https://$url"
                viewModel.saveWebdavConfig(name, url, vendor, user, pass)
            }.setNegativeButton("取消", null).show()
    }

    private fun cardTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(0xFF424242.toInt())
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(6))
    }

    private fun card() = androidx.cardview.widget.CardView(this).apply {
        radius = 8f; cardElevation = 1f; setContentPadding(0, 0, 0, 0)
        setCardBackgroundColor(Color.WHITE)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
