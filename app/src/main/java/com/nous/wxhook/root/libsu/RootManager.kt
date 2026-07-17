package com.nous.wxhook.root.libsu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.nous.wxhook.core.command.CommandResult
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RootManager {
    @Volatile private var service: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var binding = false
    @Volatile private var connectionLatch = CountDownLatch(0)
    private val bindLock = Any()

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w("wxhook:Root", "Binder died — clearing proxy")
        service = null
        bound = false
        binding = false
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service?.unlinkToDeath(deathRecipient, 0)
            binder?.linkToDeath(deathRecipient, 0)
            service = binder
            bound = binder != null
            binding = false
            connectionLatch.countDown()
            Log.i("wxhook:Root", "onServiceConnected binder=$bound component=$name")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            binding = false
            connectionLatch.countDown()
        }
    }

    suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Always attempt fresh bind — cached proxy may be dead
        val latch = synchronized(bindLock) {
            if (!binding) {
                binding = true
                connectionLatch = CountDownLatch(1)
                Log.i("wxhook:Root", "posting RootService.bind to main thread")
                Handler(Looper.getMainLooper()).post {
                    try {
                        RootService.bind(Intent(context, WxRootService::class.java), connection)
                    } catch (e: Exception) {
                        binding = false
                        connectionLatch.countDown()
                        Log.e("wxhook:Root", "RootService.bind failed on main thread", e)
                    }
                }
            }
            connectionLatch
        }

        latch.await(10, TimeUnit.SECONDS)
        if (!bound && service != null) {
            // bound may be false but proxy exists — try ping
            try {
                service?.transact(0xFE, android.os.Parcel.obtain(), null, android.os.IBinder.FLAG_ONEWAY)
            } catch (_: android.os.DeadObjectException) {
                service = null; bound = false
            }
        }
        bound && service != null
    }

    fun currentBinder(): IBinder? = service

    suspend fun exec(command: String, timeoutMs: Long = 60_000): CommandResult =
        withContext(Dispatchers.IO) {
            val binder = service ?: return@withContext CommandResult(-1, "", "RootService not bound")
            try {
                val result = WxRootBinder.exec(binder, command)
                CommandResult(
                    exitCode = result.code,
                    stdout = result.out.joinToString("\n"),
                    stderr = result.err.joinToString("\n"),
                    timedOut = false,
                )
            } catch (e: Exception) {
                CommandResult(-1, "", e.toString())
            }
        }

    suspend fun execQuiet(command: String, timeoutMs: Long = 60_000): String =
        exec(command, timeoutMs).let { if (it.stdout.isNotBlank()) it.stdout else it.stderr }

    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try { WxRootBinder.writeFile(binder, path, content) == 0 } catch (_: Exception) { false }
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext ""
        try { WxRootBinder.readFile(binder, path) } catch (_: Exception) { "" }
    }

    suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try { WxRootBinder.mkdirs(binder, path) == 0 } catch (_: Exception) { false }
    }

    suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try { WxRootBinder.fileExists(binder, path) } catch (_: Exception) { false }
    }

    suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext 0L
        try { WxRootBinder.fileSize(binder, path) } catch (_: Exception) { 0L }
    }

    suspend fun copy(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try { WxRootBinder.copy(binder, src, dst) == 0 } catch (_: Exception) { false }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val binder = service ?: return@withContext false
        try { WxRootBinder.delete(binder, path) == 0 } catch (_: Exception) { false }
    }

    fun disconnect(context: Context) {
        if (bound) RootService.unbind(connection)
        bound = false
        binding = false
        service = null
    }
}
