package com.nous.wxhook.db

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

class WxHookProvider : ContentProvider() {

    companion object {
        private const val TAG = "wxhook:Provider"
        private var capturedKey: String? = null
        private var capturedTime: Long = 0

        fun setKey(key: String) {
            capturedKey = key
            capturedTime = System.currentTimeMillis()
        }

        fun getKey(): String? = capturedKey
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        return when (uri.path) {
            "/key" -> {
                val cursor = MatrixCursor(arrayOf("key", "time", "len"))
                var key = capturedKey
                var time = capturedTime
                if (key == null) {
                    // Fallback to SharedPreferences (persists across app restarts)
                    try {
                        val prefs = context?.getSharedPreferences("wxhook", android.content.Context.MODE_PRIVATE)
                        key = prefs?.getString("last_key", null)
                        time = prefs?.getLong("last_key_time", 0) ?: 0L
                    } catch (_: Exception) {}
                }
                if (key != null) {
                    cursor.addRow(arrayOf(key, time, key.length / 2))
                }
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.path == "/key" && values != null) {
            val key = values.getAsString("key")
            if (key != null) {
                setKey(key)

                // Save to shared_prefs with commit() (synchronous)
                try {
                    val prefs = context?.getSharedPreferences("wxhook", android.content.Context.MODE_PRIVATE)
                    val success = prefs?.edit()
                        ?.putString("last_key", key)
                        ?.putInt("last_key_len", key.length / 2)
                        ?.putLong("last_key_time", System.currentTimeMillis())
                        ?.commit()  // commit() is synchronous, apply() is async
                    Log.d(TAG, "Key saved")
                } catch (e: Exception) {
                    Log.e(TAG, "Save to prefs failed: ${e.message}")
                }
            }
        }
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?) = 0
}
