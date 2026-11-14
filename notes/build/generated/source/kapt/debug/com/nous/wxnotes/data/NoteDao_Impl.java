package com.nous.wxnotes.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
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
public final class NoteDao_Impl implements NoteDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<NoteEntity> __insertionAdapterOfNoteEntity;

  private final EntityDeletionOrUpdateAdapter<NoteEntity> __updateAdapterOfNoteEntity;

  private final SharedSQLiteStatement __preparedStmtOfDelete;

  public NoteDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfNoteEntity = new EntityInsertionAdapter<NoteEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `notes` (`id`,`title`,`body`,`createdAt`,`updatedAt`,`auto`,`pinned`,`archived`,`talker`,`talkerTitle`,`messageCount`,`preview`,`lastMessageTime`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final NoteEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getTitle() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getTitle());
        }
        if (entity.getBody() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getBody());
        }
        statement.bindLong(4, entity.getCreatedAt());
        statement.bindLong(5, entity.getUpdatedAt());
        final int _tmp = entity.getAuto() ? 1 : 0;
        statement.bindLong(6, _tmp);
        final int _tmp_1 = entity.getPinned() ? 1 : 0;
        statement.bindLong(7, _tmp_1);
        final int _tmp_2 = entity.getArchived() ? 1 : 0;
        statement.bindLong(8, _tmp_2);
        if (entity.getTalker() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getTalker());
        }
        if (entity.getTalkerTitle() == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.getTalkerTitle());
        }
        statement.bindLong(11, entity.getMessageCount());
        if (entity.getPreview() == null) {
          statement.bindNull(12);
        } else {
          statement.bindString(12, entity.getPreview());
        }
        statement.bindLong(13, entity.getLastMessageTime());
      }
    };
    this.__updateAdapterOfNoteEntity = new EntityDeletionOrUpdateAdapter<NoteEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `notes` SET `id` = ?,`title` = ?,`body` = ?,`createdAt` = ?,`updatedAt` = ?,`auto` = ?,`pinned` = ?,`archived` = ?,`talker` = ?,`talkerTitle` = ?,`messageCount` = ?,`preview` = ?,`lastMessageTime` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final NoteEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getTitle() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getTitle());
        }
        if (entity.getBody() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getBody());
        }
        statement.bindLong(4, entity.getCreatedAt());
        statement.bindLong(5, entity.getUpdatedAt());
        final int _tmp = entity.getAuto() ? 1 : 0;
        statement.bindLong(6, _tmp);
        final int _tmp_1 = entity.getPinned() ? 1 : 0;
        statement.bindLong(7, _tmp_1);
        final int _tmp_2 = entity.getArchived() ? 1 : 0;
        statement.bindLong(8, _tmp_2);
        if (entity.getTalker() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getTalker());
        }
        if (entity.getTalkerTitle() == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.getTalkerTitle());
        }
        statement.bindLong(11, entity.getMessageCount());
        if (entity.getPreview() == null) {
          statement.bindNull(12);
        } else {
          statement.bindString(12, entity.getPreview());
        }
        statement.bindLong(13, entity.getLastMessageTime());
        statement.bindLong(14, entity.getId());
      }
    };
    this.__preparedStmtOfDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM notes WHERE id=?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final NoteEntity note, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfNoteEntity.insertAndReturnId(note);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final NoteEntity note, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfNoteEntity.handle(note);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
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
  public Object get(final long id, final Continuation<? super NoteEntity> $completion) {
    final String _sql = "SELECT * FROM notes WHERE id=?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<NoteEntity>() {
      @Override
      @Nullable
      public NoteEntity call() throws Exception {
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
          final NoteEntity _result;
          if (_cursor.moveToFirst()) {
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
            _result = new NoteEntity(_tmpId,_tmpTitle,_tmpBody,_tmpCreatedAt,_tmpUpdatedAt,_tmpAuto,_tmpPinned,_tmpArchived,_tmpTalker,_tmpTalkerTitle,_tmpMessageCount,_tmpPreview,_tmpLastMessageTime);
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
  public Flow<NoteEntity> observe(final long id) {
    final String _sql = "SELECT * FROM notes WHERE id=?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"notes"}, new Callable<NoteEntity>() {
      @Override
      @Nullable
      public NoteEntity call() throws Exception {
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
          final NoteEntity _result;
          if (_cursor.moveToFirst()) {
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
            _result = new NoteEntity(_tmpId,_tmpTitle,_tmpBody,_tmpCreatedAt,_tmpUpdatedAt,_tmpAuto,_tmpPinned,_tmpArchived,_tmpTalker,_tmpTalkerTitle,_tmpMessageCount,_tmpPreview,_tmpLastMessageTime);
          } else {
            _result = null;
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
  public Flow<List<NoteEntity>> observeAll(final String q) {
    final String _sql = "\n"
            + "        SELECT * FROM notes\n"
            + "        WHERE archived = 0 AND (\n"
            + "            ? = '' OR title LIKE '%' || ? || '%' OR body LIKE '%' || ? || '%'\n"
            + "            OR preview LIKE '%' || ? || '%' OR talkerTitle LIKE '%' || ? || '%'\n"
            + "        )\n"
            + "        ORDER BY pinned DESC, lastMessageTime DESC\n"
            + "        ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 5);
    int _argIndex = 1;
    if (q == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, q);
    }
    _argIndex = 2;
    if (q == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, q);
    }
    _argIndex = 3;
    if (q == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, q);
    }
    _argIndex = 4;
    if (q == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, q);
    }
    _argIndex = 5;
    if (q == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, q);
    }
    return CoroutinesRoom.createFlow(__db, false, new String[] {"notes"}, new Callable<List<NoteEntity>>() {
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

  @Override
  public Object findAggregateTarget(final String talker, final long since,
      final Continuation<? super NoteEntity> $completion) {
    final String _sql = "\n"
            + "        SELECT * FROM notes WHERE archived = 0 AND talker = ?\n"
            + "          AND auto = 1 AND updatedAt >= ?\n"
            + "        ORDER BY updatedAt DESC LIMIT 1\n"
            + "        ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    if (talker == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, talker);
    }
    _argIndex = 2;
    _statement.bindLong(_argIndex, since);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<NoteEntity>() {
      @Override
      @Nullable
      public NoteEntity call() throws Exception {
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
          final NoteEntity _result;
          if (_cursor.moveToFirst()) {
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
            _result = new NoteEntity(_tmpId,_tmpTitle,_tmpBody,_tmpCreatedAt,_tmpUpdatedAt,_tmpAuto,_tmpPinned,_tmpArchived,_tmpTalker,_tmpTalkerTitle,_tmpMessageCount,_tmpPreview,_tmpLastMessageTime);
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
  public Flow<List<NoteEntity>> observeArchived(final boolean archived) {
    final String _sql = "SELECT * FROM notes WHERE archived=? ORDER BY updatedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    final int _tmp = archived ? 1 : 0;
    _statement.bindLong(_argIndex, _tmp);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"notes"}, new Callable<List<NoteEntity>>() {
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
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfAuto);
            _tmpAuto = _tmp_1 != 0;
            final boolean _tmpPinned;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfPinned);
            _tmpPinned = _tmp_2 != 0;
            final boolean _tmpArchived;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_3 != 0;
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

  @Override
  public Object getAll(final Continuation<? super List<NoteEntity>> $completion) {
    final String _sql = "SELECT * FROM notes ORDER BY updatedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<NoteEntity>>() {
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
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<NoteEntity>> observeByTalker(final String talker) {
    final String _sql = "SELECT * FROM notes WHERE talker=? ORDER BY lastMessageTime DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (talker == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, talker);
    }
    return CoroutinesRoom.createFlow(__db, false, new String[] {"notes"}, new Callable<List<NoteEntity>>() {
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
