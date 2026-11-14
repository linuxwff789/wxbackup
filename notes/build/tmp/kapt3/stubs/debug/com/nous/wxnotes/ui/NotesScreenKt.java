package com.nous.wxnotes.ui;

@kotlin.Metadata(mv = {1, 9, 0}, k = 2, xi = 48, d1 = {"\u00008\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\u0010\t\n\u0002\b\u0002\u001a\u0010\u0010\u0000\u001a\u00020\u00012\u0006\u0010\u0002\u001a\u00020\u0003H\u0003\u001a\u0010\u0010\u0004\u001a\u00020\u00012\u0006\u0010\u0005\u001a\u00020\u0006H\u0003\u001a\u001e\u0010\u0007\u001a\u00020\u00012\u0006\u0010\b\u001a\u00020\t2\f\u0010\n\u001a\b\u0012\u0004\u0012\u00020\u00010\u000bH\u0003\u001a2\u0010\f\u001a\u00020\u00012\u0006\u0010\r\u001a\u00020\u000e2\u0012\u0010\u000f\u001a\u000e\u0012\u0004\u0012\u00020\u0011\u0012\u0004\u0012\u00020\u00010\u00102\f\u0010\u0012\u001a\b\u0012\u0004\u0012\u00020\u00010\u000bH\u0007\u00a8\u0006\u0013"}, d2 = {"Badge", "", "text", "", "EmptyState", "searching", "", "NoteCard", "note", "Lcom/nous/wxnotes/data/NoteEntity;", "onClick", "Lkotlin/Function0;", "NotesScreen", "vm", "Lcom/nous/wxnotes/NotesViewModel;", "onOpenNote", "Lkotlin/Function1;", "", "onOpenSettings", "notes_debug"})
public final class NotesScreenKt {
    
    @kotlin.OptIn(markerClass = {androidx.compose.material3.ExperimentalMaterial3Api.class})
    @androidx.compose.runtime.Composable()
    public static final void NotesScreen(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.NotesViewModel vm, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function1<? super java.lang.Long, kotlin.Unit> onOpenNote, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function0<kotlin.Unit> onOpenSettings) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void NoteCard(com.nous.wxnotes.data.NoteEntity note, kotlin.jvm.functions.Function0<kotlin.Unit> onClick) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void Badge(java.lang.String text) {
    }
    
    @androidx.compose.runtime.Composable()
    private static final void EmptyState(boolean searching) {
    }
}