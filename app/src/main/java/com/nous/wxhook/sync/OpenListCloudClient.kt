package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import openlistbridge.Openlistbridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CloudClient adapter backed by gomobile-compiled Go library.
 * Device initialization happens once on first use.
 */
class OpenListCloudClient(
    private val driverType: String,
    private val configJson: String,
) : CloudClient {

    private var initialized = false
    private var handle: String? = null

    private suspend fun ensureInit(): String {
        if (!initialized) {
            Openlistbridge.init()
            initialized = true
        }
        if (handle == null) {
            val result = withContext(Dispatchers.IO) { Openlistbridge.create(driverType, configJson) }
            val parsed = JSONObject(result)
            if (!parsed.optBoolean("success", false)) {
                throw Exception(parsed.optString("error", "create failed"))
            }
            handle = parsed.getJSONObject("data").getString("handle")
        }
        return handle!!
    }

    private suspend fun call(method: String, vararg args: String): String {
        val h = ensureInit()
        return withContext(Dispatchers.IO) {
            when (method) {
                "list" -> Openlistbridge.list(h, args[0])
                "url" -> Openlistbridge.getDownloadURL(h, args[0])
                "upload" -> Openlistbridge.upload(h, args[0], args[1], args[2], args.getOrElse(3) { "application/octet-stream" })
                "mkdir" -> Openlistbridge.mkdir(h, args[0], args[1])
                "delete" -> Openlistbridge.delete(h, args[0])
                else -> throw Exception("unknown method: $method")
            }
        }
    }

    private fun checkResult(json: String): JSONObject {
        val parsed = JSONObject(json)
        if (!parsed.optBoolean("success", false)) {
            throw Exception(parsed.optString("error", "unknown error"))
        }
        return parsed
    }

    private fun parseList(json: String): List<RemoteObject> {
        val parsed = checkResult(json)
        val arr = parsed.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            RemoteObject(
                path = obj.optString("path", ""),
                size = obj.optLong("size", 0),
                modTime = obj.optLong("modified_at", 0),
            )
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val r = call("list", "/")
            checkResult(r)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val parts = path.trim('/').split("/").filter { it.isNotBlank() }
            var current = "/"
            for (part in parts) {
                val testPath = current.trimEnd('/') + "/" + part
                val r = try { call("list", testPath); null } catch (_: Exception) { call("mkdir", current, part) }
                if (r != null) checkResult(r)
                current = testPath
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
                val fileName = remotePath.substringAfterLast("/", remotePath)
                val r = Openlistbridge.upload(handle ?: ensureInit(), parentPath, fileName, local.absolutePath, "application/octet-stream")
                val parsed = checkResult(r)
                val data = parsed.optJSONObject("data") ?: JSONObject()
                Result.success(RemoteObject(
                    path = remotePath,
                    size = data.optLong("size", local.length()),
                    modTime = data.optLong("modified_at", System.currentTimeMillis()),
                ))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun download(remotePath: String, local: File): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val r = call("url", remotePath)
                val parsed = checkResult(r)
                val url = parsed.getJSONObject("data").getString("url")
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
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun list(remotePath: String): Result<List<RemoteObject>> {
        return withContext(Dispatchers.IO) {
            try {
                val r = call("list", remotePath)
                Result.success(parseList(r))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val r = call("delete", remotePath)
                checkResult(r)
                Result.success(Unit)
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
