package com.nous.wxnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.wxnotes.NotesViewModel
import com.nous.wxnotes.data.NoteEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    vm: NotesViewModel,
    onOpenNote: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var showNewDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("微信笔记", fontWeight = FontWeight.Bold)
                        Text(
                            "转发消息自动归档 · 本地存储",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建笔记")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = {
                    search = it
                    vm.setQuery(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("搜索笔记、消息、联系人") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp)
            )

            if (notes.isEmpty()) {
                EmptyState(search.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(note = note, onClick = { onOpenNote(note.id) })
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建笔记") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("标题") }
                )
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    val t = title.trim()
                    showNewDialog = false
                    scope.launch { onOpenNote(vm.createNote(t)) }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun NoteCard(note: NoteEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatTime(note.lastMessageTime.takeIf { it > 0 } ?: note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (note.preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    note.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.talkerTitle.isNotBlank()) {
                    Text(
                        note.talkerTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "${note.messageCount} 条消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note.pinned) {
                    Spacer(Modifier.width(8.dp))
                    Badge("置顶")
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    val context = LocalContext.current
    val needStorage = !PermissionHelper.hasStorage(context)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (searching) "没有匹配的笔记" else "还没有笔记",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (searching) "换个关键词试试" else
                "在微信里长按消息 →「保存到笔记」，\n或长按多选若干条后批量保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (needStorage) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { PermissionHelper.requestAllFiles(context) }) {
                Text("授予文件访问权限（读取插件保存的媒体）")
            }
        }
        if (!PermissionHelper.hasStorage(context)) {
            Spacer(Modifier.height(8.dp))
            Text(
                "未授权时图片/语音/视频无法导入，只保存文字。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
