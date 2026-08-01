package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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

    /**
     * 将远端路径解析为完整 URL。兼容三种 href 格式：
     * 1. 完整 URL（http(s)://...）→ 直接使用
     * 2. 绝对路径（/remote.php/dav/...）→ 基于服务器 origin 拼接，避免路径重复
     * 3. 相对路径（wxhook-backup/xxx.tar.zst）→ 基于 base url 拼接
     */
    private fun resolveUrl(remote: String): String {
        val base = url.trimEnd('/')
        return when {
            remote.startsWith("http://") || remote.startsWith("https://") -> remote
            remote.startsWith("/") -> {
                val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: base
                "$origin$remote"
            }
            else -> "$base/$remote"
        }
    }

    /** 解析 WebDAV 返回的 HTTP 日期（如 "Wed, 23 Jul 2025 07:35:01 GMT"）为毫秒时间戳 */
    private fun parseHttpDate(date: String): Long {
        val candidates = listOf(
            "EEE, dd MMM yyyy HH:mm:ss z",   // RFC 1123
            "EEEE, dd-MMM-yy HH:mm:ss z",     // RFC 850
            "EEE MMM d HH:mm:ss yyyy",        // ANSI C
        )
        for (pattern in candidates) {
            try {
                return java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    .parse(date.trim())?.time ?: 0L
            } catch (_: Exception) {}
        }
        return 0L
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
            val fullUrl = "${url.trimEnd('/')}/"
            val requestBody = body.toRequestBody("application/xml".toMediaType())
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .header("Depth", "0")
                .method("PROPFIND", requestBody)
                .build()
            val response = client.newCall(request).execute()
            // 207 Multi-Status = PROPFIND success, 200 = OK
            if (response.code in 200..299 || response.code == 207) Result.success(Unit)
            else Result.failure(Exception("WebDAV PROPFIND failed: ${response.code}"))
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
            // PROPFIND 响应按 <response> 块解析，兼容带/不带命名空间前缀的标签
            val responseBlocks = Regex("<[a-zA-Z0-9-]+:?response>([\\s\\S]*?)</[a-zA-Z0-9-]+:?response>")
                .findAll(responseBody)
            for (block in responseBlocks) {
                val xml = block.groupValues[1]
                val hrefMatch = Regex("<[a-zA-Z0-9-]+:?href>([^<]+)</[a-zA-Z0-9-]+:?href>").find(xml)
                    ?: continue
                val href = hrefMatch.groupValues[1]
                val name = href.trimEnd('/').substringAfterLast('/')
                if (name.isNotEmpty()) {
                    val size = Regex("<[a-zA-Z0-9-]+:?getcontentlength>([0-9]+)</[a-zA-Z0-9-]+:?getcontentlength>")
                        .find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val modTime = Regex("<[a-zA-Z0-9-]+:?getlastmodified>([^<]+)</[a-zA-Z0-9-]+:?getlastmodified>")
                        .find(xml)?.groupValues?.get(1)?.let(::parseHttpDate) ?: 0L
                    files.add(RemoteObject(href, size, modTime))
                }
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upload(local: File, remote: String): Result<RemoteObject> = withContext(Dispatchers.IO) {
        try {
            val body = local.asRequestBody("application/octet-stream".toMediaType())
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

    override suspend fun download(remote: String, local: File, onProgress: ((downloaded: Long, total: Long) -> Unit)?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = resolveUrl(remote)
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.code in 200..299) {
                response.body?.let { body ->
                    val total = body.contentLength()
                    val out = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            done += read
                            // 每 512KB 或结束时上报一次进度，避免刷屏
                            if (done - lastReport >= 512 * 1024 || done >= total) {
                                lastReport = done
                                onProgress?.invoke(done, total)
                            }
                        }
                    }
                    local.writeBytes(out.toByteArray())
                }
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

    override suspend fun get(remotePath: String): Result<RemoteObject> {
        return Result.failure(Exception("WebDAV get not implemented"))
    }

    override suspend fun rename(remotePath: String, newName: String): Result<Unit> {
        return Result.failure(Exception("WebDAV rename not implemented"))
    }

    override suspend fun move(srcPath: String, dstDirPath: String): Result<Unit> {
        return Result.failure(Exception("WebDAV move not implemented"))
    }

    override suspend fun copy(srcPath: String, dstDirPath: String): Result<Unit> {
        return Result.failure(Exception("WebDAV copy not implemented"))
    }

    override suspend fun getStorageDetails(): Result<Pair<Long, Long>> {
        return Result.failure(Exception("WebDAV storage details not implemented"))
    }
}
