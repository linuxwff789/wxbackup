package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nous.wxhook.rootbridge.backup.BackupHookLocal
import kotlinx.coroutines.*

class RebuildReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.nous.wxhook.REBUILD") return
        Log.e("wxhook:rebuild", "RebuildReceiver triggered")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val result = runCatching {
                BackupHookLocal.rebuildDbState()
            }.getOrElse { "rebuild failed: ${it.message}" }
            Log.e("wxhook:rebuild", "rebuild result: $result")
        }
        // Keep the receiver alive by using goAsync
        val pending = goAsync()
        scope.launch {
            delay(120_000)
            pending.finish()
        }
    }
}
