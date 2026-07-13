package com.nous.wxhook.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nous.wxhook.data.local.dao.*
import com.nous.wxhook.data.local.entity.*

@Database(
    entities = [
        CachedMessage::class,
        CachedContact::class,
        CachedConversation::class,
        BackupMetadata::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WxHookDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun backupDao(): BackupDao

    companion object {
        @Volatile
        private var INSTANCE: WxHookDatabase? = null

        fun getInstance(context: Context): WxHookDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WxHookDatabase::class.java,
                    "wxhook.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
