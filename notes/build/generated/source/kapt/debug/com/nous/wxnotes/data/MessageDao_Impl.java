package com.nous.wxnotes.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Boolean;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
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
public final class MessageDao_Impl implements MessageDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<MessageEntity> __insertionAdapterOfMessageEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteByNote;

  public MessageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMessageEntity = new EntityInsertionAdapter<MessageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `messages` (`id`,`noteId`,`dedupeKey`,`msgId`,`msgSvrId`,`talker`,`talkerTitle`,`sender`,`senderName`,`type`,`subType`,`createTime`,`isSend`,`text`,`rawXml`,`mediaKind`,`mediaPath`,`fileName`,`fileSize`,`durationMs`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final MessageEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getNoteId());
        if (entity.getDedupeKey() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getDedupeKey());
        }
        statement.bindLong(4, entity.getMsgId());
        statement.bindLong(5, entity.getMsgSvrId());
        if (entity.getTalker() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getTalker());
        }
        if (entity.getTalkerTitle() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getTalkerTitle());
        }
        if (entity.getSender() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getSender());
        }
        if (entity.getSenderName() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getSenderName());
        }
        statement.bindLong(10, entity.getType());
        statement.bindLong(11, entity.getSubType());
        statement.bindLong(12, entity.getCreateTime());
        final int _tmp = entity.isSend() ? 1 : 0;
        statement.bindLong(13, _tmp);
        if (entity.getText() == null) {
          statement.bindNull(14);
        } else {
          statement.bindString(14, entity.getText());
        }
        if (entity.getRawXml() == null) {
          statement.bindNull(15);
        } else {
          statement.bindString(15, entity.getRawXml());
        }
        if (entity.getMediaKind() == null) {
          statement.bindNull(16);
        } else {
          statement.bindString(16, entity.getMediaKind());
        }
        if (entity.getMediaPath() == null) {
          statement.bindNull(17);
        } else {
          statement.bindString(17, entity.getMediaPath());
        }
        if (entity.getFileName() == null) {
          statement.bindNull(18);
        } else {
          statement.bindString(18, entity.getFileName());
        }
        statement.bindLong(19, entity.getFileSize());
        statement.bindLong(20, entity.getDurationMs());
      }
    };
    this.__preparedStmtOfDeleteByNote = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM messages WHERE noteId=?";
        return _query;
      }
    };
  }

  @Override
  public Object insertIgnore(final MessageEntity msg,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfMessageEntity.insertAndReturnId(msg);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertAllIgnore(final List<MessageEntity> msgs,
      final Continuation<? super List<Long>> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<List<Long>>() {
      @Override
      @NonNull
      public List<Long> call() throws Exception {
        __db.beginTransaction();
        try {
          final List<Long> _result = __insertionAdapterOfMessageEntity.insertAndReturnIdsList(msgs);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteByNote(final long noteId, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteByNote.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, noteId);
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
          __preparedStmtOfDeleteByNote.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<MessageEntity>> observeByNote(final long noteId) {
    final String _sql = "SELECT * FROM messages WHERE noteId=? ORDER BY createTime ASC, id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, noteId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"messages"}, new Callable<List<MessageEntity>>() {
      @Override
      @NonNull
      public List<MessageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfNoteId = CursorUtil.getColumnIndexOrThrow(_cursor, "noteId");
          final int _cursorIndexOfDedupeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dedupeKey");
          final int _cursorIndexOfMsgId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgId");
          final int _cursorIndexOfMsgSvrId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgSvrId");
          final int _cursorIndexOfTalker = CursorUtil.getColumnIndexOrThrow(_cursor, "talker");
          final int _cursorIndexOfTalkerTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "talkerTitle");
          final int _cursorIndexOfSender = CursorUtil.getColumnIndexOrThrow(_cursor, "sender");
          final int _cursorIndexOfSenderName = CursorUtil.getColumnIndexOrThrow(_cursor, "senderName");
          final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
          final int _cursorIndexOfSubType = CursorUtil.getColumnIndexOrThrow(_cursor, "subType");
          final int _cursorIndexOfCreateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createTime");
          final int _cursorIndexOfIsSend = CursorUtil.getColumnIndexOrThrow(_cursor, "isSend");
          final int _cursorIndexOfText = CursorUtil.getColumnIndexOrThrow(_cursor, "text");
          final int _cursorIndexOfRawXml = CursorUtil.getColumnIndexOrThrow(_cursor, "rawXml");
          final int _cursorIndexOfMediaKind = CursorUtil.getColumnIndexOrThrow(_cursor, "mediaKind");
          final int _cursorIndexOfMediaPath = CursorUtil.getColumnIndexOrThrow(_cursor, "mediaPath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "durationMs");
          final List<MessageEntity> _result = new ArrayList<MessageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final MessageEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpNoteId;
            _tmpNoteId = _cursor.getLong(_cursorIndexOfNoteId);
            final String _tmpDedupeKey;
            if (_cursor.isNull(_cursorIndexOfDedupeKey)) {
              _tmpDedupeKey = null;
            } else {
              _tmpDedupeKey = _cursor.getString(_cursorIndexOfDedupeKey);
            }
            final long _tmpMsgId;
            _tmpMsgId = _cursor.getLong(_cursorIndexOfMsgId);
            final long _tmpMsgSvrId;
            _tmpMsgSvrId = _cursor.getLong(_cursorIndexOfMsgSvrId);
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
            final String _tmpSender;
            if (_cursor.isNull(_cursorIndexOfSender)) {
              _tmpSender = null;
            } else {
              _tmpSender = _cursor.getString(_cursorIndexOfSender);
            }
            final String _tmpSenderName;
            if (_cursor.isNull(_cursorIndexOfSenderName)) {
              _tmpSenderName = null;
            } else {
              _tmpSenderName = _cursor.getString(_cursorIndexOfSenderName);
            }
            final int _tmpType;
            _tmpType = _cursor.getInt(_cursorIndexOfType);
            final int _tmpSubType;
            _tmpSubType = _cursor.getInt(_cursorIndexOfSubType);
            final long _tmpCreateTime;
            _tmpCreateTime = _cursor.getLong(_cursorIndexOfCreateTime);
            final boolean _tmpIsSend;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsSend);
            _tmpIsSend = _tmp != 0;
            final String _tmpText;
            if (_cursor.isNull(_cursorIndexOfText)) {
              _tmpText = null;
            } else {
              _tmpText = _cursor.getString(_cursorIndexOfText);
            }
            final String _tmpRawXml;
            if (_cursor.isNull(_cursorIndexOfRawXml)) {
              _tmpRawXml = null;
            } else {
              _tmpRawXml = _cursor.getString(_cursorIndexOfRawXml);
            }
            final String _tmpMediaKind;
            if (_cursor.isNull(_cursorIndexOfMediaKind)) {
              _tmpMediaKind = null;
            } else {
              _tmpMediaKind = _cursor.getString(_cursorIndexOfMediaKind);
            }
            final String _tmpMediaPath;
            if (_cursor.isNull(_cursorIndexOfMediaPath)) {
              _tmpMediaPath = null;
            } else {
              _tmpMediaPath = _cursor.getString(_cursorIndexOfMediaPath);
            }
            final String _tmpFileName;
            if (_cursor.isNull(_cursorIndexOfFileName)) {
              _tmpFileName = null;
            } else {
              _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final long _tmpDurationMs;
            _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs);
            _item = new MessageEntity(_tmpId,_tmpNoteId,_tmpDedupeKey,_tmpMsgId,_tmpMsgSvrId,_tmpTalker,_tmpTalkerTitle,_tmpSender,_tmpSenderName,_tmpType,_tmpSubType,_tmpCreateTime,_tmpIsSend,_tmpText,_tmpRawXml,_tmpMediaKind,_tmpMediaPath,_tmpFileName,_tmpFileSize,_tmpDurationMs);
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
  public Object getByNote(final long noteId,
      final Continuation<? super List<MessageEntity>> $completion) {
    final String _sql = "SELECT * FROM messages WHERE noteId=? ORDER BY createTime ASC, id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, noteId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<MessageEntity>>() {
      @Override
      @NonNull
      public List<MessageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfNoteId = CursorUtil.getColumnIndexOrThrow(_cursor, "noteId");
          final int _cursorIndexOfDedupeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dedupeKey");
          final int _cursorIndexOfMsgId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgId");
          final int _cursorIndexOfMsgSvrId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgSvrId");
          final int _cursorIndexOfTalker = CursorUtil.getColumnIndexOrThrow(_cursor, "talker");
          final int _cursorIndexOfTalkerTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "talkerTitle");
          final int _cursorIndexOfSender = CursorUtil.getColumnIndexOrThrow(_cursor, "sender");
          final int _cursorIndexOfSenderName = CursorUtil.getColumnIndexOrThrow(_cursor, "senderName");
          final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
          final int _cursorIndexOfSubType = CursorUtil.getColumnIndexOrThrow(_cursor, "subType");
          final int _cursorIndexOfCreateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createTime");
          final int _cursorIndexOfIsSend = CursorUtil.getColumnIndexOrThrow(_cursor, "isSend");
          final int _cursorIndexOfText = CursorUtil.getColumnIndexOrThrow(_cursor, "text");
          final int _cursorIndexOfRawXml = CursorUtil.getColumnIndexOrThrow(_cursor, "rawXml");
          final int _cursorIndexOfMediaKind = CursorUtil.getColumnIndexOrThrow(_cursor, "mediaKind");
          final int _cursorIndexOfMediaPath = CursorUtil.getColumnIndexOrThrow(_cursor, "mediaPath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "durationMs");
          final List<MessageEntity> _result = new ArrayList<MessageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final MessageEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpNoteId;
            _tmpNoteId = _cursor.getLong(_cursorIndexOfNoteId);
            final String _tmpDedupeKey;
            if (_cursor.isNull(_cursorIndexOfDedupeKey)) {
              _tmpDedupeKey = null;
            } else {
              _tmpDedupeKey = _cursor.getString(_cursorIndexOfDedupeKey);
            }
            final long _tmpMsgId;
            _tmpMsgId = _cursor.getLong(_cursorIndexOfMsgId);
            final long _tmpMsgSvrId;
            _tmpMsgSvrId = _cursor.getLong(_cursorIndexOfMsgSvrId);
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
            final String _tmpSender;
            if (_cursor.isNull(_cursorIndexOfSender)) {
              _tmpSender = null;
            } else {
              _tmpSender = _cursor.getString(_cursorIndexOfSender);
            }
            final String _tmpSenderName;
            if (_cursor.isNull(_cursorIndexOfSenderName)) {
              _tmpSenderName = null;
            } else {
              _tmpSenderName = _cursor.getString(_cursorIndexOfSenderName);
            }
            final int _tmpType;
            _tmpType = _cursor.getInt(_cursorIndexOfType);
            final int _tmpSubType;
            _tmpSubType = _cursor.getInt(_cursorIndexOfSubType);
            final long _tmpCreateTime;
            _tmpCreateTime = _cursor.getLong(_cursorIndexOfCreateTime);
            final boolean _tmpIsSend;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsSend);
            _tmpIsSend = _tmp != 0;
            final String _tmpText;
            if (_cursor.isNull(_cursorIndexOfText)) {
              _tmpText = null;
            } else {
              _tmpText = _cursor.getString(_cursorIndexOfText);
            }
            final String _tmpRawXml;
            if (_cursor.isNull(_cursorIndexOfRawXml)) {
              _tmpRawXml = null;
            } else {
              _tmpRawXml = _cursor.getString(_cursorIndexOfRawXml);
            }
            final String _tmpMediaKind;
            if (_cursor.isNull(_cursorIndexOfMediaKind)) {
              _tmpMediaKind = null;
            } else {
              _tmpMediaKind = _cursor.getString(_cursorIndexOfMediaKind);
            }
            final String _tmpMediaPath;
            if (_cursor.isNull(_cursorIndexOfMediaPath)) {
              _tmpMediaPath = null;
            } else {
              _tmpMediaPath = _cursor.getString(_cursorIndexOfMediaPath);
            }
            final String _tmpFileName;
            if (_cursor.isNull(_cursorIndexOfFileName)) {
              _tmpFileName = null;
            } else {
              _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final long _tmpDurationMs;
            _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs);
            _item = new MessageEntity(_tmpId,_tmpNoteId,_tmpDedupeKey,_tmpMsgId,_tmpMsgSvrId,_tmpTalker,_tmpTalkerTitle,_tmpSender,_tmpSenderName,_tmpType,_tmpSubType,_tmpCreateTime,_tmpIsSend,_tmpText,_tmpRawXml,_tmpMediaKind,_tmpMediaPath,_tmpFileName,_tmpFileSize,_tmpDurationMs);
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
  public Object countByNote(final long noteId, final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM messages WHERE noteId=?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, noteId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final Integer _tmp;
            if (_cursor.isNull(0)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getInt(0);
            }
            _result = _tmp;
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
  public Object search(final String q,
      final Continuation<? super List<MessageEntity>> $completion) {
    final String _sql = "\n"
            + "        SELECT * FROM messages WHERE text LIKE '%' || ? || '%' OR senderName LIKE '%' || ? || '%'\n"
            + "        ORDER BY createTime DESC LIMIT 300\n"
            + "        ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
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
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<MessageEntity>>() {
      @Override
      @NonNull
      public List<MessageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfNoteId = CursorUtil.getColumnIndexOrThrow(_cursor, "noteId");
          final int _cursorIndexOfDedupeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dedupeKey");
          final int _cursorIndexOfMsgId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgId");
          final int _cursorIndexOfMsgSvrId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgSvrId");
          final int _cursorIndexOfTalker = CursorUtil.getColumnIndexOrThrow(_cursor, "talker");
          final int _cursorIndexOfTalkerTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "talkerTitle");
          final int _cursorIndexOfSender = CursorUtil.getColumnIndexOrThrow(_cursor, "sender");
          final int _cursorIndexOfSenderName = CursorUtil.getColumnIndexOrThrow(_cursor, "senderName");
          final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
          final int _cursorIndexOfSubType = CursorUtil.getColumnIndexOrThrow(_cursor, "subType");
          final int _cursorIndexOfCreateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createTime");
          final int _cursorIndexOfIsSend = CursorUtil.getColumnIndexOrThrow(_cursor, "isSend");
          final int _cursorIndexOfText = CursorUtil.getColumnIndexOrThrow(_cursor, "text");
          final int _cursorIndexOfRawXml = CursorUtil.getColumnIndexOrThrow(_cursor, "rawXml");
          final int _cursorIndexOfMediaKind = CursorUtil.getColumnIndexOrThrow(_cursor, "mediaKind");
          final int _cursorIndexOfMediaPath = CursorUtil.getColumnIndexOrThrow(_cursor, "mediaPath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "durationMs");
          final List<MessageEntity> _result = new ArrayList<MessageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final MessageEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpNoteId;
            _tmpNoteId = _cursor.getLong(_cursorIndexOfNoteId);
            final String _tmpDedupeKey;
            if (_cursor.isNull(_cursorIndexOfDedupeKey)) {
              _tmpDedupeKey = null;
            } else {
              _tmpDedupeKey = _cursor.getString(_cursorIndexOfDedupeKey);
            }
            final long _tmpMsgId;
            _tmpMsgId = _cursor.getLong(_cursorIndexOfMsgId);
            final long _tmpMsgSvrId;
            _tmpMsgSvrId = _cursor.getLong(_cursorIndexOfMsgSvrId);
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
            final String _tmpSender;
            if (_cursor.isNull(_cursorIndexOfSender)) {
              _tmpSender = null;
            } else {
              _tmpSender = _cursor.getString(_cursorIndexOfSender);
            }
            final String _tmpSenderName;
            if (_cursor.isNull(_cursorIndexOfSenderName)) {
              _tmpSenderName = null;
            } else {
              _tmpSenderName = _cursor.getString(_cursorIndexOfSenderName);
            }
            final int _tmpType;
            _tmpType = _cursor.getInt(_cursorIndexOfType);
            final int _tmpSubType;
            _tmpSubType = _cursor.getInt(_cursorIndexOfSubType);
            final long _tmpCreateTime;
            _tmpCreateTime = _cursor.getLong(_cursorIndexOfCreateTime);
            final boolean _tmpIsSend;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsSend);
            _tmpIsSend = _tmp != 0;
            final String _tmpText;
            if (_cursor.isNull(_cursorIndexOfText)) {
              _tmpText = null;
            } else {
              _tmpText = _cursor.getString(_cursorIndexOfText);
            }
            final String _tmpRawXml;
            if (_cursor.isNull(_cursorIndexOfRawXml)) {
              _tmpRawXml = null;
            } else {
              _tmpRawXml = _cursor.getString(_cursorIndexOfRawXml);
            }
            final String _tmpMediaKind;
            if (_cursor.isNull(_cursorIndexOfMediaKind)) {
              _tmpMediaKind = null;
            } else {
              _tmpMediaKind = _cursor.getString(_cursorIndexOfMediaKind);
            }
            final String _tmpMediaPath;
            if (_cursor.isNull(_cursorIndexOfMediaPath)) {
              _tmpMediaPath = null;
            } else {
              _tmpMediaPath = _cursor.getString(_cursorIndexOfMediaPath);
            }
            final String _tmpFileName;
            if (_cursor.isNull(_cursorIndexOfFileName)) {
              _tmpFileName = null;
            } else {
              _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final long _tmpDurationMs;
            _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs);
            _item = new MessageEntity(_tmpId,_tmpNoteId,_tmpDedupeKey,_tmpMsgId,_tmpMsgSvrId,_tmpTalker,_tmpTalkerTitle,_tmpSender,_tmpSenderName,_tmpType,_tmpSubType,_tmpCreateTime,_tmpIsSend,_tmpText,_tmpRawXml,_tmpMediaKind,_tmpMediaPath,_tmpFileName,_tmpFileSize,_tmpDurationMs);
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
  public Object exists(final String key, final Continuation<? super Boolean> $completion) {
    final String _sql = "SELECT EXISTS(SELECT 1 FROM messages WHERE dedupeKey=?)";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (key == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, key);
    }
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Boolean>() {
      @Override
      @NonNull
      public Boolean call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Boolean _result;
          if (_cursor.moveToFirst()) {
            final Integer _tmp;
            if (_cursor.isNull(0)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getInt(0);
            }
            _result = _tmp == null ? null : _tmp != 0;
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
