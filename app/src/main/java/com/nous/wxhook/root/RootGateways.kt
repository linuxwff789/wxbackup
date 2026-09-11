package com.nous.wxhook.root

import com.nous.wxhook.core.command.CommandResult
import kotlinx.coroutines.runBlocking

object RootGateways {
    @Volatile var gateway: RootGateway = RootGatewayImpl()

    fun set(g: RootGateway) { gateway = g }

    fun check(): RootStatus = runBlocking { gateway.check() }
    fun exists(path: String): Boolean = runBlocking { gateway.exists(path) }
    fun stat(path: String): FileMetadata? = runBlocking { gateway.stat(path) }
    fun writeFile(path: String, content: String): Boolean = runBlocking { gateway.writeFile(path, content) }
    fun readFile(path: String): String = runBlocking { gateway.readFile(path) }
    fun countFiles(dirs: List<String>): Map<String, Int> = runBlocking { gateway.countFiles(dirs) }
    /**
     * root 进程内纯 Java 扫描附件目录，清单写入 outPath，返回每目录文件数。
     * 清单必须落盘读取：走 Binder 回复时 >1MB 会 TransactionTooLargeException 静默丢光。
     */
    fun scanAttachments(basePath: String, outPath: String, dirs: List<String>): Map<String, Int> =
        runBlocking { gateway.scanAttachments(basePath, outPath, dirs) }
    fun mkdirs(path: String): Boolean = runBlocking { gateway.mkdirs(path) }
    fun fileSize(path: String): Long = runBlocking { gateway.fileSize(path) }
    fun copy(src: String, dst: String): Boolean = runBlocking { gateway.copy(src, dst) }
    fun delete(path: String): Boolean = runBlocking { gateway.delete(path) }
    fun writeTarZstd(outputPath: String, pairsPath: String, useZstd: Boolean): Int =
        runBlocking { gateway.writeTarZstd(outputPath, pairsPath, useZstd) }
    fun webdavUpload(url: String, user: String, pass: String, filePath: String): Boolean =
        runBlocking { gateway.webdavUpload(url, user, pass, filePath) }
    fun verifyTarZstd(archivePath: String): Int =
        runBlocking { gateway.verifyTarZstd(archivePath) }
    fun readFileFromTar(archivePath: String, filePath: String): String =
        runBlocking { gateway.readFileFromTar(archivePath, filePath) }
    fun listTar(archivePath: String): String =
        runBlocking { gateway.listTar(archivePath) }
    fun getTarSqlMaxRowId(archivePath: String, filePath: String): Long =
        runBlocking { gateway.getTarSqlMaxRowId(archivePath, filePath) }
    fun getFullArchiveRowId(archivePath: String, hash: String): Long =
        runBlocking { gateway.getFullArchiveRowId(archivePath, hash) }
    fun pollFullArchiveRowId(hash: String): Long =
        runBlocking { gateway.pollFullArchiveRowId(hash) }
    fun run(command: String, timeoutMs: Long = 60_000) = runBlocking { gateway.run(command, timeoutMs) }
    fun runQuiet(command: String, timeoutMs: Long = 60_000) = runBlocking { gateway.runQuiet(command, timeoutMs) }
}
