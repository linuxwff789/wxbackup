package com.nous.wxhook.service

import org.json.JSONArray
import org.json.JSONObject
import com.nous.wxhook.root.RootGateways
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standardized backup package format (.wxhook)
 *
 * A .wxhook file is a tar.gz containing:
 *   manifest.json  — metadata (version, type, files, compression, timestamps)
 *   db/            — DB backup files (baseline or incremental .sql.gz/.sql.zst)
 *   state/         — backup state files
 *
 * SyncService sends this single file to remote storage.
 */
object BackupPackage {

    private const val PKG_VERSION = 1

    data class PackageInfo(
        val tag: String,
        val type: String,          // "full" or "incremental"
        val userHash: String = "",
        val files: List<String> = emptyList(),
        val compression: String = "gzip",
        val totalSize: Long = 0L,
        val fileCount: Long = 0L,
        val manifestJson: String = ""
    )

    /**
     * Create a .wxhook package at [outputPath] containing the given [sourceFiles].
     * Returns the package info on success or null.
     */
    fun create(
        sourceFiles: List<String>,
        outputPath: String,
        tag: String,
        type: String,
        userHash: String = "",
        compression: String = "gzip"
    ): PackageInfo? {
        if (sourceFiles.isEmpty()) return null
        val binDir = "/data/local/tmp/wxhook_bin"
        val tmpDir = "/data/local/tmp/wxhook_pkg_${System.nanoTime()}"
        val pkgDir = "$tmpDir/package"
        try {
            // Create package directory structure
            RootGateways.gateway.run(
                "mkdir -p $pkgDir/db $pkgDir/state && chmod 755 $tmpDir $pkgDir $pkgDir/db $pkgDir/state",
                10_000
            )
            // Copy source files into package dir
            var hasDb = false
            for (f in sourceFiles) {
                val name = File(f).name
                val targetDir = when {
                    name.endsWith(".sql.gz") || name.endsWith(".sql.zst") -> "db"
                    name.startsWith("backup_") || name.startsWith("db_") -> "state"
                    else -> "state"
                }
                RootGateways.gateway.run(
                    "cp \"$f\" $pkgDir/$targetDir/ && chmod 644 $pkgDir/$targetDir/$name",
                    30_000
                )
                if (targetDir == "db") hasDb = true
            }
            // Compute total size
            val sizeRaw = RootGateways.gateway.runQuiet(
                "du -sb $pkgDir 2>/dev/null | cut -f1"
            ).trim()
            val totalSize = sizeRaw.toLongOrNull() ?: 0L
            val fileCount = sourceFiles.size.toLong()
            // Create manifest
            val manifest = JSONObject().apply {
                put("pkgVersion", PKG_VERSION)
                put("app", "wxhook")
                put("tag", tag)
                put("type", type)
                put("userHash", userHash)
                put("compression", compression)
                put("fileCount", fileCount)
                put("totalSize", totalSize)
                put("createdAt", System.currentTimeMillis())
                put("files", JSONArray(sourceFiles.map { File(it).name }))
            }
            val manifestStr = manifest.toString(2)
            RootGateways.gateway.run(
                "printf '%s' '${android.util.Base64.encodeToString(manifestStr.toByteArray(), android.util.Base64.NO_WRAP)}' | base64 -d > $pkgDir/manifest.json && chmod 644 $pkgDir/manifest.json",
                10_000
            )
            // Package into tar.gz
            RootGateways.gateway.run(
                "cd $tmpDir && tar czf \"$outputPath\" package/ 2>/dev/null",
                60_000
            )
            // Verify
            val exists = RootGateways.gateway.runQuiet(
                "stat -c %s \"$outputPath\" 2>/dev/null"
            ).trim().toLongOrNull() ?: 0L
            // Cleanup tmp
            RootGateways.gateway.run("rm -rf $tmpDir", 10_000)
            if (exists < 100L) return null
            return PackageInfo(
                tag = tag,
                type = type,
                userHash = userHash,
                files = sourceFiles,
                compression = compression,
                totalSize = exists,
                fileCount = fileCount,
                manifestJson = manifestStr
            )
        } catch (_: Exception) {
            RootGateways.gateway.run("rm -rf $tmpDir", 10_000)
            return null
        }
    }

    /**
     * Extract a .wxhook package, returning the extracted directory path or null.
     */
    fun extract(packagePath: String, extractDir: String): Boolean {
        return try {
            RootGateways.gateway.run(
                "mkdir -p \"$extractDir\" && tar xzf \"$packagePath\" -C \"$extractDir\" 2>/dev/null",
                60_000
            ).ok
        } catch (_: Exception) { false }
    }
}
