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
        // 批次可能较大，写盘不能占主线程；且 WorkManager 初始化放在广播 finish 之后
        val pending = goAsync()
        var path: String? = null
        try {
            path = handle(context, intent)
        } catch (_: Throwable) {
        } finally {
            pending.finish()
        }
        if (path != null) {
            val p = path
            Thread({ enqueue(context, p) }, "wxnotes-batch-wm").start()
        }
    }

    private fun enqueue(context: Context, path: String) {
        try {
            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(workDataOf(ImportWorker.KEY_PATH to path))
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        } catch (_: Throwable) {
        }
    }

    private fun handle(context: Context, intent: Intent): String? {
        val json = intent.getStringExtra(ForwardProtocol.EXTRA_BATCH_JSON)
            ?: intent.getStringExtra(ForwardProtocol.EXTRA_JSON_PATH)?.let { p ->
                runCatching { File(p).readText() }.getOrNull()
            }
            ?: return null
        val batch = ForwardProtocol.ForwardBatch.fromJson(json) ?: return null

        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val file = File(dir, "${batch.batchId}.json")
        return runCatching {
            file.writeText(json)
            file.absolutePath
        }.getOrNull()
    }
}
