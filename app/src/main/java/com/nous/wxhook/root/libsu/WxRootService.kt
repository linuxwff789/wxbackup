package com.nous.wxhook.root.libsu

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService

class WxRootService : RootService() {

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // 进入 root 进程，UID=0，直接可访问 /data/data/com.tencent.mm/
    }

    companion object {
        private val binder = WxRootBinder()

        init {
            // 配置 Shell
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }
    }
}
