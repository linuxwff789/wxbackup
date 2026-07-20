package com.nous.wxhook.ui.cloud

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import com.nous.wxhook.service.SyncService
import com.nous.wxhook.ui.M3
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class CloudConfigActivity : AppCompatActivity() {

    private val viewModel: CloudConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "云同步配置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BackupHookLocal.init(this)

        buildUI()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.toastMessage.isNotEmpty()) {
                        android.widget.Toast.makeText(
                            this@CloudConfigActivity,
                            state.toastMessage,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearToast()
                    }
                    if (state.testResult.isNotEmpty()) {
                        supportActionBar?.title = state.testResult
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAll()
    }

    private fun buildUI() {
        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        // ── Status card ──
        val statusCard = M3.card(this)
        statusCard.addView(M3.titleMedium(this, "📊 同步状态"))
        val statusText = M3.monoBody(this).apply {
            setPadding(0, M3.dp(this@CloudConfigActivity, 8), 0, 0)
            textSize = 13f
            minLines = 4
        }
        statusCard.addView(statusText)
        root.addView(statusCard)
        root.addView(M3.sp(this, 8))

        // ── Remote config card ──
        val configCard = M3.card(this)
        configCard.addView(M3.titleMedium(this, "🔑 远端配置"))

        val remotes = viewModel.uiState.value.remotes
        if (remotes.isEmpty()) {
            configCard.addView(M3.label(this, "暂无配置，请添加远端存储").apply {
                setPadding(0, M3.dp(this@CloudConfigActivity, 8), 0, M3.dp(this@CloudConfigActivity, 8))
            })
        } else {
            for (remote in remotes) {
                val row = M3.hLayout(this)
                row.addView(M3.body(this, "📦 ${remote.name} (${remote.type})").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(M3.textButton(this, "测试") {
                    viewModel.testRemote(remote.name)
                })
                configCard.addView(row)
                configCard.addView(M3.divider(this))
            }
        }

        // Add provider buttons
        configCard.addView(M3.sp(this, 8))

        val addRow1 = M3.hLayout(this)
        addRow1.addView(M3.outlinedButton(this, "+ WebDAV") {
            addRemote("webdav")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, M3.dp(this@CloudConfigActivity, 48), 1f)
        })
        addRow1.addView(M3.sp(this, 12))
        addRow1.addView(M3.outlinedButton(this, "+ S3 对象存储") {
            addRemote("s3")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, M3.dp(this@CloudConfigActivity, 48), 1f)
        })
        configCard.addView(addRow1)

        configCard.addView(M3.sp(this, 8))
        val aliyunBtn = M3.filledButton(this, "☁️ + 阿里云盘 (OpenList)") {
            addRemote("aliyundrive")
        }
        configCard.addView(aliyunBtn)
        root.addView(configCard)
        root.addView(M3.sp(this, 8))

        // ── Sync control card ──
        val syncCard = M3.card(this)
        syncCard.addView(M3.titleMedium(this, "▶️ 同步控制"))

        val syncBtn = M3.filledButton(this, "☁️ 立即同步") {
            SyncService.start(this@CloudConfigActivity)
        }
        syncCard.addView(syncBtn)

        syncCard.addView(M3.sp(this, 12))

        // Interval setting
        val intervalLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputStyle
        ).apply {
            hint = "自动同步间隔（分钟）"
            helperText = "留空=手动同步"
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val intervalInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            val cfg = runCatching {
                JSONObject(File(filesDir, "settings_config.json").readText())
            }.getOrDefault(JSONObject())
            setText(cfg.optString("sync_interval_min", ""))
        }
        intervalLayout.addView(intervalInput)

        val intervalRow = M3.hLayout(this)
        intervalRow.addView(intervalLayout.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        intervalRow.addView(M3.sp(this, 12))
        intervalRow.addView(M3.tonalButton(this, "定时") {
            val min = intervalInput.text?.toString()?.trim()?.toIntOrNull()
            if (min != null && min > 0) {
                viewModel.setSyncInterval(min)
                SyncService.start(this@CloudConfigActivity)
            } else {
                android.widget.Toast.makeText(this@CloudConfigActivity, "请输入有效分钟数", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                M3.dp(this@CloudConfigActivity, 48)
            )
        })
        syncCard.addView(intervalRow)
        root.addView(syncCard)
        root.addView(M3.sp(this, 16))

        scrollView.addView(root)
        setContentView(scrollView)

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
            "aliyundrive" -> showAliyundriveDialog(name)
        }
    }

    // ── S3 Dialog ──
    private fun showS3Dialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3.dp(ctx, 24), M3.dp(ctx, 16), M3.dp(ctx, 24), M3.dp(ctx, 16))
        }
        val provLabels = listOf("AWS S3", "Cloudflare R2", "MinIO", "阿里云OSS", "腾讯COS", "华为OBS", "其他")
        col.addView(M3.label(ctx, "服务商"))
        val pSpin = android.widget.Spinner(ctx)
        pSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, provLabels)
        col.addView(pSpin)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "Access Key ID"))
        val ek = TextInputEditText(ctx).apply { hint = "Access Key ID" }
        col.addView(ek)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "Secret Access Key"))
        val sk = TextInputEditText(ctx).apply { hint = "Secret Access Key" }
        col.addView(sk)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "区域"))
        val rSpin = android.widget.Spinner(ctx)
        rSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("us-east-1", "ap-southeast-1", "cn-north-1", "oss-cn-hangzhou", "ap-beijing", "auto"))
        col.addView(rSpin)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "Endpoint（留空自动）"))
        val ep = TextInputEditText(ctx).apply { hint = "留空自动填充" }
        col.addView(ep)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("S3 对象存储")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                val pi = pSpin.selectedItemPosition
                val s3Prov = listOf("AWS", "Cloudflare", "Minio", "Alibaba", "TencentCOS", "HuaweiOBS", "Other")[pi]
                val region = rSpin.selectedItem.toString()
                var endpoint = ep.text?.toString()?.trim() ?: ""
                val ak = ek.text?.toString()?.trim() ?: ""
                val ask = sk.text?.toString()?.trim() ?: ""
                if (ak.isEmpty() || ask.isEmpty()) return@setPositiveButton
                if (endpoint.isEmpty()) endpoint = viewModel.getS3Endpoint(s3Prov, region)
                viewModel.saveS3Config(name, s3Prov, region, endpoint, ak, ask)
                viewModel.loadAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── WebDAV Dialog ──
    private fun showWebdavDialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3.dp(ctx, 24), M3.dp(ctx, 16), M3.dp(ctx, 24), M3.dp(ctx, 16))
        }
        col.addView(M3.label(ctx, "服务类型"))
        val vSpin = android.widget.Spinner(ctx)
        vSpin.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("nextcloud", "owncloud", "sharepoint", "fastmail", "other"))
        col.addView(vSpin)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "WebDAV 地址"))
        val urlEt = TextInputEditText(ctx).apply { hint = "https://example.com/dav/" }
        col.addView(urlEt)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "用户名"))
        val userEt = TextInputEditText(ctx)
        col.addView(userEt)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "密码"))
        val passEt = TextInputEditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(passEt)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "远端目录（留空=根目录）"))
        val pathEt = TextInputEditText(ctx).apply { hint = "wxhook-backup" }
        col.addView(pathEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("WebDAV")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                var url = urlEt.text?.toString()?.trim() ?: ""
                val user = userEt.text?.toString()?.trim() ?: ""
                val pass = passEt.text?.toString()?.trim() ?: ""
                val vendor = vSpin.selectedItem.toString()
                val remotePath = pathEt.text?.toString()?.trim()?.ifEmpty { "wxhook-backup" } ?: "wxhook-backup"
                if (url.isEmpty() || user.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http")) url = "https://$url"
                viewModel.saveWebdavConfig(name, url, vendor, user, pass, remotePath)
                viewModel.loadAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── AliyunDrive Dialog ──
    private fun showAliyundriveDialog(name: String) {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3.dp(ctx, 24), M3.dp(ctx, 16), M3.dp(ctx, 24), M3.dp(ctx, 16))
        }
        col.addView(M3.titleMedium(ctx, "阿里云盘配置"))
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "Refresh Token"))
        val tokenEt = TextInputEditText(ctx).apply { hint = "eyJ0eXAiOiJKV1Qi..." }
        col.addView(tokenEt)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "API 地址（默认即可）"))
        val apiEt = TextInputEditText(ctx).apply { setText("https://api.oplist.org/alicloud/renewapi") }
        col.addView(apiEt)
        col.addView(M3.sp(ctx, 8))

        col.addView(M3.label(ctx, "远端目录（留空=wxhook-backup）"))
        val pathEt = TextInputEditText(ctx).apply { hint = "wxhook-backup" }
        col.addView(pathEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("阿里云盘")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                val token = tokenEt.text?.toString()?.trim() ?: ""
                if (token.isEmpty()) return@setPositiveButton
                val apiUrl = apiEt.text?.toString()?.trim()?.ifEmpty { "https://api.oplist.org/alicloud/renewapi" } ?: "https://api.oplist.org/alicloud/renewapi"
                val remotePath = pathEt.text?.toString()?.trim()?.ifEmpty { "wxhook-backup" } ?: "wxhook-backup"
                viewModel.saveAliyundriveConfig(name, token, apiUrl, "root", remotePath)
                viewModel.loadAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
