package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nous.wxhook.service.BackupService

class RebuildReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.nous.wxhook.REBUILD") return
        android.util.Log.e("wxhook:rebuild", "RebuildReceiver: starting BackupService")
        BackupService.startRebuild(context)
    }
}
