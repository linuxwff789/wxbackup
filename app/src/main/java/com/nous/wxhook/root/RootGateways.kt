package com.nous.wxhook.root

import com.nous.wxhook.core.command.CommandResult
import kotlinx.coroutines.runBlocking

object RootGateways {
    var gateway: RootGateway = RootGatewayImpl()
    fun set(g: RootGateway) { gateway = g }

    // 非 suspend 包装，供非协程上下文调用
    fun run(command: String, timeoutMs: Long = 60_000): CommandResult =
        runBlocking { gateway.run(command, timeoutMs) }

    fun runQuiet(command: String, timeoutMs: Long = 60_000): String =
        runBlocking { gateway.runQuiet(command, timeoutMs) }

    fun writeFile(path: String, content: String): Boolean =
        runBlocking { gateway.writeFile(path, content) }

    fun readFile(path: String): String =
        runBlocking { gateway.readFile(path) }

    fun mkdirs(path: String): Boolean =
        runBlocking { gateway.mkdirs(path) }

    fun exists(path: String): Boolean =
        runBlocking { gateway.exists(path) }

    fun fileSize(path: String): Long =
        runBlocking { gateway.fileSize(path) }

    fun copy(src: String, dst: String): Boolean =
        runBlocking { gateway.copy(src, dst) }

    fun delete(path: String): Boolean =
        runBlocking { gateway.delete(path) }

    fun writeTarZstd(outputPath: String, pairsPath: String): Int =
        runBlocking { gateway.writeTarZstd(outputPath, pairsPath) }

    fun verifyTarZstd(archivePath: String): Int =
        runBlocking { gateway.verifyTarZstd(archivePath) }
}
