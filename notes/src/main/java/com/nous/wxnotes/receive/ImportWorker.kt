package com.nous.wxnotes.receive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nous.wxnotes.NotesApp
import com.nous.wxshared.ForwardProtocol
import java.io.File

class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val file = File(path)
        val json = runCatching { file.readText() }.getOrNull() ?: return Result.failure()
        val batch = ForwardProtocol.ForwardBatch.fromJson(json) ?: return Result.failure()

        return try {
            val result = (applicationContext as NotesApp).repository.saveBatch(batch)
            if (result.added > 0) {
                Notifications.importFinished(applicationContext, result.noteId, result.noteTitle, result.added, result.skipped)
            }
            runCatching { file.delete() }
            Result.success()
        } catch (e: Throwable) {
            android.util.Log.e("wxnotes", "ImportWorker failed: ${batch.messages.size} msgs", e)
            if (runAttemptCount < 3) Result.retry() else {
                runCatching { file.delete() }
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_PATH = "batch_json_path"
    }
}
