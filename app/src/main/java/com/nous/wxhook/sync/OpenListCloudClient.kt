package com.nous.wxhook.sync

import com.nous.wxhook.root.RootGateways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CloudClient adapter backed by openlist-cli (root shell).
 *
 * Uses the standalone CLI binary via RootGateways instead of JNI,
 * avoiding Go runtime conflict when loaded as a shared library.
 */
class OpenListCloudClient(
    private val driverType: String,
    private val configJson: String,
) : CloudClient {

    private val cliPath get() = "/data/local/tmp/wxhook_bin/openlist-cli"

    private suspend fun cli(op: String, vararg args: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val quoted = args.joinToString(" ") { escape(it) }
                val cmd = "$cliPath $driverType ${escape(configJson)} $op $quoted 2>&1"
                val result = RootGateways.run(cmd, 120_000)
                if (result.isSuccess && result.stdout.isNotBlank()) {
                    Result.success(result.stdout)
                } else {
                    val err = result.stderr.ifBlank { result.stdout.ifBlank { "empty output" } }
                    Result.failure(Exception(err.trim().lines().last()))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun escape(s: String): String {
        return "'${s.replace("'", "'\"'\"'")}'"
    }

    private fun parseListOutput(raw: String): List<RemoteObject> {
        val lines = raw.lines().filter { it.isNotBlank() }
        return lines.mapNotNull { line ->
            // Format: "name\tsize\tmodTime\tisDir"
            val parts = line.split("\t")
            if (parts.size >= 1) {
                RemoteObject(
                    path = parts[0],
                    size = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                    modTime = parts.getOrNull(2)?.toLongOrNull() ?: 0,
                )
            } else null
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val r = cli("list", "/")
            if (r.isSuccess) Result.success(Unit) else Result.failure(r.exceptionOrNull()!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val parts = path.trim('/').split("/").filter { it.isNotBlank() }
            var current = "/"
            for (part in parts) {
                val test = current.trimEnd('/') + "/" + part
                val r = cli("list", test)
                if (r.isFailure) {
                    cli("mkdir", current, part).getOrThrow()
                }
                current = test
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upload(local: File, remotePath: String): Result<RemoteObject> {
        return withContext(Dispatchers.IO) {
            try {
                val parentPath = remotePath.substringBeforeLast("/", "").ifBlank { "/" }
                val result = cli("upload", parentPath, local.absolutePath)
                if (result.isSuccess) {
                    val out = result.getOrThrow()
                    val size = local.length()
                    Result.success(RemoteObject(remotePath, size, System.currentTimeMillis()))
                } else {
                    Result.failure(result.exceptionOrNull()!!)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun download(remotePath: String, local: File): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Get download URL then fetch with OkHttp
                val urlResult = cli("url", remotePath)
                val url = urlResult.getOrThrow().trim()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .get()
                    .build()
                val response = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                if (response.code in 200..299) {
                    response.body?.bytes()?.let { local.writeBytes(it) }
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("download failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun list(remotePath: String): Result<List<RemoteObject>> {
        return withContext(Dispatchers.IO) {
            try {
                val r = cli("list", remotePath)
                r.map { parseListOutput(it) }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                cli("delete", remotePath).map { Unit }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    companion object {
        fun aliyunConfig(
            refreshToken: String,
            apiUrl: String = "https://api.oplist.org/alicloud/renewapi",
            rootFolderId: String = "root",
        ): String = JSONObject().apply {
            put("refresh_token", refreshToken)
            put("use_online_api", true)
            put("api_url_address", apiUrl)
            put("drive_type", "default")
            put("root_folder_id", rootFolderId)
            put("remove_way", "trash")
        }.toString()
    }
}
