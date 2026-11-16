package com.nous.wxnotes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.nous.wxnotes.data.NotesRepository

class NotesApp : Application() {

    lateinit var repository: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = NotesRepository(this)
        createChannels()
        cleanupInbox()
    }

    /** 插件写进 files/inbox 的临时文件，导入后删除；这里兜底清理超过 1 小时的残留。 */
    private fun cleanupInbox() {
        Thread({
            runCatching {
                val dir = java.io.File(filesDir, "inbox")
                val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) f.delete()
                }
            }
        }, "wxnotes-inbox-clean").apply { isDaemon = true }.start()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_IMPORT,
                    "消息保存",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "收到微信转发消息并保存为笔记" }
            )
        }
    }

    companion object {
        const val CHANNEL_IMPORT = "wxnotes_import"
        lateinit var instance: NotesApp
            private set
    }
}
