package com.nous.wxhook.root.libsu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nous.wxhook.core.command.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootManager {
    private var service: WxRootBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder as? WxRootBinder
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (bound && service != null) return@withContext true
        val intent = Intent(context, WxRootService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        // 等待连接
        repeat(50) {
            if (bound && service != null) return@withContext true
            Thread.sleep(100)
        }
        bound
    }

    suspend fun exec(command: String, timeoutMs: Long = 60_000): CommandResult =
        withContext(Dispatchers.IO) {
            val binder = service ?: return@withContext CommandResult(-1, "", "RootService not bound")
            try {
                val shell = (binder as IBinder)
                val result = WxRootBinder.exec(shell, command)
                CommandResult(
                    exitCode = result.code,
                    stdout = result.out.joinToString("\n"),
                    stderr = result.err.joinToString("\n"),
                    timedOut = false
                )
            } catch (e: Exception) {
                CommandResult(-1, "", e.toString())
            }
        }

    suspend fun execQuiet(command: String, timeoutMs: Long = 60_000): String =
        exec(command, timeoutMs).let { if (it.stdout.isNotBlank()) it.stdout else it.stderr }

    fun disconnect(context: Context) {
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
        }
    }
}
