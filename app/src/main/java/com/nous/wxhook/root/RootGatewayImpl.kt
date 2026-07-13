package com.nous.wxhook.root

import com.nous.wxhook.core.command.CommandResult
import com.nous.wxhook.core.command.ShellEscaper
import com.nous.wxhook.rootbridge.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootGatewayImpl : RootGateway {

    override suspend fun check(): RootStatus = withContext(Dispatchers.IO) {
        val r = RootCommandRunner.runSu("id -u", 5_000)
        if (r.isSuccess) {
            RootStatus(true, r.stdout.trim().toIntOrNull() ?: -1)
        } else {
            RootStatus(false, message = r.stderr)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val r = RootCommandRunner.runSu(
            "test -e ${ShellEscaper.quote(path)} && echo 1 || echo 0", 5_000
        )
        r.isSuccess && r.stdout.trim() == "1"
    }

    override suspend fun stat(path: String): FileMetadata? = withContext(Dispatchers.IO) {
        val r = RootCommandRunner.runSu(
            "stat -c '%d %g %s %f' ${ShellEscaper.quote(path)} 2>/dev/null", 5_000
        )
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
        RootCommandRunner.runSu(
            "mkdir -p ${ShellEscaper.quote(path)}", 10_000
        ).isSuccess
    }

    override suspend fun copy(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        RootCommandRunner.runSu(
            "cp ${ShellEscaper.quote(src)} ${ShellEscaper.quote(dst)}", 60_000
        ).isSuccess
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        RootCommandRunner.runSu(
            "rm -f ${ShellEscaper.quote(path)}", 10_000
        ).isSuccess
    }

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            RootCommandRunner.runSu(command, timeoutMs)
        }

    override suspend fun runQuiet(command: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            RootCommandRunner.runSuQuiet(command, timeoutMs)
        }
}
