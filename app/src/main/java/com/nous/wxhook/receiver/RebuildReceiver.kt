package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nous.wxhook.rootbridge.backup.BackupHookLocal

class RebuildReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.nous.wxhook.REBUILD") return
        android.util.Log.e("wxhook:rebuild", "RebuildReceiver triggered via broadcast")
        val pending = goAsync()
        Thread {
            try {
                val result = BackupHookLocal.rebuildDbState()
                android.util.Log.e("wxhook:rebuild", "rebuild result: $result")
            } catch (e: Throwable) {
                android.util.Log.e("wxhook:rebuild", "rebuild crashed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
