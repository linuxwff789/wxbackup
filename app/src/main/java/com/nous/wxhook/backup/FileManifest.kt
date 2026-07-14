package com.nous.wxhook.backup

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
        return if (f.exists()) {
            try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
    }

    fun save(backupDir: File, manifest: JSONObject) {
        val f = File(backupDir, MANIFEST_FILE)
        f.parentFile?.mkdirs()
        f.writeText(manifest.toString(2))
    }

    fun scanFiles(dir: File, prefix: String = ""): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        if (!dir.exists()) return entries
        dir.listFiles()?.forEach { f ->
            val relPath = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) {
                entries.addAll(scanFiles(f, relPath))
            } else {
                entries.add(FileEntry(
                    path = relPath,
                    size = f.length(),
                    mtime = f.lastModified(),
                ))
            }
        }
        return entries
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
