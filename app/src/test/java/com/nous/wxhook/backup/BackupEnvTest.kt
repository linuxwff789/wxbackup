package com.nous.wxhook.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupEnvTest {
    @Test
    fun tarExtractCommand_usesBundledZstdAbsolutePath() {
        BackupEnv.binDir = "/data/local/tmp/wxhook_bin"
        val cmd = BackupEnv.tarExtractCommand("/sdcard/x.tar.zst", "/sdcard/out")
        assertEquals("tar -I '/data/local/tmp/wxhook_bin/zstd' -xf \"/sdcard/x.tar.zst\" -C \"/sdcard/out\"", cmd)
    }

    @Test
    fun tarExtractCommand_usesPlainGzipForTarGz() {
        val cmd = BackupEnv.tarExtractCommand("/sdcard/x.tar.gz", "/sdcard/out")
        assertEquals("tar -xzf \"/sdcard/x.tar.gz\" -C \"/sdcard/out\"", cmd)
    }
}
