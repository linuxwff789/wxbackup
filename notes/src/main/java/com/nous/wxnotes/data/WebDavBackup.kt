package com.nous.wxnotes.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 笔记的 WebDAV 备份：把 wxnotes.db + 媒体目录打成一个 zip 传到云端，并按保留份数清理旧包。
 * 不依赖 OkHttp（用 HttpURLConnection），PNG/媒体全程流式，不会把几百 MB 读进内存。
 */
object WebDavBackup {

    // ── 配置 ────────────────────────────────────────────────────────
    const val KEY_URL = "webdav_url"
    const val KEY_USER = "webdav_user"
    const val KEY_PASS = "webdav_pass"
    const val KEY_DIR = "webdav_dir"
    const val KEY_KEEP = "webdav_keep"
    const val KEY_LAST = "webdav_last_backup"
    const val KEY_AUTO = "webdav_auto"

    const val DEFAULT_DIR = "wxnotes-backup"
    const val DEFAULT_KEEP = 7

    data class Config(
        val url: String,
        val user: String,
        val pass: String,
        val dir: String = DEFAULT_DIR,
        val keep: Int = DEFAULT_KEEP,
    ) {
        val isValid: Boolean get() = url.isNotBlank() && user.isNotBlank()
    }

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences("wxnotes", Context.MODE_PRIVATE)
        return Config(
            url = p.getString(KEY_URL, "").orEmpty(),
            user = p.getString(KEY_USER, "").orEmpty(),
            pass = p.getString(KEY_PASS, "").orEmpty(),
            dir = p.getString(KEY_DIR, DEFAULT_DIR).orEmpty().ifBlank { DEFAULT_DIR },
            keep = p.getInt(KEY_KEEP, DEFAULT_KEEP),
        )
    }

    data class RemoteFile(val name: String, val size: Long, val modified: Long)

    data class Progress(val stage: String, val done: Long = 0, val total: Long = 0)

    // ── 主流程 ──────────────────────────────────────────────────────

    /**
     * 打包 -> 上传 -> 清理旧包。返回远端文件名。
     */
    suspend fun runBackup(
        context: Context,
        config: Config = loadConfig(context),
        onProgress: (Progress) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isValid) return@withContext Result.failure(IOException("WebDAV 未配置"))
        var zip: File? = null
        try {
            onProgress(Progress("打包数据库和媒体…"))
            zip = createBackupZip(context) { done, total ->
                onProgress(Progress("打包中 $done/$total", done.toLong(), total.toLong()))
            }
            val dav = Dav(config.url, config.user, config.pass)
            onProgress(Progress("连接 WebDAV…"))
            dav.test()

            onProgress(Progress("创建远端目录 ${config.dir}…"))
            dav.ensureDir(config.dir)

            val total = zip.length()
            onProgress(Progress("上传 ${zip.name} (${formatSize(total)})…", 0, total))
            dav.upload(zip, "${config.dir}/${zip.name}") { sent ->
                onProgress(Progress("上传 ${zip.name}", sent, total))
            }

            // 只保留最近 N 份（按文件名里的时间戳倒序）
            runCatching {
                val olds = dav.list(config.dir)
                    .filter { it.name.startsWith("wxnotes-backup-") && it.name.endsWith(".zip") }
                    .sortedByDescending { it.name }
                    .drop(config.keep.coerceAtLeast(1))
                olds.forEach { dav.delete("${config.dir}/${it.name}") }
            }

            context.getSharedPreferences("wxnotes", Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            onProgress(Progress("备份完成：${zip.name}"))
            Result.success(zip.name)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            runCatching { zip?.delete() }
        }
    }

    /** 测试连接（设置页按钮用）。 */
    suspend fun testConnection(config: Config): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Dav(config.url, config.user, config.pass).test() }
            .fold({ Result.success(Unit) }, { Result.failure(it) })
    }

    suspend fun listBackups(config: Config): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        runCatching {
            Dav(config.url, config.user, config.pass).list(config.dir)
                .filter { it.name.startsWith("wxnotes-backup-") }
                .sortedByDescending { it.name }
        }.fold({ Result.success(it) }, { Result.failure(it) })
    }

    // ── 打包 ────────────────────────────────────────────────────────

    /** 备份 zip 写到 cacheDir，调用方负责删除。 */
    fun createBackupZip(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(context.cacheDir, "wxnotes-backup-$stamp.zip")
        val dbFile = snapshotDatabase(context)
        try {
            ZipOutputStream(BufferedOutputStream(out.outputStream(), 64 * 1024)).use { zos ->
                zos.putNextEntry(ZipEntry("wxnotes.db"))
                dbFile.inputStream().use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()

                val mediaRoot = mediaRoot(context)
                val files = mediaRoot.listFiles()
                    ?.flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
                    ?: emptyList()
                files.forEachIndexed { index, f ->
                    val rel = f.relativeTo(mediaRoot).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry("media/$rel"))
                    f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                    if (index % 5 == 0 || index == files.lastIndex) onProgress(index + 1, files.size)
                }
            }
        } finally {
            runCatching { dbFile.delete() }
        }
        return out
    }

    /** 一致性快照：先 checkpoint WAL，再复制 db（避免备份到半写状态）。 */
    private fun snapshotDatabase(context: Context): File {
        val db = NotesDatabase.get(context)
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        val src = context.getDatabasePath("wxnotes.db")
        val dst = File(context.cacheDir, "wxnotes-snapshot.db")
        src.copyTo(dst, overwrite = true)
        return dst
    }

    fun mediaRoot(context: Context): File {
        val ext = android.os.Environment.getExternalStorageDirectory()
        val dir = File(ext, "Download/wxnotes/media")
        return if (dir.isDirectory || dir.mkdirs()) dir else File(context.filesDir, "media")
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    // ── 极简 WebDAV 客户端（OkHttp：HttpURLConnection 不支持 PROPFIND/MKCOL） ──

    private class Dav(val baseUrl: String, val user: String, val pass: String) {

        private val auth = "Basic " + Base64.encodeToString(
            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )

        private val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        private fun resolve(remote: String): String {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty()) throw IOException("WebDAV 地址为空")
            val r = remote.trim()
            return when {
                r.startsWith("http://") || r.startsWith("https://") -> r
                r.startsWith("/") -> {
                    val origin = Regex("^(https?://[^/]+)").find(base)?.value ?: base
                    origin + r
                }
                r.isEmpty() -> "$base/"
                else -> "$base/" + r.trimStart('/')
            }
        }

        private fun decode(href: String): String = try {
            URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) { href }

        private fun builder(method: String, remote: String, depth: String? = null): okhttp3.Request.Builder {
            val b = okhttp3.Request.Builder()
                .url(resolve(remote))
                .header("Authorization", auth)
            depth?.let { b.header("Depth", it) }
            b.method(method, null)
            return b
        }

        fun test() {
            val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
            val req = builder("PROPFIND", "", "0").method("PROPFIND", body).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) throw IOException("WebDAV 连接失败：HTTP ${resp.code}")
            }
        }

        fun ensureDir(remote: String) {
            var acc = ""
            for (seg in remote.split('/').filter { it.isNotBlank() }) {
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                val req = builder("MKCOL", acc).build()
                client.newCall(req).execute().use { resp ->
                    // 405 = 已存在
                    if (resp.code !in 200..299 && resp.code != 405) {
                        throw IOException("创建目录失败 $acc：HTTP ${resp.code}")
                    }
                }
            }
        }

        fun upload(file: File, remote: String, onProgress: (Long) -> Unit) {
            val body = ProgressBody(file, onProgress)
            val req = builder("PUT", remote).method("PUT", body).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) throw IOException("上传失败：HTTP ${resp.code}")
            }
        }

        fun list(remote: String): List<RemoteFile> {
            val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
            val req = builder("PROPFIND", remote, "1").method("PROPFIND", body).build()
            val xml = client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) throw IOException("列目录失败：HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val result = ArrayList<RemoteFile>()
            Regex("<[a-zA-Z0-9-]+:?response>([\\s\\S]*?)</[a-zA-Z0-9-]+:?response>").findAll(xml).forEach { block ->
                val part = block.groupValues[1]
                val rawHref = Regex("<[a-zA-Z0-9-]+:?href>([^<]+)</[a-zA-Z0-9-]+:?href>")
                    .find(part)?.groupValues?.get(1) ?: return@forEach
                if (rawHref.endsWith("/")) return@forEach
                val href = decode(rawHref)
                val name = href.trimEnd('/').substringAfterLast('/')
                if (name.isBlank()) return@forEach
                val size = Regex("<[a-zA-Z0-9-]+:?getcontentlength>([0-9]+)<")
                    .find(part)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                result.add(RemoteFile(name, size, 0L))
            }
            return result
        }

        fun delete(remote: String) {
            val req = builder("DELETE", remote).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) throw IOException("删除失败：HTTP ${resp.code}")
            }
        }
    }

    /** 流式 PUT，60ms 节流回调，避免大包刷爆 UI。 */
    private class ProgressBody(
        private val file: File,
        private val onProgress: (Long) -> Unit,
    ) : okhttp3.RequestBody() {
        override fun contentType() = "application/zip".toMediaType()
        override fun contentLength() = file.length()
        override fun writeTo(sink: okio.BufferedSink) {
            var sent = 0L
            var last = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 60) {
                        last = now
                        onProgress(sent)
                    }
                }
            }
            onProgress(sent)
        }
    }

    private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:getcontentlength/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""
}
