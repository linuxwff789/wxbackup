package com.nous.wxnotes.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nous.wxnotes.data.WebDavBackup
import com.nous.wxnotes.receive.BackupWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import com.nous.wxnotes.NotesApp
import com.nous.wxnotes.data.NotesRepository
import com.nous.wxshared.ForwardProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("wxnotes", Context.MODE_PRIVATE)
    }
    var skipMediaScan by remember {
        mutableStateOf(prefs.getBoolean(NotesRepository.KEY_SKIP_MEDIA_SCAN, true))
    }
    val scope = rememberCoroutineScope()
    var davUrl by remember { mutableStateOf(prefs.getString(WebDavBackup.KEY_URL, "").orEmpty()) }
    var davUser by remember { mutableStateOf(prefs.getString(WebDavBackup.KEY_USER, "").orEmpty()) }
    var davPass by remember { mutableStateOf(prefs.getString(WebDavBackup.KEY_PASS, "").orEmpty()) }
    var davDir by remember { mutableStateOf(prefs.getString(WebDavBackup.KEY_DIR, WebDavBackup.DEFAULT_DIR).orEmpty()) }
    var davKeep by remember { mutableStateOf(prefs.getInt(WebDavBackup.KEY_KEEP, WebDavBackup.DEFAULT_KEEP).toString()) }
    var davAuto by remember { mutableStateOf(prefs.getBoolean(WebDavBackup.KEY_AUTO, false)) }
    var davBusy by remember { mutableStateOf(false) }
    var davMsg by remember { mutableStateOf("") }
    var davLast by remember { mutableStateOf(prefs.getLong(WebDavBackup.KEY_LAST, 0L)) }
    fun saveDav() {
        prefs.edit()
            .putString(WebDavBackup.KEY_URL, davUrl.trim())
            .putString(WebDavBackup.KEY_USER, davUser.trim())
            .putString(WebDavBackup.KEY_PASS, davPass)
            .putString(WebDavBackup.KEY_DIR, davDir.trim().ifBlank { WebDavBackup.DEFAULT_DIR })
            .putInt(WebDavBackup.KEY_KEEP, davKeep.toIntOrNull()?.coerceIn(1, 100) ?: WebDavBackup.DEFAULT_KEEP)
            .apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("媒体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("跳过媒体扫描", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在媒体目录放 .nomedia，转发的图片/视频不会出现在系统相册和图库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = skipMediaScan,
                    onCheckedChange = { checked ->
                        skipMediaScan = checked
                        prefs.edit().putBoolean(NotesRepository.KEY_SKIP_MEDIA_SCAN, checked).apply()
                        NotesApp.instance.repository.applyMediaScanPolicy()
                    }
                )
            }

            HorizontalDivider()

            Text("云备份（WebDAV）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "把笔记数据库和媒体打成一个 zip 上传，按保留份数自动清理旧包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = davUrl,
                onValueChange = { davUrl = it; saveDav() },
                singleLine = true,
                label = { Text("服务器地址（如 https://dav.example.com/dav/）") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = davUser,
                onValueChange = { davUser = it; saveDav() },
                singleLine = true,
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = davPass,
                onValueChange = { davPass = it; saveDav() },
                singleLine = true,
                label = { Text("密码 / 应用密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = davDir,
                    onValueChange = { davDir = it; saveDav() },
                    singleLine = true,
                    label = { Text("远端目录") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = davKeep,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) { davKeep = it; saveDav() } },
                    singleLine = true,
                    label = { Text("保留份数") },
                    modifier = Modifier.width(110.dp)
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("每日自动备份", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "每天在后台跑一次（需联网，失败会自动重试）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = davAuto,
                    onCheckedChange = { on ->
                        davAuto = on
                        prefs.edit().putBoolean(WebDavBackup.KEY_AUTO, on).apply()
                        val wm = WorkManager.getInstance(context)
                        if (on) {
                            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
                            wm.enqueueUniquePeriodicWork(
                                BackupWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req
                            )
                        } else {
                            wm.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
                        }
                    }
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(enabled = !davBusy, onClick = {
                    saveDav()
                    val cfg = WebDavBackup.loadConfig(context)
                    if (!cfg.isValid) {
                        davMsg = "请先填写服务器地址和用户名"
                        return@TextButton
                    }
                    davBusy = true
                    davMsg = "连接中…"
                    scope.launch {
                        val r = WebDavBackup.testConnection(cfg)
                        davMsg = r.fold({ "连接成功" }, { "连接失败：${it.message}" })
                        davBusy = false
                    }
                }) { Text("测试连接") }
                TextButton(enabled = !davBusy, onClick = {
                    saveDav()
                    val cfg = WebDavBackup.loadConfig(context)
                    if (!cfg.isValid) {
                        davMsg = "请先填写服务器地址和用户名"
                        return@TextButton
                    }
                    davBusy = true
                    davMsg = "准备备份…"
                    scope.launch {
                        val r = WebDavBackup.runBackup(context, cfg) { p -> davMsg = p.stage }
                        r.fold(
                            { davLast = System.currentTimeMillis(); davMsg = "备份完成：$it" },
                            { davMsg = "备份失败：${it.message}" }
                        )
                        davBusy = false
                    }
                }) { Text(if (davBusy) "处理中…" else "立即备份") }
                Spacer(Modifier.weight(1f))
                Text(
                    if (davLast > 0) "上次：${formatFull(davLast)}" else "还没备份过",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (davMsg.isNotBlank()) {
                Text(
                    davMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (davMsg.contains("失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            Text("权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PermissionRow(
                title = "所有文件访问权限",
                desc = "用于读取插件复制到 Download/wxfwd 的图片、语音、视频",
                granted = PermissionHelper.hasStorage(context),
                action = "去授权",
                onClick = { PermissionHelper.requestAllFiles(context) }
            )
            PermissionRow(
                title = "通知权限",
                desc = "保存完成后提醒（Android 13+）",
                granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED,
                action = "去授权",
                onClick = { PermissionHelper.requestAllFiles(context) }
            )

            HorizontalDivider()

            Text("插件接入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "1. LSPosed 中启用 wxhook 模块，作用域勾选微信\n" +
                    "2. 微信聊天里长按消息 →「保存到笔记」\n" +
                    "3. 多选消息后 →「保存所选到笔记」",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "第三方 App 接入",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                buildString {
                    appendLine("Action: ${ForwardProtocol.ACTION_RECEIVE_BATCH}")
                    appendLine("Extra: ${ForwardProtocol.EXTRA_BATCH_JSON}（JSON 字符串）")
                    appendLine("单批 ≤ ${ForwardProtocol.MAX_MESSAGES_PER_BATCH} 条")
                    append("Outbox: ${ForwardProtocol.DEFAULT_OUTBOX_DIR}/<batchId>.json")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            HorizontalDivider()

            Text("存储位置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    appendLine("笔记数据库：应用私有目录（Room）")
                    appendLine("媒体：Download/wxnotes/media/<笔记ID>/（默认 .nomedia，不进相册）")
                    append("导出：${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/wxnotes/exports/")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "wxnotes 1.0 · 协议 v${ForwardProtocol.VERSION}\n消息只保存在本机，不上传任何服务器。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    action: String,
    onClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Text(
                "已授权",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
