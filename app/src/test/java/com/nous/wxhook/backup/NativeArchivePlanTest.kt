package com.nous.wxhook.backup

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
        assert(plan.writePairsFile(f.absolutePath))
        val text = f.readText()
        assert(text.contains("/src/a.txt\tdir/a.txt"))
        assert(text.contains("/src/b\tdir/b"))
        f.delete()
    }
}
