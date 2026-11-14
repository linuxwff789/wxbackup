package com.nous.wxnotes.data;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class NotesDatabase_Impl extends NotesDatabase {
  private volatile NoteDao _noteDao;

  private volatile MessageDao _messageDao;

  private volatile TagDao _tagDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `auto` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `talker` TEXT NOT NULL, `talkerTitle` TEXT NOT NULL, `messageCount` INTEGER NOT NULL, `preview` TEXT NOT NULL, `lastMessageTime` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, `dedupeKey` TEXT NOT NULL, `msgId` INTEGER NOT NULL, `msgSvrId` INTEGER NOT NULL, `talker` TEXT NOT NULL, `talkerTitle` TEXT NOT NULL, `sender` TEXT NOT NULL, `senderName` TEXT NOT NULL, `type` INTEGER NOT NULL, `subType` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `isSend` INTEGER NOT NULL, `text` TEXT NOT NULL, `rawXml` TEXT NOT NULL, `mediaKind` TEXT NOT NULL, `mediaPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_noteId` ON `messages` (`noteId`)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_dedupeKey` ON `messages` (`dedupeKey`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_createTime` ON `messages` (`createTime`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_talker` ON `messages` (`talker`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_tags` (`noteId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`noteId`, `tagId`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ad41b36c8bb4330a1c42023802cbaf3a')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `notes`");
        db.execSQL("DROP TABLE IF EXISTS `messages`");
        db.execSQL("DROP TABLE IF EXISTS `tags`");
        db.execSQL("DROP TABLE IF EXISTS `note_tags`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsNotes = new HashMap<String, TableInfo.Column>(13);
        _columnsNotes.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("title", new TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("body", new TableInfo.Column("body", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("auto", new TableInfo.Column("auto", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("pinned", new TableInfo.Column("pinned", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("archived", new TableInfo.Column("archived", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("talker", new TableInfo.Column("talker", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("talkerTitle", new TableInfo.Column("talkerTitle", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("messageCount", new TableInfo.Column("messageCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("preview", new TableInfo.Column("preview", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNotes.put("lastMessageTime", new TableInfo.Column("lastMessageTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysNotes = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesNotes = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoNotes = new TableInfo("notes", _columnsNotes, _foreignKeysNotes, _indicesNotes);
        final TableInfo _existingNotes = TableInfo.read(db, "notes");
        if (!_infoNotes.equals(_existingNotes)) {
          return new RoomOpenHelper.ValidationResult(false, "notes(com.nous.wxnotes.data.NoteEntity).\n"
                  + " Expected:\n" + _infoNotes + "\n"
                  + " Found:\n" + _existingNotes);
        }
        final HashMap<String, TableInfo.Column> _columnsMessages = new HashMap<String, TableInfo.Column>(20);
        _columnsMessages.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("noteId", new TableInfo.Column("noteId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("dedupeKey", new TableInfo.Column("dedupeKey", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("msgId", new TableInfo.Column("msgId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("msgSvrId", new TableInfo.Column("msgSvrId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("talker", new TableInfo.Column("talker", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("talkerTitle", new TableInfo.Column("talkerTitle", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("sender", new TableInfo.Column("sender", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("senderName", new TableInfo.Column("senderName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("type", new TableInfo.Column("type", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("subType", new TableInfo.Column("subType", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("createTime", new TableInfo.Column("createTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("isSend", new TableInfo.Column("isSend", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("text", new TableInfo.Column("text", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("rawXml", new TableInfo.Column("rawXml", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("mediaKind", new TableInfo.Column("mediaKind", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("mediaPath", new TableInfo.Column("mediaPath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("fileName", new TableInfo.Column("fileName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("fileSize", new TableInfo.Column("fileSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("durationMs", new TableInfo.Column("durationMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysMessages = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesMessages = new HashSet<TableInfo.Index>(4);
        _indicesMessages.add(new TableInfo.Index("index_messages_noteId", false, Arrays.asList("noteId"), Arrays.asList("ASC")));
        _indicesMessages.add(new TableInfo.Index("index_messages_dedupeKey", true, Arrays.asList("dedupeKey"), Arrays.asList("ASC")));
        _indicesMessages.add(new TableInfo.Index("index_messages_createTime", false, Arrays.asList("createTime"), Arrays.asList("ASC")));
        _indicesMessages.add(new TableInfo.Index("index_messages_talker", false, Arrays.asList("talker"), Arrays.asList("ASC")));
        final TableInfo _infoMessages = new TableInfo("messages", _columnsMessages, _foreignKeysMessages, _indicesMessages);
        final TableInfo _existingMessages = TableInfo.read(db, "messages");
        if (!_infoMessages.equals(_existingMessages)) {
          return new RoomOpenHelper.ValidationResult(false, "messages(com.nous.wxnotes.data.MessageEntity).\n"
                  + " Expected:\n" + _infoMessages + "\n"
                  + " Found:\n" + _existingMessages);
        }
        final HashMap<String, TableInfo.Column> _columnsTags = new HashMap<String, TableInfo.Column>(3);
        _columnsTags.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsTags.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsTags.put("color", new TableInfo.Column("color", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysTags = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesTags = new HashSet<TableInfo.Index>(1);
        _indicesTags.add(new TableInfo.Index("index_tags_name", true, Arrays.asList("name"), Arrays.asList("ASC")));
        final TableInfo _infoTags = new TableInfo("tags", _columnsTags, _foreignKeysTags, _indicesTags);
        final TableInfo _existingTags = TableInfo.read(db, "tags");
        if (!_infoTags.equals(_existingTags)) {
          return new RoomOpenHelper.ValidationResult(false, "tags(com.nous.wxnotes.data.TagEntity).\n"
                  + " Expected:\n" + _infoTags + "\n"
                  + " Found:\n" + _existingTags);
        }
        final HashMap<String, TableInfo.Column> _columnsNoteTags = new HashMap<String, TableInfo.Column>(2);
        _columnsNoteTags.put("noteId", new TableInfo.Column("noteId", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsNoteTags.put("tagId", new TableInfo.Column("tagId", "INTEGER", true, 2, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysNoteTags = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesNoteTags = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoNoteTags = new TableInfo("note_tags", _columnsNoteTags, _foreignKeysNoteTags, _indicesNoteTags);
        final TableInfo _existingNoteTags = TableInfo.read(db, "note_tags");
        if (!_infoNoteTags.equals(_existingNoteTags)) {
          return new RoomOpenHelper.ValidationResult(false, "note_tags(com.nous.wxnotes.data.NoteTagCrossRef).\n"
                  + " Expected:\n" + _infoNoteTags + "\n"
                  + " Found:\n" + _existingNoteTags);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "ad41b36c8bb4330a1c42023802cbaf3a", "9aded6cc7151b1c97dee63727f724e32");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "notes","messages","tags","note_tags");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `notes`");
      _db.execSQL("DELETE FROM `messages`");
      _db.execSQL("DELETE FROM `tags`");
      _db.execSQL("DELETE FROM `note_tags`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(NoteDao.class, NoteDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(MessageDao.class, MessageDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(TagDao.class, TagDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public NoteDao noteDao() {
    if (_noteDao != null) {
      return _noteDao;
    } else {
      synchronized(this) {
        if(_noteDao == null) {
          _noteDao = new NoteDao_Impl(this);
        }
        return _noteDao;
      }
    }
  }

  @Override
  public MessageDao messageDao() {
    if (_messageDao != null) {
      return _messageDao;
    } else {
      synchronized(this) {
        if(_messageDao == null) {
          _messageDao = new MessageDao_Impl(this);
        }
        return _messageDao;
      }
    }
  }

  @Override
  public TagDao tagDao() {
    if (_tagDao != null) {
      return _tagDao;
    } else {
      synchronized(this) {
        if(_tagDao == null) {
          _tagDao = new TagDao_Impl(this);
        }
        return _tagDao;
      }
    }
  }
}
