package com.nous.wxnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nous.wxnotes.NotesViewModel

private sealed interface Route {
    data object Notes : Route
    data class Detail(val noteId: Long) : Route
    data object Settings : Route
}

@Composable
fun AppRoot(initialNoteId: Long, vm: NotesViewModel = viewModel()) {
    var route by remember {
        mutableStateOf<Route>(
            if (initialNoteId > 0) Route.Detail(initialNoteId) else Route.Notes
        )
    }

    // 返回键先回到笔记列表，而不是退出 App；在根页面才交给系统处理
    BackHandler(enabled = route !is Route.Notes) {
        route = Route.Notes
    }

    when (val r = route) {
        Route.Notes -> NotesScreen(
            vm = vm,
            onOpenNote = { route = Route.Detail(it) },
            onOpenSettings = { route = Route.Settings }
        )

        is Route.Detail -> NoteDetailScreen(
            vm = vm,
            noteId = r.noteId,
            onBack = { route = Route.Notes }
        )

        Route.Settings -> SettingsScreen(
            onBack = { route = Route.Notes }
        )
    }
}
