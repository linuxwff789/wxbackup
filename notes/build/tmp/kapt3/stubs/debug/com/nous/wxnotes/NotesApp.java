package com.nous.wxnotes;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001c\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\u0002\n\u0002\b\u0003\b\u0007\u0018\u0000 \u000b2\u00020\u0001:\u0001\u000bB\u0005\u00a2\u0006\u0002\u0010\u0002J\b\u0010\b\u001a\u00020\tH\u0002J\b\u0010\n\u001a\u00020\tH\u0016R\u001e\u0010\u0005\u001a\u00020\u00042\u0006\u0010\u0003\u001a\u00020\u0004@BX\u0086.\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0006\u0010\u0007\u00a8\u0006\f"}, d2 = {"Lcom/nous/wxnotes/NotesApp;", "Landroid/app/Application;", "()V", "<set-?>", "Lcom/nous/wxnotes/data/NotesRepository;", "repository", "getRepository", "()Lcom/nous/wxnotes/data/NotesRepository;", "createChannels", "", "onCreate", "Companion", "notes_debug"})
public final class NotesApp extends android.app.Application {
    private com.nous.wxnotes.data.NotesRepository repository;
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String CHANNEL_IMPORT = "wxnotes_import";
    private static com.nous.wxnotes.NotesApp instance;
    @org.jetbrains.annotations.NotNull()
    public static final com.nous.wxnotes.NotesApp.Companion Companion = null;
    
    public NotesApp() {
        super();
    }
    
    @org.jetbrains.annotations.NotNull()
    public final com.nous.wxnotes.data.NotesRepository getRepository() {
        return null;
    }
    
    @java.lang.Override()
    public void onCreate() {
    }
    
    private final void createChannels() {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001a\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u001e\u0010\u0007\u001a\u00020\u00062\u0006\u0010\u0005\u001a\u00020\u0006@BX\u0086.\u00a2\u0006\b\n\u0000\u001a\u0004\b\b\u0010\t\u00a8\u0006\n"}, d2 = {"Lcom/nous/wxnotes/NotesApp$Companion;", "", "()V", "CHANNEL_IMPORT", "", "<set-?>", "Lcom/nous/wxnotes/NotesApp;", "instance", "getInstance", "()Lcom/nous/wxnotes/NotesApp;", "notes_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
        
        @org.jetbrains.annotations.NotNull()
        public final com.nous.wxnotes.NotesApp getInstance() {
            return null;
        }
    }
}