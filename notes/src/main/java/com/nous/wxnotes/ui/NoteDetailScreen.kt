package com.nous.wxnotes.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nous.wxnotes.NotesViewModel
import com.nous.wxnotes.data.MessageEntity
import com.nous.wxnotes.data.NoteEntity
import com.nous.wxshared.ForwardProtocol
import com.nous.wxshared.WxMessageParser
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(vm: NotesViewModel, noteId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note by vm.observeNote(noteId).collectAsStateWithLifecycle(initialValue = null)
    val messages by vm.observeMessages(noteId).collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by vm.observeTags(noteId).collectAsStateWithLifecycle(initialValue = emptyList())

    var menuOpen by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(false) }
    var editBody by remember { mutableStateOf(false) }
    var addTagDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        note?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; editTitle = true }
                        )
                        DropdownMenuItem(
                            text = { Text("添加标签") },
                            leadingIcon = { Icon(Icons.Default.Label, null) },
                            onClick = { menuOpen = false; addTagDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(if (note?.pinned == true) "取消置顶" else "置顶") },
                            leadingIcon = { Icon(Icons.Default.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                note?.let { n -> scope.launch { vm.setPinned(n.id, !n.pinned) } }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出 Markdown / Obsidian") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val f = vm.export(noteId)
                                    if (f != null) {
                                        Toast.makeText(context, "已导出：${f.absolutePath}", Toast.LENGTH_LONG).show()
                                        shareLocalFile(context, f)
                                    } else {
                                        Toast.makeText(context, "导出失败（无存储权限或没有内容）", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("归档") },
                            leadingIcon = { Icon(Icons.Default.Archive, null) },
                            onClick = {
                                menuOpen = false
                                scope.launch { vm.setArchived(noteId, true); onBack() }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除笔记") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; confirmDelete = true }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        val n = note
        if (n == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { NoteHeader(n) }

            if (n.body.isNotBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth().clickable { editBody = true }
                    ) {
                        Text(
                            n.body,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                item {
                    TextButton(onClick = { editBody = true }) {
                        Text("添加笔记正文…")
                    }
                }
            }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = {
                                scope.launch { vm.removeTag(noteId, tag.id) }
                            },
                            label = { Text("#${tag.name}") },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                    AssistChip(
                        onClick = { addTagDialog = true },
                        label = { Text("+ 标签") },
                        leadingIcon = { Icon(Icons.Default.Label, null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        "  聊天记录 · ${messages.size} 条  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
            }

            val groups = messages.groupBy { dayKey(it.createTime, n.createdAt) }
            groups.forEach { (day, msgs) ->
                item(key = "day-$day") {
                    Text(
                        day,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(msgs, key = { it.id }) { m ->
                    MessageRow(
                        m = m,
                        note = n,
                        onImageClick = { path ->
                            val imgs = messages
                                .filter { it.mediaKind == ForwardProtocol.MEDIA_IMAGE && it.mediaPath.isNotBlank() }
                                .map { it.mediaPath }
                            val idx = imgs.indexOf(path).coerceAtLeast(0)
                            previewImages = imgs
                            previewIndex = idx
                        },
                        onDelete = { scope.launch { vm.deleteMessage(noteId, m.id) } }
                    )
                }
            }
        }
    }

    if (editTitle) {
        var value by remember(note?.title) { mutableStateOf(note?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editTitle = false },
            title = { Text("重命名笔记") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    editTitle = false
                    scope.launch { vm.rename(noteId, value.trim()) }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editTitle = false }) { Text("取消") } }
        )
    }

    if (editBody) {
        var value by remember(note?.body) { mutableStateOf(note?.body.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editBody = false },
            title = { Text("笔记正文") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("写下你的总结、想法…") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editBody = false
                    note?.let { v -> scope.launch { vm.updateBody(v, value) } }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editBody = false }) { Text("取消") } }
        )
    }

    if (addTagDialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addTagDialog = false },
            title = { Text("添加标签") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("标签名（可空格分隔多个）") }
                )
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    addTagDialog = false
                    scope.launch {
                        value.split(' ', '，', ',')
                            .filter { it.isNotBlank() }
                            .forEach { vm.addTag(noteId, it.trim()) }
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addTagDialog = false }) { Text("取消") } }
        )
    }

    DisposableEffect(Unit) {
        onDispose { VoicePlayback.stop() }
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewDialog(
            images = previewImages,
            startIndex = previewIndex,
            onDismiss = { previewImages = emptyList() }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这篇笔记？") },
            text = { Text("笔记和其中 ${messages.size} 条消息会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { vm.delete(noteId); onBack() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun NoteHeader(note: NoteEntity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (note.talkerTitle.isNotBlank()) {
            Text(
                "来自微信 · ${note.talkerTitle}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            "创建 ${formatFull(note.createdAt)} · 最后更新 ${formatFull(note.updatedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun dayKey(createTime: Long, fallback: Long): String {
    val ms = if (createTime > 0) createTime * 1000 else fallback
    return SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault()).format(Date(ms))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    m: MessageEntity,
    note: NoteEntity,
    onImageClick: (String) -> Unit,
    onDelete: () -> Unit
) {
    val mine = m.isSend
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(m.id) { mutableStateOf(false) }
    var confirmDelete by remember(m.id) { mutableStateOf(false) }
    var videoFullscreen by remember(m.id) { mutableStateOf(false) }
    val parsed = remember(m.id) {
        if (m.rawXml.isNotBlank()) WxMessageParser.parse(m.type, m.rawXml, m.subType) else null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(modifier = Modifier.widthIn(max = 320.dp)) {
            Text(
                buildString {
                    append(
                        when {
                            mine -> "我"
                            m.senderName.isNotBlank() -> m.senderName
                            note.talker.endsWith("@chatroom") -> m.sender.ifBlank { "群成员" }
                            else -> note.talkerTitle
                        }
                    )
                    val t = m.createTime * 1000
                    if (t > 0) append("  ").append(formatTime(t))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Box {
                Surface(
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            menuOpen = true
                            true
                        }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    // 视频要有画面贴边的效果：气泡内不留 padding，由视频自己按比例撑满
                    val videoFullBleed = m.mediaKind == ForwardProtocol.MEDIA_VIDEO && m.mediaPath.isNotBlank()
                    Column(modifier = Modifier.padding(if (videoFullBleed) 0.dp else 10.dp)) {
                        MessageContent(m, parsed?.url.orEmpty(), note, onImageClick)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (m.mediaPath.isNotBlank() && m.mediaKind != ForwardProtocol.MEDIA_NONE) {
                        DropdownMenuItem(
                            text = { Text("分享") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                menuOpen = false
                                shareMedia(context, m.mediaPath, m.mediaKind, m.fileName)
                            }
                        )
                    } else if (m.text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("分享文字") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                menuOpen = false
                                shareText(context, m.text)
                            }
                        )
                    }
                    if (m.text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("复制文字") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = {
                            menuOpen = false
                            confirmDelete = true
                        }
                    )
                }
            }
            // 视频的操作条放在气泡外面，气泡才能刚好贴合画面
            if (m.mediaKind == ForwardProtocol.MEDIA_VIDEO && m.mediaPath.isNotBlank() &&
                File(m.mediaPath).let { it.isFile && it.length() >= MIN_VIDEO_BYTES }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        listOfNotNull(
                            WxMessageParser.formatSize(m.fileSize).takeIf { it.isNotBlank() },
                            WxMessageParser.formatDuration(m.durationMs).takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { videoFullscreen = true }) {
                        Text("全屏", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        if (m.mediaPath.isNotBlank()) openLocalFile(context, m.mediaPath, "video", m.fileName)
                    }) { Text("外部打开", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }

    if (videoFullscreen) {
        FullscreenVideoDialog(path = m.mediaPath, onDismiss = { videoFullscreen = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条消息？") },
            text = { Text("它的媒体文件也会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun MessageContent(
    m: MessageEntity,
    url: String,
    note: NoteEntity,
    onImageClick: (String) -> Unit
) {
    val context = LocalContext.current
    // 媒体还不在本地（插件正在下载原图/附件，或大文件还在传输）
    if (m.mediaPath.isBlank() && m.mediaKind.isNotBlank() &&
        m.mediaKind != ForwardProtocol.MEDIA_EMOJI
    ) {
        PendingMedia(m)
        return
    }
    when (m.mediaKind) {
        ForwardProtocol.MEDIA_IMAGE -> {
            if (m.mediaPath.isNotBlank()) {
                AsyncImage(
                    model = File(m.mediaPath),
                    contentDescription = m.text,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onImageClick(m.mediaPath) },
                    contentScale = ContentScale.Fit
                )
                if (m.text.isNotBlank() && m.text != "[图片]") {
                    Spacer(Modifier.height(4.dp))
                    Text(m.text, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(m.text.ifBlank { "[图片]" })
            }
        }

        ForwardProtocol.MEDIA_VOICE -> VoiceContent(m)

        ForwardProtocol.MEDIA_VIDEO -> VideoContent(m)

        ForwardProtocol.MEDIA_FILE -> AttachmentRow(
            icon = "📄", title = m.fileName.ifBlank { m.text.ifBlank { "[文件]" } },
            subtitle = WxMessageParser.formatSize(m.fileSize),
            onClick = { if (m.mediaPath.isNotBlank()) openLocalFile(context, m.mediaPath, "file", m.fileName) }
        )

        else -> {
            if (url.isNotBlank()) {
                Column(
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                            )
                        }
                    }
                ) {
                    Text(m.text)
                    Text(
                        url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(m.text.ifBlank { WxMessageParser.typeName(m.type) })
            }
        }
    }
}

@Composable
private fun AttachmentRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PendingMedia(m: MessageEntity) {
    var elapsed by remember(m.id) { mutableIntStateOf(0) }
    LaunchedEffect(m.id) {
        while (elapsed < 180) {
            kotlinx.coroutines.delay(1000)
            elapsed++
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (elapsed < 180) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                when (m.mediaKind) {
                    ForwardProtocol.MEDIA_IMAGE -> "正在下载原图…"
                    ForwardProtocol.MEDIA_VIDEO -> "正在下载视频…"
                    ForwardProtocol.MEDIA_FILE -> "正在下载附件…"
                    ForwardProtocol.MEDIA_VOICE -> "正在下载语音…"
                    else -> "媒体处理中…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "媒体未下载（可在微信里重新保存一次）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (m.text.isNotBlank() && m.text != "[图片]" && m.text != "[视频]") {
            Spacer(Modifier.height(2.dp))
            Text(m.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 只负责画面：按原始比例测量，宽度由视频自己决定（父容器 wrapContent），气泡才能贴合。 */
@Composable
private fun VideoContent(m: MessageEntity) {
    var failed by remember(m.id) { mutableStateOf(false) }
    val missingOrBroken = remember(m.id, m.mediaPath) {
        val f = File(m.mediaPath)
        !f.isFile || f.length() < MIN_VIDEO_BYTES
    }

    if (missingOrBroken || failed) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "视频无法播放",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "文件没下载完整或已过期（微信服务器会清理旧视频），可以在微信里重新保存一次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        return
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            AspectVideoView(ctx).apply {
                setVideoPath(m.mediaPath)
                val mc = android.widget.MediaController(ctx)
                mc.setAnchorView(this)
                setMediaController(mc)
            }
        },
        update = { v -> v.onError = { _, _ -> failed = true } },
        modifier = Modifier
            .heightIn(max = 340.dp)
            .wrapContentSize(Alignment.Center)
    )
}

/** 小于这个大小基本是微信留下的占位/损坏文件。 */
private const val MIN_VIDEO_BYTES = 50L * 1024

@Composable
private fun FullscreenVideoDialog(path: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    AspectVideoView(ctx).apply {
                        setVideoPath(path)
                        val mc = android.widget.MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        start()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

@Composable
private fun VoiceContent(m: MessageEntity) {
    val context = LocalContext.current
    val playing = VoicePlayback.playingId == m.id
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                if (m.mediaPath.isBlank()) {
                    Toast.makeText(context, "语音文件未导出", Toast.LENGTH_SHORT).show()
                    return@clickable
                }
                VoicePlayback.toggle(context, m.id, m.mediaPath)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (playing) "停止" else "播放",
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            buildString {
                append(if (m.text.isNotBlank() && !m.text.startsWith("[语音]")) m.text else "语音")
                val d = WxMessageParser.formatDuration(m.durationMs)
                if (d.isNotBlank()) append("  ").append(d)
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
