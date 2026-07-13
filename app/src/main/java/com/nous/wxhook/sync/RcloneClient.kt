package com.nous.wxhook.sync

import com.nous.wxhook.core.command.CommandResult
import com.nous.wxhook.core.command.ShellEscaper
import com.nous.wxhook.root.RootGateways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RcloneClient(
    private val binPath: String,
    private val configPath: String? = null,
) : CloudClient {

    private fun rcloneBase(): String {
        val env = "HOME=/data/local/tmp LD_LIBRARY_PATH=$binPath SSL_CERT_DIR=/system/etc/security/cacerts"
        return "$env $binPath/rclone"
    }

    private fun rcloneArgs(vararg extra: String): List<String> {
        val args = mutableListOf(rcloneBase())
        if (configPath != null) {
            args.add("--config")
            args.add(configPath)
        }
        args.addAll(extra)
        return args
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        if (configPath == null) return@withContext Result.failure(Exception("No config"))
        val result = RootGateways.run(
            rcloneArgs("lsd", "gdrive:").joinToString(" "), 15_000
        )
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(Exception("rclone exit=${result.exitCode}: ${result.stderr.take(200)}"))
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = RootGateways.run(
            rcloneArgs("mkdir", path).joinToString(" "), 10_000
        )
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(Exception("mkdir failed: ${result.stderr.take(200)}"))
    }

    override suspend fun upload(local: File, remote: String): Result<RemoteObject> =
        withContext(Dispatchers.IO) {
            val result = RootGateways.run(
                rcloneArgs("copy", local.absolutePath, remote, "--no-check-certificate", "--timeout=30s").joinToString(" "),
                120_000
            )
            if (result.isSuccess) {
                Result.success(RemoteObject(remote, local.length()))
            } else {
                Result.failure(Exception("upload failed(exit=${result.exitCode}): ${result.stderr.take(200)}"))
            }
        }

    override suspend fun download(remote: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            val result = RootGateways.run(
                rcloneArgs("copy", remote, local.absolutePath, "--no-check-certificate", "--timeout=30s").joinToString(" "),
                120_000
            )
            if (result.isSuccess) Result.success(Unit)
            else Result.failure(Exception("download failed: ${result.stderr.take(200)}"))
        }

    override suspend fun list(remote: String): Result<List<RemoteObject>> =
        withContext(Dispatchers.IO) {
            val result = RootGateways.run(
                rcloneArgs("ls", remote, "--no-check-certificate").joinToString(" "),
                30_000
            )
            if (!result.isSuccess) return@withContext Result.failure(Exception("list failed: ${result.stderr.take(200)}"))
            val objects = result.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex(), limit = 2)
                if (parts.size == 2) RemoteObject(parts[1], parts[0].toLongOrNull() ?: 0) else null
            }
            Result.success(objects)
        }

    override suspend fun delete(remote: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = RootGateways.run(
            rcloneArgs("delete", remote, "--no-check-certificate").joinToString(" "),
            30_000
        )
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(Exception("delete failed: ${result.stderr.take(200)}"))
    }
}
