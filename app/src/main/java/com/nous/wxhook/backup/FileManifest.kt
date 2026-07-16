package com.nous.wxhook.backup

import com.nous.wxhook.root.RootGateways
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FileEntry(
    val path: String,
    val size: Long,
    val mtime: Long,
    val md5: String = "",
)

data class FileDiff(
    val added: List<FileEntry>,
    val modified: List<FileEntry>,
    val deleted: List<String>,
    val unchanged: Int,
)

object FileManifest {
    private const val MANIFEST_FILE = "file_manifest.json"

    fun load(backupDir: File): JSONObject {
        val f = File(backupDir, MANIFEST_FILE)
        return try {
            val txt = RootGateways.runQuiet("cat \"${f.absolutePath}\" 2>/dev/null").trim()
            if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
        } catch (_: Exception) { JSONObject() }
    }

    fun save(backupDir: File, manifest: JSONObject) {
        val f = File(backupDir, MANIFEST_FILE)
        // 先写到临时文件，再用 su 复制（避免 FUSE 问题）
        val tmp = File(BackupEnv.filesDirForWrite(), MANIFEST_FILE)
        tmp.writeText(manifest.toString(2))
        BackupEnv.suCopy(tmp, f)
    }

    fun scanWeChatAttachments(
        wxBasePath: String,
        userHash: String,
        attachmentDirs: List<String>,
    ): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        for (dir in attachmentDirs) {
            val sourceDir = "$wxBasePath/$dir"
            val output = RootGateways.runQuiet(
                "find \"$sourceDir\" -type f -exec stat -c '%s %Y %n' {} + 2>/dev/null"
            )
            output.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split(' ', limit = 3)
                if (parts.size != 3) return@forEach
                val relativePath = parts[2].removePrefix("$wxBasePath/")
                entries.add(FileEntry(
                    path = "$userHash/$relativePath",
                    size = parts[0].toLongOrNull() ?: return@forEach,
                    mtime = parts[1].toLongOrNull() ?: return@forEach,
                ))
            }
        }
        return entries
    }

    fun findEntry(manifest: JSONObject, path: String): FileEntry? {
        val arr = manifest.optJSONArray("files") ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("path") == path) {
                return FileEntry(
                    path = obj.getString("path"),
                    size = obj.getLong("size"),
                    mtime = obj.getLong("mtime"),
                )
            }
        }
        return null
    }

    fun diff(oldManifest: JSONObject, newFiles: List<FileEntry>): FileDiff {
        val oldEntries = mutableMapOf<String, FileEntry>()
        val oldArr = oldManifest.optJSONArray("files") ?: JSONArray()
        for (i in 0 until oldArr.length()) {
            val obj = oldArr.getJSONObject(i)
            oldEntries[obj.getString("path")] = FileEntry(
                path = obj.getString("path"),
                size = obj.getLong("size"),
                mtime = obj.getLong("mtime"),
            )
        }

        val added = mutableListOf<FileEntry>()
        val modified = mutableListOf<FileEntry>()
        val seen = mutableSetOf<String>()

        for (entry in newFiles) {
            seen.add(entry.path)
            val old = oldEntries[entry.path]
            if (old == null) {
                added.add(entry)
            } else if (old.size != entry.size || old.mtime != entry.mtime) {
                modified.add(entry)
            }
        }

        val deleted = oldEntries.keys.filter { it !in seen }

        return FileDiff(
            added = added,
            modified = modified,
            deleted = deleted,
            unchanged = newFiles.size - added.size - modified.size,
        )
    }

    fun toManifest(files: List<FileEntry>, tag: String = ""): JSONObject {
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().apply {
                put("path", f.path)
                put("size", f.size)
                put("mtime", f.mtime)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("tag", tag)
            put("fileCount", files.size)
            put("files", arr)
        }
    }
}
