package com.nous.wxhook.sync

import java.io.File

data class CloudConfig(
    val provider: String = "",
    val url: String = "",
    val vendor: String = "",
    val user: String = "",
    val pass: String = "",
    val remote: String = "",
) {
    val isValid: Boolean get() = url.isNotBlank() && user.isNotBlank()
}

data class RemoteObject(
    val path: String,
    val size: Long = 0,
    val modTime: Long = 0,
)

data class SyncResult(
    val success: Boolean,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val totalBytes: Long = 0,
    val message: String = "",
    val errors: List<String> = emptyList(),
)

interface CloudClient {
    suspend fun testConnection(): Result<Unit>
    suspend fun ensureDirectory(path: String): Result<Unit>
    suspend fun upload(local: File, remote: String): Result<RemoteObject>
    suspend fun download(remote: String, local: File): Result<Unit>
    suspend fun list(remote: String): Result<List<RemoteObject>>
    suspend fun delete(remote: String): Result<Unit>
}
