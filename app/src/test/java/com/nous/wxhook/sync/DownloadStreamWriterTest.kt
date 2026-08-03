package com.nous.wxhook.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DownloadStreamWriterTest {
    @Test
    fun copy_writesToTargetWithoutKeepingWholePayloadInMemory() {
        val dir = createTempDir(prefix = "wx-download-test")
        val target = File(dir, "archive.tar.zst")
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()

        DownloadStreamWriter.copy(
            input = ByteArrayInputStream(payload),
            target = target,
            total = payload.size.toLong(),
            onProgress = { done, _ -> progress += done },
        )

        assertArrayEquals(payload, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
        assertEquals(payload.size.toLong(), progress.last())
        dir.deleteRecursively()
    }
}
