package com.nous.wxhook.sync

import com.openlist.bridge.OpenListDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CloudClient adapter backed by OpenListDriver.
 *
 * Supports multiple driver types through OpenList's JNI bridge:
 * - "aliyundrive": 阿里云盘 Open (refresh_token auth)
 * - "webdav":       Standard WebDAV (basic auth, alternative to WebDavClient)
 */
class OpenListCloudClient(
    private val driverType: String,
    configJson: String,
) : CloudClient {

    private val driver: OpenListDriver = runBlockingSafely {
        OpenListDriver.create(driverType, configJson)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            driver.list("/")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Try listing first (dir exists) — mkdir if fails
            val listResult = try { driver.list(path) } catch (_: Exception) { null }
            if (listResult != null) return@withContext Result.success(Unit)

            // Walk path segments and create each missing part
            val parts = path.trim('/').split("/").filter { it.isNotBlank() }
            var current = "/"
            for (part in parts) {
                try {
                    driver.list(current + part)
                } catch (_: Exception) {
                    driver.mkdir(current, part)
                }
                current = current.trimEnd('/') + "/" + part
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
                val cf = driver.upload(parentPath, fileName, local.absolutePath)
                Result.success(RemoteObject(
                    path = remotePath,
                    size = cf.size,
                    modTime = cf.modifiedAt,
                ))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun download(remotePath: String, local: File): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val url = driver.getDownloadUrl(remotePath)
                // Download via OkHttp (same pattern as WebDavClient)
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
                val files = driver.list(remotePath)
                Result.success(files.map {
                    RemoteObject(
                        path = it.path,
                        size = it.size,
                        modTime = it.modifiedAt,
                    )
                })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                driver.delete(remotePath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Clean up native resources. */
    fun destroy() {
        driver.destroy()
    }

    companion object {
        /** Build AliyunDrive config JSON from individual fields. */
        fun aliyunConfig(
            refreshToken: String,
            apiUrl: String = "https://api.oplist.org/alicloud/renewapi",
            rootFolderId: String = "root",
        ): String = org.json.JSONObject().apply {
            put("refresh_token", refreshToken)
            put("use_online_api", true)
            put("api_url_address", apiUrl)
            put("drive_type", "default")
            put("root_folder_id", rootFolderId)
            put("remove_way", "trash")
        }.toString()

        /** Build WebDAV config JSON. */
        fun webdavConfig(
            address: String,
            username: String,
            password: String,
        ): String = org.json.JSONObject().apply {
            put("address", address)
            put("username", username)
            put("password", password)
            put("vendor", "other")
        }.toString()
    }
}

/** Utility: run coroutine block synchronously from a non-coroutine context. */
internal fun <T> runBlockingSafely(block: suspend () -> T): T {
    return kotlinx.coroutines.runBlocking { block() }
}
