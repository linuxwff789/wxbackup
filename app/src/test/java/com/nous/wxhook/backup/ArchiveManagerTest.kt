package com.nous.wxhook.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveManagerTest {
    @Test
    fun extractionCacheAndTempDirsAreNotArchiveCandidates() {
        assertFalse(ArchiveManager.isArchiveDirCandidate("extracted_wxbackup_full_20260723_073501"))
        assertFalse(ArchiveManager.isArchiveDirCandidate("extracted_incr_attachments_20260727_020000"))
        assertFalse(ArchiveManager.isArchiveDirCandidate("tmp"))
    }

    @Test
    fun realHashDirsAreArchiveCandidates() {
        assertTrue(ArchiveManager.isArchiveDirCandidate("6d1f34a5edc49e8b6d238141b2d004f3"))
        assertTrue(ArchiveManager.isArchiveDirCandidate("anything-not-extracted"))
    }
}
