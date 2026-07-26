package com.nous.wxhook.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class NativeArchivePlanTest {
    @Test
    fun writePairsFile_createsTabSeparatedFile() {
        val f = File.createTempFile("pairs", ".txt")
        val plan = NativeArchivePlan("/out.tar.zst", listOf(
            NativeArchivePlan.Source("/src/a.txt", "dir/a.txt"),
            NativeArchivePlan.Source("/src/b", "dir/b"),
        ))
        assertEquals(true, plan.writePairsFile(f.absolutePath))
        val text = f.readText()
        assertEquals(true, text.contains("/src/a.txt\tdir/a.txt"))
        assertEquals(true, text.contains("/src/b\tdir/b"))
        f.delete()
    }

    @Test
    fun toPairsContent_keepsOnlyFirstSourceForEachArchivePath() {
        val plan = NativeArchivePlan("/out.tar.zst", listOf(
            NativeArchivePlan.Source("/src/original.sql", "user/incr.sql"),
            NativeArchivePlan.Source("/src/duplicate.sql", "user/incr.sql"),
            NativeArchivePlan.Source("/src/manifest.json", "user/file_manifest.json"),
        ))

        val content = plan.toPairsContent()

        assertEquals(2, content.lines().size)
        assertEquals(true, content.contains("/src/original.sql\tuser/incr.sql"))
        assertFalse(content.contains("/src/duplicate.sql\tuser/incr.sql"))
    }
}
