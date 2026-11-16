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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var window by remember {
        mutableStateOf(
            prefs.getLong(NotesRepository.KEY_AGGREGATE_WINDOW, NotesRepository.DEFAULT_AGGREGATE_WINDOW).toString()
        )
    }
    var saved by remember { mutableStateOf(false) }
    var skipMediaScan by remember {
        mutableStateOf(prefs.getBoolean(NotesRepository.KEY_SKIP_MEDIA_SCAN, true))
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
            Text("消息聚合", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "同一会话在窗口期内连续转发，会自动追加到同一篇笔记。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = window,
                    onValueChange = {
                        if (it.length <= 4 && it.all(Char::isDigit)) {
                            window = it
                            saved = false
                        }
                    },
                    singleLine = true,
                    label = { Text("窗口（分钟）") },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val v = window.toLongOrNull()?.coerceIn(1, 1440) ?: NotesRepository.DEFAULT_AGGREGATE_WINDOW
                    prefs.edit().putLong(NotesRepository.KEY_AGGREGATE_WINDOW, v).apply()
                    window = v.toString()
                    saved = true
                }) { Text(if (saved) "已保存" else "保存") }
            }

            HorizontalDivider()

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
                    appendLine("媒体：应用私有 files/media/<笔记ID>/")
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
