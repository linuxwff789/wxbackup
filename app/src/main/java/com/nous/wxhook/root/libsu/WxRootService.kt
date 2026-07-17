package com.nous.wxhook.root.libsu

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService

class WxRootService : RootService() {

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("wxhook:Root", "WxRootService created, PID=${android.os.Process.myPid()}")
    }

    override fun onDestroy() {
        android.util.Log.w("wxhook:Root", "WxRootService destroyed — system may have killed it")
        super.onDestroy()
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
