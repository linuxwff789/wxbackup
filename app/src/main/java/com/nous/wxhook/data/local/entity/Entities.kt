package com.nous.wxhook.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_messages")
data class CachedMessage(
    @PrimaryKey val msgId: Long,
    val talker: String,
    val talkerName: String?,
    val content: String?,
    val type: Int,
    val createTime: Long,
    val isSend: Boolean,
    val msgSvrId: Long,
    val imgPath: String?,
    val source: String,           // realtime / snapshot / import
    val snapshotTime: Long?
)

@Entity(tableName = "cached_contacts")
data class CachedContact(
    @PrimaryKey val username: String,
    val nickname: String?,
    val alias: String?,
    val type: Int,
    val lastSync: Long
)

@Entity(tableName = "cached_conversations")
data class CachedConversation(
    @PrimaryKey val talker: String,
    val displayName: String?,
    val lastMessage: String?,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val chatType: Int              // 1=单聊, 2=群聊
)

@Entity(tableName = "backup_metadata")
data class BackupMetadata(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backupPath: String,
    val backupTime: Long,
    val messageCount: Long,
    val fileSize: Long,
    val keyUsed: String?,
    val notes: String?
)
