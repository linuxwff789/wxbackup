package com.nous.wxhook.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.File

/** SQLCipher CLI path inside Termux. */
private const val SQLCIPHER = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"

/**
 * WeChat EnMicroMsg.db tools.
 *
 * PRAGMA key = e9cd2ae
 * cipher_compatibility = 3, page_size = 1024, kdf_iter = 4000, hmac = OFF
 */
object WeChatDbDecryptor {

    private const val TAG = "wxhook:Decryptor"
    private const val DB_NAME = "EnMicroMsg.db"
    private const val DB_HASH_DIR = "6d1f34a5edc49e8b6d238141b2d004f3"

    data class DbConfig(
        val key: String = "e9cd2ae",
        val cipherCompatibility: Int = 3,
        val pageSize: Int = 1024,
        val kdfIter: Int = 4000,
        val hmacEnabled: Boolean = false
    )

    data class DbSnapshot(
        val dbFile: File,
        val walFile: File,
        val shmFile: File,
        val key: String,
        val tableCount: Int = 0,
        val messageCount: Long = 0
    )

    private fun prg(key: String) = """
PRAGMA key='$key';
PRAGMA cipher_compatibility=3;
PRAGMA cipher_page_size=1024;
PRAGMA kdf_iter=4000;
PRAGMA cipher_use_hmac=OFF;
""".trimIndent()

    // ── CLI-based helpers (preferred) ──────────────────────────

    /**
     * Run a single SQL query against a decrypted DB via sqlcipher CLI.
     * Returns the last line of output, or null on failure.
     */
    fun cliQuery(dbPath: String, query: String, key: String = "e9cd2ae"): String? {
        return try {
            val sql = prg(key) + query
            val tmp = File("/data/local/tmp/_q_tmp.sql").apply { writeText(sql) }
            val out = RootCommandRunner.runSuQuiet("$SQLCIPHER '$dbPath' < '${tmp.absolutePath}' 2>/dev/null | tail -1").trim()
            tmp.delete()
            out.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "CLI query failed: ${e.message}"); null
        }
    }

    /** Count rows in the given table via CLI. */
    fun cliCount(dbPath: String, table: String = "message", key: String = "e9cd2ae"): Long {
        val r = cliQuery(dbPath, "SELECT count(*) FROM $table;", key)
        return r?.toLongOrNull() ?: -1L
    }

    /** Get row counts for known tables via CLI. */
    fun cliGetStats(dbPath: String, key: String = "e9cd2ae"): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        for (t in listOf("message", "rcontact", "rconversation", "ImgInfo2", "VoiceInfo")) {
            stats[t] = cliCount(dbPath, t, key)
        }
        return stats
    }

    // ── Legacy Android-SQLite methods (won't work on encrypted DB) ──

    /**
     * Opens a decrypted DB via Android SQLiteDatabase.
     * ⚠ This does NOT support SQLCipher and will fail on encrypted DBs.
     * Use cliQuery / cliCount instead.
     */
    @Deprecated("Use cliQuery — Android SQLite cannot open SQLCipher DBs")
    fun openDecryptedDb(dbPath: String, config: DbConfig = DbConfig()): SQLiteDatabase? {
        Log.w(TAG, "openDecryptedDb() uses Android SQLite — won't open SQLCipher DB. Use cliQuery instead.")
        return try {
            val sql = buildString {
                append("PRAGMA key = '${config.key}'; ")
                append("PRAGMA cipher_compatibility = ${config.cipherCompatibility}; ")
                append("PRAGMA cipher_page_size = ${config.pageSize}; ")
                append("PRAGMA kdf_iter = ${config.kdfIter}; ")
                if (!config.hmacEnabled) append("PRAGMA cipher_use_hmac = OFF; ")
            }
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY, null)
            val cursor = db.rawQuery("$sql SELECT count(*) FROM sqlite_master", null)
            if (cursor.moveToFirst()) {
                Log.i(TAG, "Decrypted DB: ${cursor.getInt(0)} tables")
                cursor.close(); db
            } else {
                cursor.close(); db.close()
                Log.e(TAG, "Failed to decrypt DB"); null
            }
        } catch (e: Exception) { Log.e(TAG, "Decrypt failed: ${e.message}"); null }
    }

    @Deprecated("Use cliQuery — Android SQLite cannot open SQLCipher DBs")
    fun queryMessages(db: SQLiteDatabase, talker: String, limit: Int = 100, offset: Int = 0): List<Map<String, Any?>> {
        val msgs = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT localId,TalkerId,msgSvrID,type,subType,content,isSend,createTime,talker,imgPath " +
            "FROM message WHERE talker=? ORDER BY createTime DESC LIMIT ? OFFSET ?",
            arrayOf(talker, limit.toString(), offset.toString())
        )
        while (cursor.moveToNext()) msgs.add(mapOf(
            "msgId" to cursor.getLong(0), "talkerId" to cursor.getLong(1),
            "msgSvrId" to cursor.getLong(2), "type" to cursor.getInt(3),
            "subType" to cursor.getInt(4), "content" to cursor.getString(5),
            "isSend" to (cursor.getInt(6) == 1), "createTime" to cursor.getLong(7),
            "talker" to cursor.getString(8), "imgPath" to cursor.getString(9)
        ))
        cursor.close(); return msgs
    }

    @Deprecated("Use cliQuery — Android SQLite cannot open SQLCipher DBs")
    fun queryConversations(db: SQLiteDatabase): List<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery("SELECT cusername,md_msg_seq,unReadCount FROM rconversation ORDER BY md_msg_seq DESC", null)
        while (cursor.moveToNext()) list.add(mapOf("username" to cursor.getString(0), "lastMsgSeq" to cursor.getLong(1), "unreadCount" to cursor.getInt(2)))
        cursor.close(); return list
    }

    @Deprecated("Use cliQuery — Android SQLite cannot open SQLCipher DBs")
    fun queryContacts(db: SQLiteDatabase): List<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery("SELECT username,nickname,alias,type FROM rcontact WHERE type IN (1,2,3,33) ORDER BY nickname ASC", null)
        while (cursor.moveToNext()) list.add(mapOf("username" to cursor.getString(0), "nickname" to cursor.getString(1), "alias" to cursor.getString(2), "type" to cursor.getInt(3)))
        cursor.close(); return list
    }

    @Deprecated("Use cliGetStats — Android SQLite cannot open SQLCipher DBs")
    fun getStats(db: SQLiteDatabase): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        for (t in listOf("message", "rcontact", "rconversation", "ImgInfo2", "VoiceInfo")) {
            try { val c = db.rawQuery("SELECT count(*) FROM $t", null); if (c.moveToFirst()) stats[t] = c.getLong(0); c.close() } catch (_: Exception) { stats[t] = -1L }
        }
        return stats
    }
}