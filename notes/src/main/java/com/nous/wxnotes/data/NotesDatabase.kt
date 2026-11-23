package com.nous.wxnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, MessageEntity::class, TagEntity::class, NoteTagCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun messageDao(): MessageDao
    abstract fun tagDao(): TagDao

    companion object {
        /** v2: messages 增加 mediaUnavailable（媒体已过期标记） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN mediaUnavailable INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: NotesDatabase? = null

        fun get(context: Context): NotesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NotesDatabase::class.java,
                "wxnotes.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
