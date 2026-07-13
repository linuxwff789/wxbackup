package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KeyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "wxhook:KeyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val keyLen = intent.getIntExtra("keyLen", 0)
        val pageSize = intent.getStringExtra("pageSize") ?: "?"
        val version = intent.getStringExtra("version") ?: "?"
        val time = intent.getStringExtra("time") ?: ""

        Log.d(TAG, "Received key: $key (len=$keyLen)")

        // Save to wxhook's shared_preferences
        val prefs = context.getSharedPreferences("wxhook", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_key", key)
            .putInt("last_key_len", keyLen)
            .putLong("last_key_time", System.currentTimeMillis())
            .putString("page_size", pageSize)
            .putString("cipher_version", version)
            .apply()

        Log.d(TAG, "Key saved to shared_prefs: $key")
    }
}
