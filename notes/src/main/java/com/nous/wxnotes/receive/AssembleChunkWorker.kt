package com.nous.wxnotes.receive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nous.wxnotes.data.NotesDatabase
import com.nous.wxshared.ForwardProtocol
import java.io.File

class AssembleChunkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val transferId = inputData.getString(KEY_TRANSFER_ID) ?: return Result.failure()
        val dir = File(File(applicationContext.filesDir, "imports/chunks"), transferId)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) {
            return if (runAttemptCount < 20) Result.retry() else Result.failure()
        }
        val meta = ForwardProtocol.MediaChunk.decode(metaFile.readText()) ?: return Result.failure()
        if (meta.total <= 0) return Result.failure()

        val parts = (0 until meta.total).map { File(dir, "$it.part") }
        if (parts.any { !it.exists() }) {
            // 还没收齐，退避重试
            return if (runAttemptCount < 20) Result.retry() else {
                dir.deleteRecursively()
                Result.failure()
            }
        }

        val db = NotesDatabase.get(applicationContext)
        val msg = db.messageDao().getByKey(meta.dedupeKey)
        if (msg == null) {
            // 消息批次可能还没入库，等它
            return if (runAttemptCount < 10) Result.retry() else {
                dir.deleteRecursively()
                Result.failure()
            }
        }

        val mediaDir = File(File(applicationContext.filesDir, "media"), msg.noteId.toString())
        if (!mediaDir.exists() && !mediaDir.mkdirs()) return Result.failure()

        val name = meta.fileName.ifBlank {
            "media_${System.currentTimeMillis()}" + when (meta.mediaKind) {
                ForwardProtocol.MEDIA_IMAGE -> ".jpg"
                ForwardProtocol.MEDIA_VIDEO -> ".mp4"
                ForwardProtocol.MEDIA_VOICE -> ".amr"
                else -> ".bin"
            }
        }
        val dst = uniqueFile(mediaDir, name)
        return try {
            dst.outputStream().use { out ->
                parts.forEach { part ->
                    part.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
            }
            db.messageDao().updateMedia(
                msg.id,
                dst.absolutePath,
                meta.mediaKind,
                dst.name,
                dst.length()
            )
            dir.deleteRecursively()
            Result.success()
        } catch (e: Throwable) {
            android.util.Log.e("wxnotes", "assemble chunks failed: $transferId", e)
            runCatching { dst.delete() }
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base-$i$ext")
            i++
        }
        return f
    }

    companion object {
        const val KEY_TRANSFER_ID = "transfer_id"
    }
}
