package com.nous.wxhook.root

import kotlinx.coroutines.runBlocking

object RootGateways {
    private var gateway: RootGateway = RootGatewayStub()

    fun set(g: RootGateway) { gateway = g }

    fun check(): RootStatus = runBlocking { gateway.check() }
    fun exists(path: String): Boolean = runBlocking { gateway.exists(path) }
    fun stat(path: String): FileMetadata? = runBlocking { gateway.stat(path) }
    fun writeFile(path: String, content: String): Boolean = runBlocking { gateway.writeFile(path, content) }
    fun readFile(path: String): String = runBlocking { gateway.readFile(path) }
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
    fun run(command: String, timeoutMs: Long = 60_000) = runBlocking { gateway.run(command, timeoutMs) }
    fun runQuiet(command: String, timeoutMs: Long = 60_000) = runBlocking { gateway.runQuiet(command, timeoutMs) }
}
