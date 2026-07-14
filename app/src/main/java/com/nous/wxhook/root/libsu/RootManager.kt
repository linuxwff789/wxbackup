package com.nous.wxhook.root.libsu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nous.wxhook.core.command.CommandResult
import com.topjohnwu.superuser.ipc.RootService
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
        RootService.bind(intent, connection)
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

    // 文件操作 - 在 root 进程执行
    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try {
            WxRootBinder.writeFile(binder as IBinder, path, content) == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext ""
        try {
            WxRootBinder.readFile(binder as IBinder, path)
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try {
            WxRootBinder.mkdirs(binder as IBinder, path) == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try {
            WxRootBinder.fileExists(binder as IBinder, path)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext 0L
        try {
            WxRootBinder.fileSize(binder as IBinder, path)
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun copy(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try {
            WxRootBinder.copy(binder as IBinder, src, dst) == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try {
            WxRootBinder.delete(binder as IBinder, path) == 0
        } catch (e: Exception) {
            false
        }
    }

    fun disconnect(context: Context) {
        if (bound) {
            RootService.unbind(connection)
            bound = false
            service = null
        }
    }
}
