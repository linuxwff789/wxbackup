package com.nous.wxhook.data.local.dao

import androidx.room.*
import com.nous.wxhook.data.local.entity.*

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE talker = :talker ORDER BY createTime ASC")
    suspend fun getMessagesByTalker(talker: String): List<CachedMessage>

    @Query("SELECT * FROM cached_messages WHERE content LIKE '%' || :keyword || '%' ORDER BY createTime DESC LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int = 200): List<CachedMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<CachedMessage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: CachedMessage)

    @Query("SELECT COUNT(*) FROM cached_messages")
    suspend fun getCount(): Long
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM cached_contacts ORDER BY nickname ASC")
    suspend fun getAll(): List<CachedContact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<CachedContact>)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM cached_conversations ORDER BY lastMessageTime DESC")
    suspend fun getAll(): List<CachedConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<CachedConversation>)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_metadata ORDER BY backupTime DESC")
    suspend fun getAll(): List<BackupMetadata>

    @Insert
    suspend fun insert(backup: BackupMetadata)

    @Query("DELETE FROM backup_metadata WHERE id = :id")
    suspend fun delete(id: Long)
}
