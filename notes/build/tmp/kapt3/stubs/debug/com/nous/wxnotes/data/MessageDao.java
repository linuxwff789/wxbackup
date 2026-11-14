package com.nous.wxnotes.data;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000@\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0003\bg\u0018\u00002\u00020\u0001J\u0016\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J\u0016\u0010\u0007\u001a\u00020\b2\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J\u0016\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\fH\u00a7@\u00a2\u0006\u0002\u0010\rJ\u001c\u0010\u000e\u001a\b\u0012\u0004\u0012\u00020\u00100\u000f2\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J\"\u0010\u0011\u001a\b\u0012\u0004\u0012\u00020\u00050\u000f2\f\u0010\u0012\u001a\b\u0012\u0004\u0012\u00020\u00100\u000fH\u00a7@\u00a2\u0006\u0002\u0010\u0013J\u0016\u0010\u0014\u001a\u00020\u00052\u0006\u0010\u0015\u001a\u00020\u0010H\u00a7@\u00a2\u0006\u0002\u0010\u0016J\u001c\u0010\u0017\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00100\u000f0\u00182\u0006\u0010\u0004\u001a\u00020\u0005H\'J\u001c\u0010\u0019\u001a\b\u0012\u0004\u0012\u00020\u00100\u000f2\u0006\u0010\u001a\u001a\u00020\fH\u00a7@\u00a2\u0006\u0002\u0010\r\u00a8\u0006\u001b"}, d2 = {"Lcom/nous/wxnotes/data/MessageDao;", "", "countByNote", "", "noteId", "", "(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "deleteByNote", "", "exists", "", "key", "", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getByNote", "", "Lcom/nous/wxnotes/data/MessageEntity;", "insertAllIgnore", "msgs", "(Ljava/util/List;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "insertIgnore", "msg", "(Lcom/nous/wxnotes/data/MessageEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "observeByNote", "Lkotlinx/coroutines/flow/Flow;", "search", "q", "notes_debug"})
@androidx.room.Dao()
public abstract interface MessageDao {
    
    @androidx.room.Insert(onConflict = 5)
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object insertIgnore(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.data.MessageEntity msg, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Long> $completion);
    
    @androidx.room.Insert(onConflict = 5)
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object insertAllIgnore(@org.jetbrains.annotations.NotNull()
    java.util.List<com.nous.wxnotes.data.MessageEntity> msgs, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<java.lang.Long>> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM messages WHERE noteId=:noteId ORDER BY createTime ASC, id ASC")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.MessageEntity>> observeByNote(long noteId);
    
    @androidx.room.Query(value = "SELECT * FROM messages WHERE noteId=:noteId ORDER BY createTime ASC, id ASC")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getByNote(long noteId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.nous.wxnotes.data.MessageEntity>> $completion);
    
    @androidx.room.Query(value = "SELECT COUNT(*) FROM messages WHERE noteId=:noteId")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object countByNote(long noteId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Integer> $completion);
    
    @androidx.room.Query(value = "DELETE FROM messages WHERE noteId=:noteId")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object deleteByNote(long noteId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "\n        SELECT * FROM messages WHERE text LIKE \'%\' || :q || \'%\' OR senderName LIKE \'%\' || :q || \'%\'\n        ORDER BY createTime DESC LIMIT 300\n        ")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object search(@org.jetbrains.annotations.NotNull()
    java.lang.String q, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.nous.wxnotes.data.MessageEntity>> $completion);
    
    @androidx.room.Query(value = "SELECT EXISTS(SELECT 1 FROM messages WHERE dedupeKey=:key)")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object exists(@org.jetbrains.annotations.NotNull()
    java.lang.String key, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion);
}