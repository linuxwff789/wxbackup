package com.nous.wxhook.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ArchiveDownloadPlannerTest {
    @Test
    fun missingOrDifferentSizeArchivesAreSelected() {
        val dir = createTempDir(prefix = "archive-planner")
        val existing = File(dir, "existing.tar.zst").apply { writeBytes(ByteArray(10)) }
        val jobs = ArchiveDownloadPlanner.missingOrChanged(
            listOf(
                ArchiveDownloadPlanner.Candidate("same.tar.zst", existing.absolutePath, 10),
                ArchiveDownloadPlanner.Candidate("changed.tar.zst", File(dir, "changed.tar.zst").absolutePath, 20),
                ArchiveDownloadPlanner.Candidate("missing.tar.zst", File(dir, "missing.tar.zst").absolutePath, 30),
            )
        )
        assertEquals(listOf("changed.tar.zst", "missing.tar.zst"), jobs.map { it.name })
        dir.deleteRecursively()
    }
}
