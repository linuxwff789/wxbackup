package com.nous.wxhook.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneStatsParseTest {
    @Test
    fun parsesDirCountLines() {
        val out = "image2 2603\nvoice2 144\nvideo 158\n"
        val counts = ArchiveManager.parseDirCounts(out)
        assertEquals(3, counts.size)
        assertEquals(2603, counts["image2"])
        assertEquals(144, counts["voice2"])
    }

    @Test
    fun ignoresEmptyAndMalformedLines() {
        val out = "image2 10\n\nnot-a-count\ncdn 0\n"
        val counts = ArchiveManager.parseDirCounts(out)
        assertEquals(2, counts.size)
        assertEquals(10, counts["image2"])
        assertEquals(0, counts["cdn"])
    }

    @Test
    fun phoneStatsTotalSumsCounts() {
        val stats = ArchiveManager.PhoneStats(
            msgCount = 100,
            msgRowIdFrom = 90,
            msgRowId = 120,
            attachmentCounts = mapOf("image2" to 10, "video" to 5),
        )
        assertEquals(15, stats.totalAttachments)
    }
}
