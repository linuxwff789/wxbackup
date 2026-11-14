package com.nous.wxnotes.ui;

@kotlin.Metadata(mv = {1, 9, 0}, k = 2, xi = 48, d1 = {"\u00006\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0002\b\u0007\u001a.\u0010\u0000\u001a\u00020\u00012\u0006\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u00032\u0006\u0010\u0005\u001a\u00020\u00032\f\u0010\u0006\u001a\b\u0012\u0004\u0012\u00020\u00010\u0007H\u0003\u001a \u0010\b\u001a\u00020\u00012\u0006\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\u00032\u0006\u0010\f\u001a\u00020\rH\u0003\u001a\u0018\u0010\u000e\u001a\u00020\u00012\u0006\u0010\t\u001a\u00020\n2\u0006\u0010\f\u001a\u00020\rH\u0003\u001a&\u0010\u000f\u001a\u00020\u00012\u0006\u0010\u0010\u001a\u00020\u00112\u0006\u0010\u0012\u001a\u00020\u00132\f\u0010\u0014\u001a\b\u0012\u0004\u0012\u00020\u00010\u0007H\u0007\u001a\u0010\u0010\u0015\u001a\u00020\u00012\u0006\u0010\f\u001a\u00020\rH\u0003\u001a\u0010\u0010\u0016\u001a\u00020\u00012\u0006\u0010\t\u001a\u00020\nH\u0003\u001a\u0018\u0010\u0017\u001a\u00020\u00032\u0006\u0010\u0018\u001a\u00020\u00132\u0006\u0010\u0019\u001a\u00020\u0013H\u0002\u00a8\u0006\u001a"}, d2 = {"AttachmentRow", "", "icon", "", "title", "subtitle", "onClick", "Lkotlin/Function0;", "MessageContent", "m", "Lcom/nous/wxnotes/data/MessageEntity;", "url", "note", "Lcom/nous/wxnotes/data/NoteEntity;", "MessageRow", "NoteDetailScreen", "vm", "Lcom/nous/wxnotes/NotesViewModel;", "noteId", "", "onBack", "NoteHeader", "VoiceContent", "dayKey", "createTime", "fallback", "notes_debug"})
public final class NoteDetailScreenKt {
    
    @kotlin.OptIn(markerClass = {androidx.compose.material3.ExperimentalMaterial3Api.class, androidx.compose.foundation.layout.ExperimentalLayoutApi.class})
    @androidx.compose.runtime.Composable()
    public static final void NoteDetailScreen(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.NotesViewModel vm, long noteId, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function0<kotlin.Unit> onBack) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void NoteHeader(com.nous.wxnotes.data.NoteEntity note) {
    }
    
    private static final java.lang.String dayKey(long createTime, long fallback) {
        return null;
    }
    
    @androidx.compose.runtime.Composable()
    private static final void MessageRow(com.nous.wxnotes.data.MessageEntity m, com.nous.wxnotes.data.NoteEntity note) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void MessageContent(com.nous.wxnotes.data.MessageEntity m, java.lang.String url, com.nous.wxnotes.data.NoteEntity note) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void AttachmentRow(java.lang.String icon, java.lang.String title, java.lang.String subtitle, kotlin.jvm.functions.Function0<kotlin.Unit> onClick) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void VoiceContent(com.nous.wxnotes.data.MessageEntity m) {
    }
}