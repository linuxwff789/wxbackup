package com.nous.wxnotes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nous.wxnotes.data.NoteEntity
import com.nous.wxnotes.data.NotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.io.File

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    val repository: NotesRepository = (app as NotesApp).repository

    private val _query = MutableStateFlow("")

    val notes: StateFlow<List<NoteEntity>> = _query
        .debounce(120)
        .flatMapLatest { repository.observeNotes(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun observeNote(id: Long) = repository.observeNote(id)
    fun observeMessages(id: Long) = repository.observeMessages(id)
    fun observeTags(id: Long) = repository.observeTags(id)
    fun observeAllTags() = repository.observeAllTags()
    fun observeNotesWithTag(tagId: Long) = repository.observeNotesWithTag(tagId)

    suspend fun createNote(title: String) = repository.createManualNote(title)
    suspend fun rename(id: Long, title: String) = repository.renameNote(id, title)
    suspend fun updateBody(note: NoteEntity, body: String) = repository.updateNote(note.copy(body = body))
    suspend fun delete(id: Long) = repository.deleteNote(id)
    suspend fun deleteNotes(ids: List<Long>) = repository.deleteNotes(ids)
    suspend fun setArchived(id: Long, archived: Boolean) = repository.setArchived(id, archived)
    suspend fun setPinned(id: Long, pinned: Boolean) = repository.setPinned(id, pinned)
    suspend fun addTag(id: Long, name: String) = repository.addTag(id, name)
    suspend fun removeTag(id: Long, tagId: Long) = repository.removeTag(id, tagId)
    suspend fun export(id: Long): File? = repository.exportMarkdown(id)
    suspend fun tagsOf(id: Long) = repository.tagsOf(id)
}
