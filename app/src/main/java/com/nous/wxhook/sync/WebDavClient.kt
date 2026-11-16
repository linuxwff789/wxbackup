package com.nous.wxhook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
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
        val r = remote.trim()
        return when {
            r.startsWith("http://") || r.startsWith("https://") -> r
            r.startsWith("/") -> {
                val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: base
                "$origin$r"
            }
            r.isEmpty() -> "$base/"
            else -> "$base/${r.trimStart('/')}"
        }
    }

    /** PROPFIND 返回的 href 是 percent-encoded 的（中文/空格/括号），解码后才能和本地文件名比对。 */
    private fun decodeHref(href: String): String = try {
        java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        href
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
            // allprop 在部分服务器（坚果云等）会 400，propname 兼容性最好
            val body = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:propname/></D:propfind>"""
            val fullUrl = resolveUrl("")
            val requestBody = body.toRequestBody("application/xml".toMediaType())
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", authHeader())
                .header("Depth", "0")
                .method("PROPFIND", requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                // 207 Multi-Status = PROPFIND success, 200 = OK
                if (response.code in 200..299 || response.code == 207) Result.success(Unit)
                else Result.failure(Exception("WebDAV PROPFIND failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun ensureDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 远端目录可能是多级（wxhook-backup/phone），MKCOL 不支持自动创建父目录，逐级建
            var acc = ""
            var lastError: String? = null
            for (seg in path.split('/').filter { it.isNotBlank() }) {
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                val request = Request.Builder()
                    .url(resolveUrl(acc))
                    .header("Authorization", authHeader())
                    .method("MKCOL", null)
                    .build()
                client.newCall(request).execute().use { response ->
                    // 201 Created / 200-299 ok；405 Already Exists 也算成功
                    if (response.code !in 200..299 && response.code != 405) {
                        // 409 Conflict 一般是父目录权限问题；继续尝试下一级没有意义
                        lastError = "MKCOL $acc failed: ${response.code}"
                    }
                }
                if (lastError != null) break
            }
            if (lastError != null) Result.failure(Exception(lastError))
            else Result.success(Unit)
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

            val fullUrl = resolveUrl(remote)
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
                val rawHref = hrefMatch.groupValues[1]
                // 目录的 href 以 / 结尾，跳过（Syncer 只关心归档文件）
                if (rawHref.endsWith("/")) continue
                val href = decodeHref(rawHref)
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

    override suspend fun upload(local: File, remote: String, onProgress: ((uploaded: Long, total: Long) -> Unit)?): Result<RemoteObject> = withContext(Dispatchers.IO) {
        try {
            val body = ProgressRequestBody(local, onProgress)
            val fullUrl = resolveUrl(remote)
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
                    body.byteStream().use { input ->
                        DownloadStreamWriter.copy(input, local, total, onProgress)
                    }
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
            val fullUrl = resolveUrl(remote)
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

    /** HEAD 取文件大小/修改时间。 */
    override suspend fun get(remotePath: String): Result<RemoteObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(resolveUrl(remotePath))
                .header("Authorization", authHeader())
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    Result.failure(Exception("HEAD failed: ${response.code}"))
                } else {
                    Result.success(
                        RemoteObject(
                            remotePath,
                            response.header("Content-Length")?.toLongOrNull() ?: 0L,
                            parseHttpDate(response.header("Last-Modified").orEmpty())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun davMoveCopy(method: String, srcPath: String, dstPath: String, overwrite: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(resolveUrl(srcPath))
                    .header("Authorization", authHeader())
                    .header("Destination", resolveUrl(dstPath))
                    .header("Overwrite", if (overwrite) "T" else "F")
                    .method(method, null)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code in 200..299) Result.success(Unit)
                    else Result.failure(Exception("$method failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun rename(remotePath: String, newName: String): Result<Unit> {
        val parent = remotePath.trimEnd('/').substringBeforeLast('/', "")
        val dst = if (parent.isEmpty()) newName else "$parent/$newName"
        return davMoveCopy("MOVE", remotePath, dst, overwrite = true)
    }

    override suspend fun move(srcPath: String, dstDirPath: String): Result<Unit> {
        val name = srcPath.trimEnd('/').substringAfterLast('/')
        return davMoveCopy("MOVE", srcPath, "$dstDirPath/$name", overwrite = true)
    }

    override suspend fun copy(srcPath: String, dstDirPath: String): Result<Unit> {
        val name = srcPath.trimEnd('/').substringAfterLast('/')
        return davMoveCopy("COPY", srcPath, "$dstDirPath/$name", overwrite = true)
    }

    /** quota-available-bytes / quota-used-bytes（部分服务器不返回，失败时调用方自行忽略）。 */
    override suspend fun getStorageDetails(): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        try {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:">
                    <D:prop>
                        <D:quota-available-bytes/>
                        <D:quota-used-bytes/>
                    </D:prop>
                </D:propfind>
            """.trimIndent()
            val request = Request.Builder()
                .url(resolveUrl(""))
                .header("Authorization", authHeader())
                .header("Depth", "0")
                .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val xml = response.body?.string().orEmpty()
                if (response.code !in 200..299) return@use Result.failure(Exception("PROPFIND quota failed: ${response.code}"))
                val avail = Regex("<[a-zA-Z0-9-]+:?quota-available-bytes>([0-9]+)<")
                    .find(xml)?.groupValues?.get(1)?.toLongOrNull()
                val used = Regex("<[a-zA-Z0-9-]+:?quota-used-bytes>([0-9]+)<")
                    .find(xml)?.groupValues?.get(1)?.toLongOrNull()
                if (avail == null || used == null) {
                    Result.failure(Exception("服务器未返回配额信息"))
                } else {
                    Result.success(avail to used)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * 流式 PUT body：边写边回报已发送字节（WebDAV 是唯一能拿到真实字节进度的驱动）。
 * 限流 200ms 一次，避免 759MB 的包刷爆 UI/通知。
 */
private class ProgressRequestBody(
    private val file: File,
    private val onProgress: ((Long, Long) -> Unit)?,
) : RequestBody() {

    override fun contentType(): MediaType = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var written = 0L
        var lastReport = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                sink.write(buf, 0, read)
                written += read
                val cb = onProgress ?: continue
                val now = System.currentTimeMillis()
                if (now - lastReport >= 200 || written >= total) {
                    lastReport = now
                    cb(written, total)
                }
            }
        }
        onProgress?.invoke(written, total)
    }
}
