package com.nous.wxnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NoteEntity::class, MessageEntity::class, TagEntity::class, NoteTagCrossRef::class],
    version = 1,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun messageDao(): MessageDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile
        private var instance: NotesDatabase? = null

        fun get(context: Context): NotesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NotesDatabase::class.java,
                "wxnotes.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
