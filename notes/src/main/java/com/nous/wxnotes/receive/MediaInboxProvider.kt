package com.nous.wxnotes.receive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 微信插件把大媒体直接写进笔记 App 的私有目录，绕开广播 1MB / 分片丢包。
 *
 * authority: com.nous.wxnotes.inbox
 * 用法：contentResolver.openOutputStream(Uri.parse("content://com.nous.wxnotes.inbox/<name>"))
 * 落点：filesDir/inbox/<name>，由 ImportWorker 导入后删除。
 */
class MediaInboxProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('w')) throw FileNotFoundException("read-only provider")
        val name = uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
        if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) {
            throw FileNotFoundException("bad name: $name")
        }
        val ctx = context ?: throw FileNotFoundException("no context")
        val dir = File(ctx.filesDir, "inbox").apply { mkdirs() }
        val f = File(dir, name)
        return ParcelFileDescriptor.open(
            f,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
    }
}
