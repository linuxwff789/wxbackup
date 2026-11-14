package com.nous.wxnotes.data;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000*\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0007\n\u0002\u0010\b\n\u0002\b\u0003\n\u0002\u0010\u000b\n\u0002\b9\b\u0087\b\u0018\u00002\u00020\u0001B\u00c9\u0001\u0012\b\b\u0002\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003\u0012\u0006\u0010\u0005\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u0007\u001a\u00020\u0003\u0012\b\b\u0002\u0010\b\u001a\u00020\u0003\u0012\b\b\u0002\u0010\t\u001a\u00020\u0006\u0012\b\b\u0002\u0010\n\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u000b\u001a\u00020\u0006\u0012\b\b\u0002\u0010\f\u001a\u00020\u0006\u0012\b\b\u0002\u0010\r\u001a\u00020\u000e\u0012\b\b\u0002\u0010\u000f\u001a\u00020\u000e\u0012\b\b\u0002\u0010\u0010\u001a\u00020\u0003\u0012\b\b\u0002\u0010\u0011\u001a\u00020\u0012\u0012\b\b\u0002\u0010\u0013\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u0014\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u0015\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u0016\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u0017\u001a\u00020\u0006\u0012\b\b\u0002\u0010\u0018\u001a\u00020\u0003\u0012\b\b\u0002\u0010\u0019\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u001aJ\t\u00102\u001a\u00020\u0003H\u00c6\u0003J\t\u00103\u001a\u00020\u000eH\u00c6\u0003J\t\u00104\u001a\u00020\u000eH\u00c6\u0003J\t\u00105\u001a\u00020\u0003H\u00c6\u0003J\t\u00106\u001a\u00020\u0012H\u00c6\u0003J\t\u00107\u001a\u00020\u0006H\u00c6\u0003J\t\u00108\u001a\u00020\u0006H\u00c6\u0003J\t\u00109\u001a\u00020\u0006H\u00c6\u0003J\t\u0010:\u001a\u00020\u0006H\u00c6\u0003J\t\u0010;\u001a\u00020\u0006H\u00c6\u0003J\t\u0010<\u001a\u00020\u0003H\u00c6\u0003J\t\u0010=\u001a\u00020\u0003H\u00c6\u0003J\t\u0010>\u001a\u00020\u0003H\u00c6\u0003J\t\u0010?\u001a\u00020\u0006H\u00c6\u0003J\t\u0010@\u001a\u00020\u0003H\u00c6\u0003J\t\u0010A\u001a\u00020\u0003H\u00c6\u0003J\t\u0010B\u001a\u00020\u0006H\u00c6\u0003J\t\u0010C\u001a\u00020\u0006H\u00c6\u0003J\t\u0010D\u001a\u00020\u0006H\u00c6\u0003J\t\u0010E\u001a\u00020\u0006H\u00c6\u0003J\u00d1\u0001\u0010F\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00032\b\b\u0002\u0010\u0005\u001a\u00020\u00062\b\b\u0002\u0010\u0007\u001a\u00020\u00032\b\b\u0002\u0010\b\u001a\u00020\u00032\b\b\u0002\u0010\t\u001a\u00020\u00062\b\b\u0002\u0010\n\u001a\u00020\u00062\b\b\u0002\u0010\u000b\u001a\u00020\u00062\b\b\u0002\u0010\f\u001a\u00020\u00062\b\b\u0002\u0010\r\u001a\u00020\u000e2\b\b\u0002\u0010\u000f\u001a\u00020\u000e2\b\b\u0002\u0010\u0010\u001a\u00020\u00032\b\b\u0002\u0010\u0011\u001a\u00020\u00122\b\b\u0002\u0010\u0013\u001a\u00020\u00062\b\b\u0002\u0010\u0014\u001a\u00020\u00062\b\b\u0002\u0010\u0015\u001a\u00020\u00062\b\b\u0002\u0010\u0016\u001a\u00020\u00062\b\b\u0002\u0010\u0017\u001a\u00020\u00062\b\b\u0002\u0010\u0018\u001a\u00020\u00032\b\b\u0002\u0010\u0019\u001a\u00020\u0003H\u00c6\u0001J\u0013\u0010G\u001a\u00020\u00122\b\u0010H\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u0010I\u001a\u00020\u000eH\u00d6\u0001J\t\u0010J\u001a\u00020\u0006H\u00d6\u0001R\u0011\u0010\u0010\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001b\u0010\u001cR\u0011\u0010\u0005\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001d\u0010\u001eR\u0011\u0010\u0019\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001f\u0010\u001cR\u0011\u0010\u0017\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b \u0010\u001eR\u0011\u0010\u0018\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b!\u0010\u001cR\u0016\u0010\u0002\u001a\u00020\u00038\u0006X\u0087\u0004\u00a2\u0006\b\n\u0000\u001a\u0004\b\"\u0010\u001cR\u0011\u0010\u0011\u001a\u00020\u0012\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0011\u0010#R\u0011\u0010\u0015\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b$\u0010\u001eR\u0011\u0010\u0016\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b%\u0010\u001eR\u0011\u0010\u0007\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b&\u0010\u001cR\u0011\u0010\b\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\'\u0010\u001cR\u0011\u0010\u0004\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b(\u0010\u001cR\u0011\u0010\u0014\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b)\u0010\u001eR\u0011\u0010\u000b\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b*\u0010\u001eR\u0011\u0010\f\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b+\u0010\u001eR\u0011\u0010\u000f\u001a\u00020\u000e\u00a2\u0006\b\n\u0000\u001a\u0004\b,\u0010-R\u0011\u0010\t\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b.\u0010\u001eR\u0011\u0010\n\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b/\u0010\u001eR\u0011\u0010\u0013\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b0\u0010\u001eR\u0011\u0010\r\u001a\u00020\u000e\u00a2\u0006\b\n\u0000\u001a\u0004\b1\u0010-\u00a8\u0006K"}, d2 = {"Lcom/nous/wxnotes/data/MessageEntity;", "", "id", "", "noteId", "dedupeKey", "", "msgId", "msgSvrId", "talker", "talkerTitle", "sender", "senderName", "type", "", "subType", "createTime", "isSend", "", "text", "rawXml", "mediaKind", "mediaPath", "fileName", "fileSize", "durationMs", "(JJLjava/lang/String;JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIJZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V", "getCreateTime", "()J", "getDedupeKey", "()Ljava/lang/String;", "getDurationMs", "getFileName", "getFileSize", "getId", "()Z", "getMediaKind", "getMediaPath", "getMsgId", "getMsgSvrId", "getNoteId", "getRawXml", "getSender", "getSenderName", "getSubType", "()I", "getTalker", "getTalkerTitle", "getText", "getType", "component1", "component10", "component11", "component12", "component13", "component14", "component15", "component16", "component17", "component18", "component19", "component2", "component20", "component3", "component4", "component5", "component6", "component7", "component8", "component9", "copy", "equals", "other", "hashCode", "toString", "notes_debug"})
@androidx.room.Entity(tableName = "messages", indices = {@androidx.room.Index(value = {"noteId"}), @androidx.room.Index(value = {"dedupeKey"}, unique = true), @androidx.room.Index(value = {"createTime"}), @androidx.room.Index(value = {"talker"})})
public final class MessageEntity {
    @androidx.room.PrimaryKey(autoGenerate = true)
    private final long id = 0L;
    private final long noteId = 0L;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String dedupeKey = null;
    private final long msgId = 0L;
    private final long msgSvrId = 0L;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String talker = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String talkerTitle = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String sender = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String senderName = null;
    private final int type = 0;
    private final int subType = 0;
    
