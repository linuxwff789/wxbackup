package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = "${url.trimEnd('/')}/"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299) Result.success(Unit)
            else Result.failure(Exception("WebDAV GET failed: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = "${url.trimEnd('/')}/$path"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .method("MKCOL", null)
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299 || response.code == 405) Result.success(Unit)
            else Result.failure(Exception("MKCOL failed: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun list(remote: String): Result<List<RemoteObject>> = withContext(Dispatchers.IO) {
        try {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:">
                    <D:allprop/>
                </D:propfind>
            """.trimIndent()

            val fullUrl = "${url.trimEnd('/')}/$remote"
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

            val files = mutableListOf<RemoteObject>()
            val hrefRegex = Regex("<[dD]:href>([^<]+)</[dD]:href>")
            val matches = hrefRegex.findAll(responseBody)
            for (match in matches) {
                val href = match.groupValues[1]
                val name = href.trimEnd('/').substringAfterLast('/')
                if (name.isNotEmpty()) {
                    files.add(RemoteObject(href, 0, 0))
                }
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upload(local: File, remote: String): Result<RemoteObject> = withContext(Dispatchers.IO) {
        try {
            val body = local.readBytes().toRequestBody("application/octet-stream".toMediaType())
            val fullUrl = "${url.trimEnd('/')}/$remote"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .put(body)
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299) Result.success(RemoteObject(remote, local.length(), 0))
            else Result.failure(Exception("PUT failed: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun download(remote: String, local: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = "${url.trimEnd('/')}/$remote"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299) {
                response.body?.bytes()?.let { local.writeBytes(it) }
                Result.success(Unit)
            } else {
                Result.failure(Exception("GET failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(remote: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = "${url.trimEnd('/')}/$remote"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .delete()
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299) Result.success(Unit)
            else Result.failure(Exception("DELETE failed: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
