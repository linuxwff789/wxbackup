package com.nous.wxhook.database

import com.nous.wxhook.core.command.ShellEscaper
import com.nous.wxhook.rootbridge.RootCommandRunner

object SqlCipherExecutor {
    private const val TAG = "wxhook:SqlCipher"

    private val LD_PRELOAD_DEFAULT =
        "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6"

    private var binDir = "/data/local"
    private var sqlcipherPath = "/data/local/sqlcipher"

    fun init(binDirectory: String) {
        binDir = binDirectory
        sqlcipherPath = "$binDirectory/sqlcipher"
    }

    fun buildCommand(): String = "$LD_PRELOAD_DEFAULT $sqlcipherPath"

    fun query(dbPath: String, sql: String, timeoutMs: Long = 60_000): String {
        val tmpFile = "/data/local/tmp/wxhook_sql_${System.currentTimeMillis()}.sql"
        val writeResult = RootCommandRunner.runSu(
            "printf '%s' ${ShellEscaper.quote(sql)} > $tmpFile", 5_000
        )
        if (!writeResult.isSuccess) return ""

        return try {
            val cmd = "${buildCommand()} ${ShellEscaper.quote(dbPath)} < $tmpFile 2>/dev/null | tail -1"
            RootCommandRunner.runSuQuiet(cmd, timeoutMs).trim()
        } finally {
            RootCommandRunner.runSu("rm -f $tmpFile", 3_000)
        }
    }

    fun executeScript(dbPath: String, script: String, timeoutMs: Long = 120_000): Boolean {
        val tmpFile = "/data/local/tmp/wxhook_sql_${System.currentTimeMillis()}.sql"
        val writeResult = RootCommandRunner.runSu(
            "printf '%s' ${ShellEscaper.quote(script)} > $tmpFile", 5_000
        )
        if (!writeResult.isSuccess) return false

        return try {
            val cmd = "${buildCommand()} ${ShellEscaper.quote(dbPath)} < $tmpFile 2>/dev/null"
            RootCommandRunner.runSu(cmd, timeoutMs).isSuccess
        } finally {
            RootCommandRunner.runSu("rm -f $tmpFile", 3_000)
        }
    }

    fun fileQuery(dbPath: String, sqlFile: String, timeoutMs: Long = 60_000): String {
        val cmd = "${buildCommand()} ${ShellEscaper.quote(dbPath)} < ${ShellEscaper.quote(sqlFile)} 2>/dev/null | tail -1"
        return RootCommandRunner.runSuQuiet(cmd, timeoutMs).trim()
    }

    fun version(): String {
        return RootCommandRunner.runSuQuiet("${buildCommand()} -version 2>/dev/null | head -1").trim()
    }
}
