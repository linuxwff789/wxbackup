package com.nous.wxhook

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.nous.wxhook.backup.BackupEnv
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class App : Application() {
    companion object {
        var instance: App? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Apply Dynamic Colors (Material You) on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Init shared paths (used by Syncer, ArchiveService, etc.)
        BackupEnv.filesDirPath = filesDir.absolutePath
        BackupEnv.binDir = "/data/local/tmp/wxhook_bin"

        // 初始化 RootGateway (带 context，支持 libsu)
        val gateway = RootGatewayImpl(this)
        RootGateways.set(gateway)

        // 异步初始化 libsu 连接
        GlobalScope.launch {
            gateway.initialize()
        }
    }
}
