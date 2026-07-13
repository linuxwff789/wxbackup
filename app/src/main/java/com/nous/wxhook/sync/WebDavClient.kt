package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class WebDavClient(
    private val url: String,
    private val user: String,
    private val pass: String,
) : CloudClient {

    private fun authHeader(): String {
        val credentials = "$user:$pass"
        return "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
    }

    private fun request(method: String, path: String, body: ByteArray? = null): Int {
        val fullUrl = "${url.trimEnd('/')}/$path"
        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", authHeader())
        conn.setRequestProperty("Content-Type", "application/xml")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        return conn.responseCode
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val code = request("PROPFIND", "")
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("WebDAV PROPFIND failed: $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val code = request("MKCOL", path)
            if (code in 200..299 || code == 405) Result.success(Unit) // 405 = already exists
            else Result.failure(Exception("MKCOL failed: $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upload(local: File, remote: String): Result<RemoteObject> =
        withContext(Dispatchers.IO) {
            try {
                val data = local.readBytes()
                val code = request("PUT", remote, data)
                if (code in 200..299) {
                    Result.success(RemoteObject(remote, local.length()))
                } else {
                    Result.failure(Exception("PUT failed: $code"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun download(remote: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val fullUrl = "${url.trimEnd('/')}/$remote"
                val conn = URL(fullUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", authHeader())
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    }
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("GET failed: ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun list(remote: String): Result<List<RemoteObject>> =
        withContext(Dispatchers.IO) {
            try {
                val body = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:">
  <d:prop><d:displayname/><d:getcontentlength/></d:prop>
</d:propfind>""".toByteArray()
                // PROPFIND with depth 1
                val fullUrl = "${url.trimEnd('/')}/$remote"
                val conn = URL(fullUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "PROPFIND"
                conn.setRequestProperty("Authorization", authHeader())
                conn.setRequestProperty("Content-Type", "application/xml")
                conn.setRequestProperty("Depth", "1")
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000

                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("PROPFIND failed: ${conn.responseCode}"))
                }
                val xml = conn.inputStream.bufferedReader().readText()
                // 简单解析 response（避免加 XML parser 依赖）
                val objects = mutableListOf<RemoteObject>()
                val entries = xml.split("<d:response>").drop(1)
                for (entry in entries) {
                    val href = entry.substringAfter("<d:href>", "").substringBefore("</d:href>")
                    if (href.isNotBlank() && href != "$remote/") {
                        val size = entry.substringAfter("<d:getcontentlength>", "0")
                            .substringBefore("</d:getcontentlength>").toLongOrNull() ?: 0
                        objects.add(RemoteObject(href.trimStart('/'), size))
                    }
                }
                Result.success(objects)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun delete(remote: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val code = request("DELETE", remote)
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("DELETE failed: $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
