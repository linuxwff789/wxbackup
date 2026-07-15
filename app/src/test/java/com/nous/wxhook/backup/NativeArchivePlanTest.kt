package com.nous.wxhook.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeArchivePlanTest {
    @Test
    fun sourcePairs_keepArchiveNamesRelativeAndStable() {
        val plan = NativeArchivePlan(
            outputPath = "/data/local/tmp/test.tar.zst",
            sources = listOf(
                NativeArchivePlan.Source("/sdcard/Download/wxhook_backup/file_manifest.json", "file_manifest.json"),
                NativeArchivePlan.Source("/proc/123/root/data/data/com.tencent.mm/MicroMsg/h/image2", "h/image2"),
            )
        )

        assertEquals(
            arrayOf(
                "/sdcard/Download/wxhook_backup/file_manifest.json", "file_manifest.json",
                "/proc/123/root/data/data/com.tencent.mm/MicroMsg/h/image2", "h/image2",
            ).toList(),
            plan.nativePairs().toList()
        )
    }

    @Test
    fun sourcePairs_rejectAbsoluteOrTraversingArchiveNames() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeArchivePlan("/data/local/tmp/test.tar.zst", listOf(
                NativeArchivePlan.Source("/source", "../escape")
            )).nativePairs()
        }
    }
}
