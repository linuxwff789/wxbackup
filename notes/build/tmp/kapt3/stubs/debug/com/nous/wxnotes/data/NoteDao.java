package com.nous.wxnotes.data;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000>\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0004\n\u0002\u0010 \n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\u000b\n\u0002\b\u0003\bg\u0018\u00002\u00020\u0001J\u0016\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J \u0010\u0007\u001a\u0004\u0018\u00010\b2\u0006\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\fJ\u0018\u0010\r\u001a\u0004\u0018\u00010\b2\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J\u0014\u0010\u000e\u001a\b\u0012\u0004\u0012\u00020\b0\u000fH\u00a7@\u00a2\u0006\u0002\u0010\u0010J\u0016\u0010\u0011\u001a\u00020\u00052\u0006\u0010\u0012\u001a\u00020\bH\u00a7@\u00a2\u0006\u0002\u0010\u0013J\u0018\u0010\u0014\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\b0\u00152\u0006\u0010\u0004\u001a\u00020\u0005H\'J\u001c\u0010\u0016\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u000f0\u00152\u0006\u0010\u0017\u001a\u00020\nH\'J\u001c\u0010\u0018\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u000f0\u00152\u0006\u0010\u0019\u001a\u00020\u001aH\'J\u001c\u0010\u001b\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u000f0\u00152\u0006\u0010\t\u001a\u00020\nH\'J\u0016\u0010\u001c\u001a\u00020\u00032\u0006\u0010\u0012\u001a\u00020\bH\u00a7@\u00a2\u0006\u0002\u0010\u0013\u00a8\u0006\u001d"}, d2 = {"Lcom/nous/wxnotes/data/NoteDao;", "", "delete", "", "id", "", "(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "findAggregateTarget", "Lcom/nous/wxnotes/data/NoteEntity;", "talker", "", "since", "(Ljava/lang/String;JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "get", "getAll", "", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "insert", "note", "(Lcom/nous/wxnotes/data/NoteEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "observe", "Lkotlinx/coroutines/flow/Flow;", "observeAll", "q", "observeArchived", "archived", "", "observeByTalker", "update", "notes_debug"})
@androidx.room.Dao()
public abstract interface NoteDao {
    
    @androidx.room.Insert()
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object insert(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.data.NoteEntity note, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Long> $completion);
    
    @androidx.room.Update()
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object update(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.data.NoteEntity note, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "DELETE FROM notes WHERE id=:id")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object delete(long id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM notes WHERE id=:id")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object get(long id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.nous.wxnotes.data.NoteEntity> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM notes WHERE id=:id")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<com.nous.wxnotes.data.NoteEntity> observe(long id);
    
    @androidx.room.Query(value = "\n        SELECT * FROM notes\n        WHERE archived = 0 AND (\n            :q = \'\' OR title LIKE \'%\' || :q || \'%\' OR body LIKE \'%\' || :q || \'%\'\n            OR preview LIKE \'%\' || :q || \'%\' OR talkerTitle LIKE \'%\' || :q || \'%\'\n        )\n        ORDER BY pinned DESC, lastMessageTime DESC\n        ")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.NoteEntity>> observeAll(@org.jetbrains.annotations.NotNull()
    java.lang.String q);
    
    @androidx.room.Query(value = "\n        SELECT * FROM notes WHERE archived = 0 AND talker = :talker\n          AND auto = 1 AND updatedAt >= :since\n        ORDER BY updatedAt DESC LIMIT 1\n        ")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object findAggregateTarget(@org.jetbrains.annotations.NotNull()
    java.lang.String talker, long since, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.nous.wxnotes.data.NoteEntity> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM notes WHERE archived=:archived ORDER BY updatedAt DESC")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.NoteEntity>> observeArchived(boolean archived);
    
    @androidx.room.Query(value = "SELECT * FROM notes ORDER BY updatedAt DESC")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getAll(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.nous.wxnotes.data.NoteEntity>> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM notes WHERE talker=:talker ORDER BY lastMessageTime DESC")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.NoteEntity>> observeByTalker(@org.jetbrains.annotations.NotNull()
    java.lang.String talker);
}