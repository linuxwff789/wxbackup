package com.nous.wxnotes.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class TagDao_Impl implements TagDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<TagEntity> __insertionAdapterOfTagEntity;

  private final EntityInsertionAdapter<NoteTagCrossRef> __insertionAdapterOfNoteTagCrossRef;

  private final SharedSQLiteStatement __preparedStmtOfUnlink;

  private final SharedSQLiteStatement __preparedStmtOfDelete;

  private final SharedSQLiteStatement __preparedStmtOfUnlinkAll;

  public TagDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfTagEntity = new EntityInsertionAdapter<TagEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `tags` (`id`,`name`,`color`) VALUES (nullif(?, 0),?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final TagEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getName() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getName());
        }
        statement.bindLong(3, entity.getColor());
      }
    };
    this.__insertionAdapterOfNoteTagCrossRef = new EntityInsertionAdapter<NoteTagCrossRef>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `note_tags` (`noteId`,`tagId`) VALUES (?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final NoteTagCrossRef entity) {
        statement.bindLong(1, entity.getNoteId());
        statement.bindLong(2, entity.getTagId());
      }
    };
    this.__preparedStmtOfUnlink = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM note_tags WHERE noteId=? AND tagId=?";
        return _query;
      }
    };
    this.__preparedStmtOfDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM tags WHERE id=?";
        return _query;
      }
    };
    this.__preparedStmtOfUnlinkAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM note_tags WHERE tagId=?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final TagEntity tag, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfTagEntity.insertAndReturnId(tag);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object link(final NoteTagCrossRef ref, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfNoteTagCrossRef.insert(ref);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object unlink(final long noteId, final long tagId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUnlink.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, noteId);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, tagId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUnlink.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDelete.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDelete.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object unlinkAll(final long tagId, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUnlinkAll.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, tagId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUnlinkAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<TagEntity>> observeAll() {
    final String _sql = "SELECT * FROM tags ORDER BY name";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"tags"}, new Callable<List<TagEntity>>() {
      @Override
      @NonNull
      public List<TagEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final List<TagEntity> _result = new ArrayList<TagEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final TagEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            _item = new TagEntity(_tmpId,_tmpName,_tmpColor);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object findByName(final String name, final Continuation<? super TagEntity> $completion) {
    final String _sql = "SELECT * FROM tags WHERE name=?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (name == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, name);
    }
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<TagEntity>() {
      @Override
      @Nullable
      public TagEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final TagEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            _result = new TagEntity(_tmpId,_tmpName,_tmpColor);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object get(final long id, final Continuation<? super TagEntity> $completion) {
    final String _sql = "SELECT * FROM tags WHERE id=?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<TagEntity>() {
      @Override
      @Nullable
      public TagEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final TagEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            _result = new TagEntity(_tmpId,_tmpName,_tmpColor);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<TagWithCount>> observeWithCount() {
    final String _sql = "\n"
            + "        SELECT t.id AS id, t.name AS name, t.color AS color, COUNT(nt.noteId) AS count\n"
            + "        FROM tags t LEFT JOIN note_tags nt ON nt.tagId = t.id\n"
            + "        GROUP BY t.id ORDER BY t.name\n"
            + "        ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"tags",
        "note_tags"}, new Callable<List<TagWithCount>>() {
      @Override
      @NonNull
      public List<TagWithCount> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = 0;
          final int _cursorIndexOfName = 1;
          final int _cursorIndexOfColor = 2;
          final int _cursorIndexOfCount = 3;
          final List<TagWithCount> _result = new ArrayList<TagWithCount>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final TagWithCount _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            final int _tmpCount;
            _tmpCount = _cursor.getInt(_cursorIndexOfCount);
            _item = new TagWithCount(_tmpId,_tmpName,_tmpColor,_tmpCount);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<TagEntity>> observeTagsOf(final long noteId) {
    final String _sql = "\n"
            + "        SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId=t.id\n"
            + "        WHERE nt.noteId=? ORDER BY t.name\n"
            + "        ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, noteId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"tags",
        "note_tags"}, new Callable<List<TagEntity>>() {
      @Override
      @NonNull
      public List<TagEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final List<TagEntity> _result = new ArrayList<TagEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final TagEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            _item = new TagEntity(_tmpId,_tmpName,_tmpColor);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<NoteEntity>> observeNotesWithTag(final long tagId) {
    final String _sql = "\n"
            + "        SELECT n.* FROM notes n JOIN note_tags nt ON nt.noteId=n.id\n"
            + "        WHERE nt.tagId=? AND n.archived=0 ORDER BY n.lastMessageTime DESC\n"
            + "        ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, tagId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"notes",
        "note_tags"}, new Callable<List<NoteEntity>>() {
      @Override
      @NonNull
      public List<NoteEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfBody = CursorUtil.getColumnIndexOrThrow(_cursor, "body");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfAuto = CursorUtil.getColumnIndexOrThrow(_cursor, "auto");
          final int _cursorIndexOfPinned = CursorUtil.getColumnIndexOrThrow(_cursor, "pinned");
          final int _cursorIndexOfArchived = CursorUtil.getColumnIndexOrThrow(_cursor, "archived");
          final int _cursorIndexOfTalker = CursorUtil.getColumnIndexOrThrow(_cursor, "talker");
          final int _cursorIndexOfTalkerTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "talkerTitle");
          final int _cursorIndexOfMessageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "messageCount");
          final int _cursorIndexOfPreview = CursorUtil.getColumnIndexOrThrow(_cursor, "preview");
          final int _cursorIndexOfLastMessageTime = CursorUtil.getColumnIndexOrThrow(_cursor, "lastMessageTime");
          final List<NoteEntity> _result = new ArrayList<NoteEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final NoteEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            if (_cursor.isNull(_cursorIndexOfTitle)) {
              _tmpTitle = null;
            } else {
              _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            }
            final String _tmpBody;
            if (_cursor.isNull(_cursorIndexOfBody)) {
              _tmpBody = null;
            } else {
              _tmpBody = _cursor.getString(_cursorIndexOfBody);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final boolean _tmpAuto;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfAuto);
            _tmpAuto = _tmp != 0;
            final boolean _tmpPinned;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfPinned);
            _tmpPinned = _tmp_1 != 0;
            final boolean _tmpArchived;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_2 != 0;
            final String _tmpTalker;
            if (_cursor.isNull(_cursorIndexOfTalker)) {
              _tmpTalker = null;
            } else {
              _tmpTalker = _cursor.getString(_cursorIndexOfTalker);
            }
            final String _tmpTalkerTitle;
            if (_cursor.isNull(_cursorIndexOfTalkerTitle)) {
              _tmpTalkerTitle = null;
            } else {
              _tmpTalkerTitle = _cursor.getString(_cursorIndexOfTalkerTitle);
            }
            final int _tmpMessageCount;
            _tmpMessageCount = _cursor.getInt(_cursorIndexOfMessageCount);
            final String _tmpPreview;
            if (_cursor.isNull(_cursorIndexOfPreview)) {
              _tmpPreview = null;
            } else {
              _tmpPreview = _cursor.getString(_cursorIndexOfPreview);
            }
            final long _tmpLastMessageTime;
            _tmpLastMessageTime = _cursor.getLong(_cursorIndexOfLastMessageTime);
            _item = new NoteEntity(_tmpId,_tmpTitle,_tmpBody,_tmpCreatedAt,_tmpUpdatedAt,_tmpAuto,_tmpPinned,_tmpArchived,_tmpTalker,_tmpTalkerTitle,_tmpMessageCount,_tmpPreview,_tmpLastMessageTime);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
