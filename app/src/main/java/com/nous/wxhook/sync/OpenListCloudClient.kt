package com.nous.wxhook.sync

import com.nous.wxhook.root.RootGateways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CloudClient adapter backed by openlist-cli via root shell.
 *
 * Uses the standalone Go CLI binary through RootGateways instead of JNI,
 * avoiding Go runtime conflicts with the Android JVM on some devices.
 */
class OpenListCloudClient(
    private val driverType: String,
    private val configJson: String,
) : CloudClient {

    private fun cliPath() = "/data/local/tmp/wxhook_bin/openlist-cli"

    private suspend fun cli(op: String, vararg args: String): String {
        return withContext(Dispatchers.IO) {
            val quotedArgs = args.joinToString(" ") { escape(it) }
            val cmd = "${cliPath()} $driverType ${escape(configJson)} $op $quotedArgs 2>&1"
            val result = RootGateways.run(cmd, 120_000)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                result.stdout
            } else {
                val err = result.stderr.ifBlank { result.stdout.ifBlank { "empty output" } }
                throw Exception(err.trim().lines().last())
            }
        }
    }

    private fun escape(s: String): String = "'${s.replace("'", "'\"'\"'")}'"

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try { cli("list", "/"); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            for (part in path.trim('/').split("/").filter { it.isNotBlank() }) {
                try { cli("list", "/$part") } catch (_: Exception) { cli("mkdir", "/", part) }
            }
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun upload(local: File, remotePath: String): Result<RemoteObject> {
        return withContext(Dispatchers.IO) {
            try {
                val parentPath = remotePath.substringBeforeLast("/", "").ifBlank { "/" }
                cli("upload", parentPath, local.absolutePath)
                Result.success(RemoteObject(remotePath, local.length(), System.currentTimeMillis()))
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun download(remotePath: String, local: File): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val url = cli("url", remotePath).trim()
                val request = okhttp3.Request.Builder().url(url).get().build()
                val response = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(request).execute()
                if (response.code in 200..299) {
                    response.body?.bytes()?.let { local.writeBytes(it) }
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("download failed: ${response.code}"))
                }
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun list(remotePath: String): Result<List<RemoteObject>> {
        return withContext(Dispatchers.IO) {
            try {
                val out = cli("list", remotePath)
                val items = out.lines().filter { it.isNotBlank() }.map { line ->
                    val parts = line.split("\t")
                    RemoteObject(
                        path = parts.getOrElse(0) { "" },
                        size = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                    )
                }
                Result.success(items)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try { cli("delete", remotePath); Result.success(Unit) }
            catch (e: Exception) { Result.failure(e) }
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
