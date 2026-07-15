package com.nous.wxhook.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class FullBackupLayoutTest {
    @Test
    fun databaseDump_isRawSqlUntilOuterTarZstdCompression() {
        assertEquals("EnMicroMsg_baseline.sql", FullBackupLayout.databaseDumpName())
    }
}
