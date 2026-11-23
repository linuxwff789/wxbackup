package com.nous.wxnotes.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nous.wxnotes.NotesApp
import com.nous.wxnotes.data.WebDavBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 调试/自动化入口（供 adb / Termux 触发，避免手动点 UI）：
 *   am broadcast -a com.nous.wxnotes.CMD -p com.nous.wxnotes --es cmd test_dav
 *   am broadcast -a com.nous.wxnotes.CMD -p com.nous.wxnotes --es cmd backup
 *   am broadcast -a com.nous.wxnotes.CMD -p com.nous.wxnotes --es cmd set_dav \
 *       --es url http://127.0.0.1:8099/ --es user u --es pass p --es dir wxnotes-backup
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        val prefs = context.getSharedPreferences("wxnotes", Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (cmd) {
                    "delete_note" -> {
                        val ids = intent.getStringExtra("ids").orEmpty()
                            .split(",").mapNotNull { it.trim().toLongOrNull() }
                        if (ids.isEmpty()) {
                            Log.w(TAG, "delete_note: ids required")
                        } else {
                            NotesApp.instance.repository.deleteNotes(ids)
                            Log.i(TAG, "deleted=${ids.joinToString(",")}")
                        }
                    }

                    "set_dav" -> {
                        prefs.edit()
                            .putString(WebDavBackup.KEY_URL, intent.getStringExtra("url").orEmpty())
                            .putString(WebDavBackup.KEY_USER, intent.getStringExtra("user").orEmpty())
                            .putString(WebDavBackup.KEY_PASS, intent.getStringExtra("pass").orEmpty())
                            .putString(WebDavBackup.KEY_DIR, intent.getStringExtra("dir") ?: WebDavBackup.DEFAULT_DIR)
                            .putInt(WebDavBackup.KEY_KEEP, intent.getIntExtra("keep", WebDavBackup.DEFAULT_KEEP))
                            .apply()
                        Log.i(TAG, "DAV_CONFIG ${WebDavBackup.loadConfig(context)}")
                    }
                    "test_dav" -> {
                        val r = WebDavBackup.testConnection(WebDavBackup.loadConfig(context))
                        Log.i(TAG, "DAV_TEST ok=${r.isSuccess} err=${r.exceptionOrNull()?.message}")
                    }
                    "backup" -> {
                        val cfg = WebDavBackup.loadConfig(context)
                        Log.i(TAG, "DAV_BACKUP start dir=${cfg.dir}")
                        val r = WebDavBackup.runBackup(context, cfg) { p ->
                            Log.i(TAG, "DAV_PROGRESS ${p.stage} ${p.done}/${p.total}")
                        }
                        Log.i(TAG, "DAV_BACKUP ok=${r.isSuccess} file=${r.getOrNull()} err=${r.exceptionOrNull()?.message}")
                    }
                    "list_dav" -> {
                        val r = WebDavBackup.listBackups(WebDavBackup.loadConfig(context))
                        Log.i(TAG, "DAV_LIST ok=${r.isSuccess} ${r.getOrNull()?.joinToString { "${it.name}:${it.size}" }}")
                    }
                    else -> Log.w(TAG, "unknown cmd=$cmd")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "DAV_CMD failed: $cmd", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "wxnotes-dav"
        const val ACTION = "com.nous.wxnotes.CMD"
    }
}
