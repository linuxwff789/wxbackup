package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDavClient(
    private val url: String,
    private val user: String,
    private val pass: String,
) : CloudClient {

    private val client: OkHttpClient by lazy {
        // Trust all certificates (self-signed / unknown CA)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun authHeader(): String {
        val credentials = "$user:$pass"
        return "Basic ${java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())}"
    }

    private fun request(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Int {
        val fullUrl = "${url.trimEnd('/')}/$path"
        val reqBuilder = Request.Builder()
            .url(fullUrl)
            .header("Authorization", authHeader())

        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }

        if (body != null) {
            val requestBody = body.toRequestBody("application/xml".toMediaType())
            reqBuilder.method(method, requestBody)
        } else {
            // GET/HEAD without body
            when (method) {
                "GET" -> reqBuilder.get()
                "HEAD" -> reqBuilder.head()
                "DELETE" -> reqBuilder.delete()
                else -> reqBuilder.method(method, null)
            }
        }

        val response = client.newCall(reqBuilder.build()).execute()
        return response.code
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val code = request("GET", "")
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("WebDAV GET failed: $code"))
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

    override suspend fun list(path: String): Result<List<CloudFile>> = withContext(Dispatchers.IO) {
        try {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:">
                    <D:allprop/>
                </D:propfind>
            """.trimIndent()

            // OkHttp supports PROPFIND via method()
            val fullUrl = "${url.trimEnd('/')}/$path"
            val requestBody = body.toRequestBody("application/xml".toMediaType())
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .header("Depth", "1")
                .method("PROPFIND", requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.code !in 200..299) {
                return@withContext Result.failure(Exception("PROPFIND failed: ${response.code}"))
            }

            // Simple XML parsing for href elements
            val files = mutableListOf<CloudFile>()
            val hrefRegex = Regex("<[dD]:href>([^<]+)</[dD]:href>")
            val matches = hrefRegex.findAll(responseBody)
            for (match in matches) {
                val href = match.groupValues[1]
                // Skip the directory itself (last segment)
                val name = href.trimEnd('/').substringAfterLast('/')
                if (name.isNotEmpty() && name != path.trimEnd('/').substringAfterLast('/')) {
                    files.add(CloudFile(href, name, 0))
                }
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upload(localFile: java.io.File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = localFile.readBytes().toRequestBody("application/octet-stream".toMediaType())
            val code = request("PUT", remotePath, null)
            // Re-do with body
            val fullUrl = "${url.trimEnd('/')}/$remotePath"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .put(body)
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299) Result.success(Unit)
            else Result.failure(Exception("PUT failed: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val code = request("DELETE", remotePath)
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("DELETE failed: $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
