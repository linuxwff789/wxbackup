package com.nous.wxnotes.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 用户手写的笔记正文（自动摘要之外的部分） */
    val body: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** true = 由消息自动生成/追加；false = 用户手动创建 */
    val auto: Boolean = true,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val talker: String = "",
    val talkerTitle: String = "",
    val messageCount: Int = 0,
    val preview: String = "",
    val lastMessageTime: Long = 0
)

@Entity(
    tableName = "messages",
    indices = [
        Index("noteId"),
        Index(value = ["dedupeKey"], unique = true),
        Index("createTime"),
        Index("talker")
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val dedupeKey: String,
    val msgId: Long = 0,
    val msgSvrId: Long = 0,
    val talker: String = "",
    val talkerTitle: String = "",
    val sender: String = "",
    val senderName: String = "",
    val type: Int = 0,
    val subType: Int = 0,
    /** 微信秒级时间戳 */
    val createTime: Long = 0,
    val isSend: Boolean = false,
    val text: String = "",
    val rawXml: String = "",
    val mediaKind: String = "",
    val mediaPath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val durationMs: Long = 0
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int = 0
)

@Entity(tableName = "note_tags", primaryKeys = ["noteId", "tagId"])
data class NoteTagCrossRef(val noteId: Long, val tagId: Long)

data class NoteWithTags(
    val note: NoteEntity,
    val tags: List<TagEntity>
)

data class TagWithCount(
    val id: Long,
    val name: String,
    val color: Int,
    val count: Int
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM notes WHERE id=:id")
    suspend fun get(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id=:id")
    fun observe(id: Long): Flow<NoteEntity?>

    @Query(
        """
        SELECT * FROM notes
        WHERE archived = 0 AND (
            :q = '' OR title LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%'
            OR preview LIKE '%' || :q || '%' OR talkerTitle LIKE '%' || :q || '%'
        )
        ORDER BY pinned DESC, updatedAt DESC
        """
    )
    fun observeAll(q: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes WHERE archived = 0 AND talker = :talker
          AND auto = 1 AND updatedAt >= :since
        ORDER BY updatedAt DESC LIMIT 1
        """
    )
    suspend fun findAggregateTarget(talker: String, since: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id=:id LIMIT 1")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE archived=:archived ORDER BY updatedAt DESC")
    fun observeArchived(archived: Boolean): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE talker=:talker ORDER BY lastMessageTime DESC")
    fun observeByTalker(talker: String): Flow<List<NoteEntity>>
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(msg: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(msgs: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages WHERE noteId=:noteId ORDER BY createTime ASC, id ASC")
    fun observeByNote(noteId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE noteId=:noteId ORDER BY createTime ASC, id ASC")
    suspend fun getByNote(noteId: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE noteId=:noteId")
    suspend fun countByNote(noteId: Long): Int

    @Query("DELETE FROM messages WHERE noteId=:noteId")
    suspend fun deleteByNote(noteId: Long)

    @Query(
        """
        SELECT * FROM messages WHERE text LIKE '%' || :q || '%' OR senderName LIKE '%' || :q || '%'
        ORDER BY createTime DESC LIMIT 300
        """
    )
    suspend fun search(q: String): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE dedupeKey=:key)")
    suspend fun exists(key: String): Boolean

    @Query("SELECT * FROM messages WHERE dedupeKey=:key LIMIT 1")
    suspend fun getByKey(key: String): MessageEntity?

    /** 兼容旧的 chunk 元数据（dedupeKey 不带 |n:noteId 后缀）。 */
    @Query("SELECT * FROM messages WHERE dedupeKey LIKE :prefix || '%' ORDER BY id DESC LIMIT 1")
    suspend fun getByKeyPrefix(prefix: String): MessageEntity?

    @Query("UPDATE messages SET mediaPath=:path, mediaKind=:kind, fileName=:name, fileSize=:size WHERE id=:id")
    suspend fun updateMedia(id: Long, path: String, kind: String, name: String, size: Long)

    @Query("SELECT * FROM messages WHERE id=:id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE id=:id")
    suspend fun deleteById(id: Long)

    /** 旧版本把文本消息按时间就近误配成语音（mediaKind=voice），这里一次性纠正。 */
    @Query(
        """
        UPDATE messages SET mediaPath='', mediaKind='', fileName='', fileSize=0
        WHERE type=:textType AND mediaKind=:voiceKind
        """
    )
    suspend fun clearMisdetectedVoice(textType: Int, voiceKind: String)
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name=:name")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: NoteTagCrossRef)

    @Query("DELETE FROM note_tags WHERE noteId=:noteId AND tagId=:tagId")
    suspend fun unlink(noteId: Long, tagId: Long)

    @Query("SELECT * FROM tags WHERE id=:id")
    suspend fun get(id: Long): TagEntity?

    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.color AS color, COUNT(nt.noteId) AS count
        FROM tags t LEFT JOIN note_tags nt ON nt.tagId = t.id
        GROUP BY t.id ORDER BY t.name
        """
    )
    fun observeWithCount(): Flow<List<TagWithCount>>

    @Query(
        """
        SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId=t.id
        WHERE nt.noteId=:noteId ORDER BY t.name
        """
    )
    fun observeTagsOf(noteId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT n.* FROM notes n JOIN note_tags nt ON nt.noteId=n.id
        WHERE nt.tagId=:tagId AND n.archived=0 ORDER BY n.lastMessageTime DESC
        """
    )
    fun observeNotesWithTag(tagId: Long): Flow<List<NoteEntity>>

    @Query("DELETE FROM tags WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM note_tags WHERE tagId=:tagId")
    suspend fun unlinkAll(tagId: Long)
}
