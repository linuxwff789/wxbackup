package com.nous.wxnotes.data;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\b\'\u0018\u0000 \t2\u00020\u0001:\u0001\tB\u0005\u00a2\u0006\u0002\u0010\u0002J\b\u0010\u0003\u001a\u00020\u0004H&J\b\u0010\u0005\u001a\u00020\u0006H&J\b\u0010\u0007\u001a\u00020\bH&\u00a8\u0006\n"}, d2 = {"Lcom/nous/wxnotes/data/NotesDatabase;", "Landroidx/room/RoomDatabase;", "()V", "messageDao", "Lcom/nous/wxnotes/data/MessageDao;", "noteDao", "Lcom/nous/wxnotes/data/NoteDao;", "tagDao", "Lcom/nous/wxnotes/data/TagDao;", "Companion", "notes_debug"})
@androidx.room.Database(entities = {com.nous.wxnotes.data.NoteEntity.class, com.nous.wxnotes.data.MessageEntity.class, com.nous.wxnotes.data.TagEntity.class, com.nous.wxnotes.data.NoteTagCrossRef.class}, version = 1, exportSchema = false)
public abstract class NotesDatabase extends androidx.room.RoomDatabase {
    @kotlin.jvm.Volatile()
    @org.jetbrains.annotations.Nullable()
    private static volatile com.nous.wxnotes.data.NotesDatabase instance;
    @org.jetbrains.annotations.NotNull()
    public static final com.nous.wxnotes.data.NotesDatabase.Companion Companion = null;
    
    public NotesDatabase() {
        super();
    }
    
    @org.jetbrains.annotations.NotNull()
    public abstract com.nous.wxnotes.data.NoteDao noteDao();
    
    @org.jetbrains.annotations.NotNull()
    public abstract com.nous.wxnotes.data.MessageDao messageDao();
    
    @org.jetbrains.annotations.NotNull()
    public abstract com.nous.wxnotes.data.TagDao tagDao();
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001a\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u000e\u0010\u0005\u001a\u00020\u00042\u0006\u0010\u0006\u001a\u00020\u0007R\u0010\u0010\u0003\u001a\u0004\u0018\u00010\u0004X\u0082\u000e\u00a2\u0006\u0002\n\u0000\u00a8\u0006\b"}, d2 = {"Lcom/nous/wxnotes/data/NotesDatabase$Companion;", "", "()V", "instance", "Lcom/nous/wxnotes/data/NotesDatabase;", "get", "context", "Landroid/content/Context;", "notes_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
        
        @org.jetbrains.annotations.NotNull()
        public final com.nous.wxnotes.data.NotesDatabase get(@org.jetbrains.annotations.NotNull()
        android.content.Context context) {
            return null;
        }
    }
}