package com.nous.wxnotes.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nous.wxshared.ForwardProtocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 接收微信插件转发的消息批次。
 *
 * 广播里携带完整 JSON（单批 ≤100 条，远低于 Binder 1MB 限制），
 * 这里先落盘到私有目录，再交给 WorkManager 处理，
 * 避免广播 10 秒超时（媒体复制可能很慢）。
 */
class BatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ForwardProtocol.ACTION_RECEIVE_BATCH) return
        val json = intent.getStringExtra(ForwardProtocol.EXTRA_BATCH_JSON)
            ?: intent.getStringExtra(ForwardProtocol.EXTRA_JSON_PATH)?.let { p ->
                runCatching { File(p).readText() }.getOrNull()
            }
            ?: return
        val batch = ForwardProtocol.ForwardBatch.fromJson(json) ?: return

        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val file = File(dir, "${batch.batchId}.json")
        runCatching { file.writeText(json) }.getOrElse { return }

        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_PATH to file.absolutePath))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
