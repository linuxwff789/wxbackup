package com.nous.wxhook.sync

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Streams a download to disk and publishes coarse progress without buffering the archive. */
object DownloadStreamWriter {
    private const val BUFFER_SIZE = 256 * 1024
    private const val REPORT_BYTES = 512 * 1024L

    fun copy(
        input: InputStream,
        target: File,
        total: Long,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var done = 0L
        var lastReport = 0L
        try {
            part.outputStream().use { output ->
                copy(input, output) { count ->
                    done += count
                    if (done - lastReport >= REPORT_BYTES || (total > 0 && done >= total)) {
                        lastReport = done
                        onProgress?.invoke(done, total)
                    }
                }
                output.flush()
            }
            if (total > 0 && done != total) {
                throw java.io.IOException("download size mismatch: $done/$total")
            }
            if (target.exists() && !target.delete()) {
                throw java.io.IOException("cannot replace ${target.absolutePath}")
            }
            if (!part.renameTo(target)) {
                throw java.io.IOException("cannot finalize ${target.absolutePath}")
            }
            onProgress?.invoke(done, total)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private fun copy(input: InputStream, output: OutputStream, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            onBytes(read)
        }
    }
}
