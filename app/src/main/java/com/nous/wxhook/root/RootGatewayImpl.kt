package com.nous.wxhook.root

import android.content.Context
import com.nous.wxhook.core.command.CommandResult
import com.nous.wxhook.core.command.ShellEscaper
import com.nous.wxhook.rootbridge.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootGatewayImpl(private val context: Context? = null) : RootGateway {

    private var useLibsu = false

    suspend fun initialize(): RootStatus {
        val status = check()
        if (status.available && context != null) {
            try {
                val connected = com.nous.wxhook.root.libsu.RootManager.ensureConnected(context)
                if (connected) useLibsu = true
            } catch (_: Exception) {}
        }
        return status
    }

    override suspend fun check(): RootStatus = withContext(Dispatchers.IO) {
        if (useLibsu) {
            val result = com.nous.wxhook.root.libsu.RootManager.exec("id -u", 5_000)
            if (result.isSuccess) {
                RootStatus(true, result.stdout.trim().toIntOrNull() ?: -1)
            } else {
                RootStatus(false, message = result.stderr)
            }
        } else {
            val r = RootCommandRunner.runSu("id -u", 5_000)
            if (r.isSuccess) {
                RootStatus(true, r.stdout.trim().toIntOrNull() ?: -1)
            } else {
                RootStatus(false, message = r.stderr)
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        run("test -e ${ShellEscaper.quote(path)} && echo 1 || echo 0").let {
            it.isSuccess && it.stdout.trim() == "1"
        }
    }

    override suspend fun stat(path: String): FileMetadata? = withContext(Dispatchers.IO) {
        val r = run("stat -c '%d %g %s %f' ${ShellEscaper.quote(path)} 2>/dev/null")
        if (!r.isSuccess) return@withContext null
        val parts = r.stdout.trim().split(" ")
        if (parts.size < 4) return@withContext null
        FileMetadata(
            path = path,
            exists = true,
            isDirectory = parts[3].first() == '4',
            uid = parts[0].toIntOrNull() ?: 0,
            gid = parts[1].toIntOrNull() ?: 0,
            size = parts[2].toLongOrNull() ?: 0,
            mode = parts[3],
        )
    }

    override suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        run("mkdir -p ${ShellEscaper.quote(path)}").isSuccess
    }

    override suspend fun copy(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        run("cp ${ShellEscaper.quote(src)} ${ShellEscaper.quote(dst)}").isSuccess
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        run("rm -f ${ShellEscaper.quote(path)}").isSuccess
    }

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            if (useLibsu) {
                com.nous.wxhook.root.libsu.RootManager.exec(command, timeoutMs)
            } else {
                RootCommandRunner.runSu(command, timeoutMs)
            }
        }

    override suspend fun runQuiet(command: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            if (useLibsu) {
                com.nous.wxhook.root.libsu.RootManager.execQuiet(command, timeoutMs)
            } else {
                RootCommandRunner.runSuQuiet(command, timeoutMs)
            }
        }
}
