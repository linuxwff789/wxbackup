package com.nous.wxnotes.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nous.wxshared.ForwardProtocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 大媒体分片接收：每片落到私有目录，最后一片到达后由 Worker 合并。
 * 这样任意大小的图片/视频都能保存，不受 Binder 1MB 限制。
 */
class ChunkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ForwardProtocol.ACTION_RECEIVE_CHUNK) return
        // 大文件 Base64 解码 + 写盘不能占用广播主线程，否则会触发「Can't deliver broadcast」被杀
        val pending = goAsync()
        var transferId: String? = null
        try {
            transferId = handle(context, intent)
        } catch (_: Throwable) {
        } finally {
            pending.finish()
        }
        // WorkManager 初始化较慢，放到广播 finish 之后，避免拖住广播被系统杀进程
        if (transferId != null) {
            val id = transferId
            Thread({ enqueue(context, id) }, "wxnotes-chunk-wm").start()
        }
    }

    private fun enqueue(context: Context, transferId: String) {
        try {
            val request = OneTimeWorkRequestBuilder<AssembleChunkWorker>()
                .setInputData(workDataOf(AssembleChunkWorker.KEY_TRANSFER_ID to transferId))
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "assemble-$transferId",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (_: Throwable) {
        }
    }

    private fun handle(context: Context, intent: Intent): String? {
        val json = intent.getStringExtra(ForwardProtocol.EXTRA_CHUNK_JSON) ?: return null
        val chunk = ForwardProtocol.MediaChunk.decode(json) ?: return null
        if (chunk.transferId.isBlank() || chunk.total <= 0) return null

        val dir = File(File(context.filesDir, "imports/chunks"), chunk.transferId)
        if (!dir.exists() && !dir.mkdirs()) return null
        try {
            val bytes = android.util.Base64.decode(chunk.data, android.util.Base64.DEFAULT)
            File(dir, "${chunk.index}.part").writeBytes(bytes)
            // 元数据每片都写一次（内容相同），用临时文件原子替换。
            // 注意不能写原始 json：里面含本片的 base64 data，曾把 meta.json 撑到 127KB。
            val meta = File(dir, "meta.json")
            val tmp = File(dir, "meta.json.tmp")
            tmp.writeText(chunk.copy(data = "").encode())
            if (!tmp.renameTo(meta)) {
                meta.writeText(json)
                tmp.delete()
            }
        } catch (_: Throwable) {
            return null
        }
        return chunk.transferId
    }
}
