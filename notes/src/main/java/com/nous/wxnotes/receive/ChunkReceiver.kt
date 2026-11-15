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
        val json = intent.getStringExtra(ForwardProtocol.EXTRA_CHUNK_JSON) ?: return
        val chunk = ForwardProtocol.MediaChunk.decode(json) ?: return
        if (chunk.transferId.isBlank() || chunk.total <= 0) return

        val dir = File(File(context.filesDir, "imports/chunks"), chunk.transferId)
        if (!dir.exists() && !dir.mkdirs()) return
        try {
            val bytes = android.util.Base64.decode(chunk.data, android.util.Base64.DEFAULT)
            File(dir, "${chunk.index}.part").writeBytes(bytes)
            // 元数据每片都写一次（内容相同），用临时文件原子替换
            val meta = File(dir, "meta.json")
            val tmp = File(dir, "meta.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(meta)) {
                meta.writeText(json)
                tmp.delete()
            }
        } catch (_: Throwable) {
            return
        }

        val request = OneTimeWorkRequestBuilder<AssembleChunkWorker>()
            .setInputData(workDataOf(AssembleChunkWorker.KEY_TRANSFER_ID to chunk.transferId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "assemble-${chunk.transferId}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
