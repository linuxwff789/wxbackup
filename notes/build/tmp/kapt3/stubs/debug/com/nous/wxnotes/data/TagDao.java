package com.nous.wxnotes.data;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000H\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0010 \n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\u0004\bg\u0018\u00002\u00020\u0001J\u0016\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J\u0018\u0010\u0007\u001a\u0004\u0018\u00010\b2\u0006\u0010\t\u001a\u00020\nH\u00a7@\u00a2\u0006\u0002\u0010\u000bJ\u0018\u0010\f\u001a\u0004\u0018\u00010\b2\u0006\u0010\u0004\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006J\u0016\u0010\r\u001a\u00020\u00052\u0006\u0010\u000e\u001a\u00020\bH\u00a7@\u00a2\u0006\u0002\u0010\u000fJ\u0016\u0010\u0010\u001a\u00020\u00032\u0006\u0010\u0011\u001a\u00020\u0012H\u00a7@\u00a2\u0006\u0002\u0010\u0013J\u0014\u0010\u0014\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u00160\u0015H\'J\u001c\u0010\u0017\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00180\u00160\u00152\u0006\u0010\u0019\u001a\u00020\u0005H\'J\u001c\u0010\u001a\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u00160\u00152\u0006\u0010\u001b\u001a\u00020\u0005H\'J\u0014\u0010\u001c\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u001d0\u00160\u0015H\'J\u001e\u0010\u001e\u001a\u00020\u00032\u0006\u0010\u001b\u001a\u00020\u00052\u0006\u0010\u0019\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u001fJ\u0016\u0010 \u001a\u00020\u00032\u0006\u0010\u0019\u001a\u00020\u0005H\u00a7@\u00a2\u0006\u0002\u0010\u0006\u00a8\u0006!"}, d2 = {"Lcom/nous/wxnotes/data/TagDao;", "", "delete", "", "id", "", "(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "findByName", "Lcom/nous/wxnotes/data/TagEntity;", "name", "", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "get", "insert", "tag", "(Lcom/nous/wxnotes/data/TagEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "link", "ref", "Lcom/nous/wxnotes/data/NoteTagCrossRef;", "(Lcom/nous/wxnotes/data/NoteTagCrossRef;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "observeAll", "Lkotlinx/coroutines/flow/Flow;", "", "observeNotesWithTag", "Lcom/nous/wxnotes/data/NoteEntity;", "tagId", "observeTagsOf", "noteId", "observeWithCount", "Lcom/nous/wxnotes/data/TagWithCount;", "unlink", "(JJLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "unlinkAll", "notes_debug"})
@androidx.room.Dao()
public abstract interface TagDao {
    
    @androidx.room.Insert(onConflict = 5)
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object insert(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.data.TagEntity tag, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Long> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM tags ORDER BY name")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.TagEntity>> observeAll();
    
    @androidx.room.Query(value = "SELECT * FROM tags WHERE name=:name")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object findByName(@org.jetbrains.annotations.NotNull()
    java.lang.String name, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.nous.wxnotes.data.TagEntity> $completion);
    
    @androidx.room.Insert(onConflict = 5)
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object link(@org.jetbrains.annotations.NotNull()
    com.nous.wxnotes.data.NoteTagCrossRef ref, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "DELETE FROM note_tags WHERE noteId=:noteId AND tagId=:tagId")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object unlink(long noteId, long tagId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM tags WHERE id=:id")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object get(long id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.nous.wxnotes.data.TagEntity> $completion);
    
    @androidx.room.Query(value = "\n        SELECT t.id AS id, t.name AS name, t.color AS color, COUNT(nt.noteId) AS count\n        FROM tags t LEFT JOIN note_tags nt ON nt.tagId = t.id\n        GROUP BY t.id ORDER BY t.name\n        ")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.TagWithCount>> observeWithCount();
    
    @androidx.room.Query(value = "\n        SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId=t.id\n        WHERE nt.noteId=:noteId ORDER BY t.name\n        ")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.TagEntity>> observeTagsOf(long noteId);
    
    @androidx.room.Query(value = "\n        SELECT n.* FROM notes n JOIN note_tags nt ON nt.noteId=n.id\n        WHERE nt.tagId=:tagId AND n.archived=0 ORDER BY n.lastMessageTime DESC\n        ")
    @org.jetbrains.annotations.NotNull()
    public abstract kotlinx.coroutines.flow.Flow<java.util.List<com.nous.wxnotes.data.NoteEntity>> observeNotesWithTag(long tagId);
    
    @androidx.room.Query(value = "DELETE FROM tags WHERE id=:id")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object delete(long id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "DELETE FROM note_tags WHERE tagId=:tagId")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object unlinkAll(long tagId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
}