    /**
     * 微信秒级时间戳
     */
    private final long createTime = 0L;
    private final boolean isSend = false;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String text = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String rawXml = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String mediaKind = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String mediaPath = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String fileName = null;
    private final long fileSize = 0L;
    private final long durationMs = 0L;
    
    public MessageEntity(long id, long noteId, @org.jetbrains.annotations.NotNull()
    java.lang.String dedupeKey, long msgId, long msgSvrId, @org.jetbrains.annotations.NotNull()
    java.lang.String talker, @org.jetbrains.annotations.NotNull()
    java.lang.String talkerTitle, @org.jetbrains.annotations.NotNull()
    java.lang.String sender, @org.jetbrains.annotations.NotNull()
    java.lang.String senderName, int type, int subType, long createTime, boolean isSend, @org.jetbrains.annotations.NotNull()
    java.lang.String text, @org.jetbrains.annotations.NotNull()
    java.lang.String rawXml, @org.jetbrains.annotations.NotNull()
    java.lang.String mediaKind, @org.jetbrains.annotations.NotNull()
    java.lang.String mediaPath, @org.jetbrains.annotations.NotNull()
    java.lang.String fileName, long fileSize, long durationMs) {
        super();
    }
    
    public final long getId() {
        return 0L;
    }
    
    public final long getNoteId() {
        return 0L;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getDedupeKey() {
        return null;
    }
    
    public final long getMsgId() {
        return 0L;
    }
    
    public final long getMsgSvrId() {
        return 0L;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getTalker() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getTalkerTitle() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getSender() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getSenderName() {
        return null;
    }
    
    public final int getType() {
        return 0;
    }
    
    public final int getSubType() {
        return 0;
    }
    
    /**
     * 微信秒级时间戳
     */
    public final long getCreateTime() {
        return 0L;
    }
    
    public final boolean isSend() {
        return false;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getText() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getRawXml() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getMediaKind() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getMediaPath() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getFileName() {
        return null;
    }
    
    public final long getFileSize() {
        return 0L;
    }
    
    public final long getDurationMs() {
        return 0L;
    }
    
    public final long component1() {
        return 0L;
    }
    
    public final int component10() {
        return 0;
    }
    
    public final int component11() {
        return 0;
    }
    
    public final long component12() {
        return 0L;
    }
    
    public final boolean component13() {
        return false;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component14() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component15() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component16() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component17() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component18() {
        return null;
    }
    
    public final long component19() {
        return 0L;
    }
    
    public final long component2() {
        return 0L;
    }
    
    public final long component20() {
        return 0L;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component3() {
        return null;
    }
    
    public final long component4() {
        return 0L;
    }
    
    public final long component5() {
        return 0L;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component6() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component7() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component8() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component9() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final com.nous.wxnotes.data.MessageEntity copy(long id, long noteId, @org.jetbrains.annotations.NotNull()
    java.lang.String dedupeKey, long msgId, long msgSvrId, @org.jetbrains.annotations.NotNull()
    java.lang.String talker, @org.jetbrains.annotations.NotNull()
    java.lang.String talkerTitle, @org.jetbrains.annotations.NotNull()
    java.lang.String sender, @org.jetbrains.annotations.NotNull()
    java.lang.String senderName, int type, int subType, long createTime, boolean isSend, @org.jetbrains.annotations.NotNull()
    java.lang.String text, @org.jetbrains.annotations.NotNull()
    java.lang.String rawXml, @org.jetbrains.annotations.NotNull()
    java.lang.String mediaKind, @org.jetbrains.annotations.NotNull()
    java.lang.String mediaPath, @org.jetbrains.annotations.NotNull()
    java.lang.String fileName, long fileSize, long durationMs) {
        return null;
    }
    
    @java.lang.Override()
    public boolean equals(@org.jetbrains.annotations.Nullable()
    java.lang.Object other) {
        return false;
    }
    
    @java.lang.Override()
    public int hashCode() {
        return 0;
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.NotNull()
    public java.lang.String toString() {
        return null;
    }
}