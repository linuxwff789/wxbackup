package com.nous.wxhook.root

import com.nous.wxhook.core.command.CommandResult

data class RootStatus(
    val available: Boolean,
    val uid: Int = -1,
    val message: String = "",
)

data class FileMetadata(
    val path: String,
    val exists: Boolean,
    val isDirectory: Boolean = false,
    val size: Long = 0,
    val uid: Int = 0,
    val gid: Int = 0,
    val mode: String = "",
)

interface RootGateway {
    suspend fun check(): RootStatus
    suspend fun exists(path: String): Boolean
    suspend fun stat(path: String): FileMetadata?
    suspend fun mkdirs(path: String): Boolean
    suspend fun copy(src: String, dst: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun writeTarZstd(outputPath: String, pairsPath: String, useZstd: Boolean): Int
    suspend fun verifyTarZstd(archivePath: String): Int
    suspend fun exec(command: String, timeoutMs: Long): CommandResult
    /** Read a file from a tar[.zst|.gz] archive via JNI (runs in root process). */
    suspend fun readFileFromTar(archivePath: String, filePath: String): String
    /** List files in a tar[.zst|.gz] archive via JNI. */
    suspend fun listTar(archivePath: String): String
    suspend fun run(command: String, timeoutMs: Long = 60_000): CommandResult
    suspend fun runQuiet(command: String, timeoutMs: Long = 60_000): String
    
    // 文件操作 - 在 root 进程执行
    suspend fun writeFile(path: String, content: String): Boolean
    suspend fun readFile(path: String): String
    suspend fun fileSize(path: String): Long

    // WebDAV upload in root process (no external binary dependency)
    suspend fun webdavUpload(url: String, user: String, pass: String, filePath: String): Boolean
}
