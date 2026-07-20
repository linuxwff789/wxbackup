package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import openlistbridge.Openlistbridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CloudClient adapter backed by gomobile-compiled Go JNI library.
 *
 * Uses the full openlistbridge.aar (go.Seq + Openlistbridge class)
 * for correct Go runtime bootstrap on Android.
 */
class OpenListCloudClient(
    private val driverType: String,
    private val configJson: String,
) : CloudClient {

    private var handle: String? = null

    private suspend fun ensureHandle(): String {
        if (handle == null) {
            val result = withContext(Dispatchers.IO) {
                Openlistbridge.create(driverType, configJson)
            }
            val parsed = JSONObject(result)
            if (!parsed.optBoolean("success", false)) {
                throw Exception(parsed.optString("error", "create failed"))
            }
            handle = parsed.getJSONObject("data").getString("handle")
        }
        return handle!!
    }

    private suspend fun call(method: String, vararg args: String): String {
        val h = ensureHandle()
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

    private fun checkSuccess(json: String): JSONObject {
        val parsed = JSONObject(json)
        if (!parsed.optBoolean("success", false)) {
            throw Exception(parsed.optString("error", "operation failed"))
        }
        return parsed
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val r = Openlistbridge.create(driverType, configJson)
            val parsed = JSONObject(r)
            if (!parsed.optBoolean("success", false))
                throw Exception(parsed.optString("error", "create failed"))
            handle = parsed.getJSONObject("data").getString("handle")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            var current = "/"
            for (part in path.trim('/').split("/").filter { it.isNotBlank() }) {
                val child = current.trimEnd('/') + "/" + part
                val listR = Openlistbridge.list(h, child)
                if (!JSONObject(listR).optBoolean("success", false)) {
                    val mkR = Openlistbridge.mkdir(h, current, part)
                    checkSuccess(mkR)
                }
                current = child
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upload(local: File, remotePath: String): Result<RemoteObject> {
        return withContext(Dispatchers.IO) {
            try {
                val h = ensureHandle()
                val parentPath = remotePath.substringBeforeLast("/", "").ifBlank { "/" }
                val fileName = remotePath.substringAfterLast("/", remotePath)
                val r = Openlistbridge.upload(h, parentPath, fileName, local.absolutePath, "application/octet-stream")
                checkSuccess(r)
                Result.success(RemoteObject(remotePath, local.length(), System.currentTimeMillis()))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun download(remotePath: String, local: File): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val h = ensureHandle()
                val r = Openlistbridge.getDownloadURL(h, remotePath)
                val parsed = checkSuccess(r)
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
                val parsed = checkSuccess(r)
                val arr = parsed.optJSONArray("data") ?: JSONArray()
                val items = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RemoteObject(
                        path = obj.optString("path", ""),
                        size = obj.optLong("size", 0),
                        modTime = obj.optLong("modified_at", 0),
                    )
                }
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                checkSuccess(call("delete", remotePath))
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
