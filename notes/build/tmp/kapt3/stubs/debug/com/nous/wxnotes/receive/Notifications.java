package com.nous.wxnotes.receive;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000,\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0002\b\u0002\b\u00c7\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J.\u0010\u0003\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\u0006\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\f\u00a8\u0006\u000e"}, d2 = {"Lcom/nous/wxnotes/receive/Notifications;", "", "()V", "importFinished", "", "context", "Landroid/content/Context;", "noteId", "", "noteTitle", "", "added", "", "skipped", "notes_debug"})
public final class Notifications {
    @org.jetbrains.annotations.NotNull()
    public static final com.nous.wxnotes.receive.Notifications INSTANCE = null;
    
    private Notifications() {
        super();
    }
    
    public final void importFinished(@org.jetbrains.annotations.NotNull()
    android.content.Context context, long noteId, @org.jetbrains.annotations.NotNull()
    java.lang.String noteTitle, int added, int skipped) {
    }
}