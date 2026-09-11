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

    /** 附件清单中转目录：root 进程写入、应用进程读取（清单不进 Binder 回复） */
    private const val SCAN_DIR = "/data/local/tmp/wxhook_scan"

    fun load(backupDir: File): JSONObject {
        val f = File(backupDir, MANIFEST_FILE)
        val local = File(BackupEnv.filesDirForWrite(), MANIFEST_FILE)
        return try {
            if (RootGateways.copy(f.absolutePath, local.absolutePath)) {
                val txt = if (local.exists()) local.readText() else ""
                local.delete()
                if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
            } else JSONObject()
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
        // 清单必须落盘、再由应用进程流式读取。整份清单若经 Binder 回复（writeString）返回，
        // 超过 ~1MB 事务上限会抛 TransactionTooLargeException，被 RootManager 吞成异常文本，
        // 解析时全部丢弃 → 该目录静默变成 "0 条"（image2 七千多个附件就是这么丢的）。
        val scanDir = File(SCAN_DIR).apply { mkdirs() }
        val outFile = File(scanDir, "attachments.txt")
        outFile.delete()
        val scanned = RootGateways.scanAttachments(wxBasePath, outFile.absolutePath, attachmentDirs)
        val parsed = mutableMapOf<String, Int>()
        try {
            outFile.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val parts = line.split(' ', limit = 3)
                    if (parts.size != 3) continue
                    val size = parts[0].toLongOrNull() ?: continue
                    val mtime = parts[1].toLongOrNull() ?: continue
                    val relativePath = parts[2].removePrefix("$wxBasePath/")
                    if (relativePath == parts[2]) continue // 不在此 base 下，忽略
                    entries.add(FileEntry(
                        path = "$userHash/$relativePath",
                        size = size,
                        mtime = mtime,
                    ))
                    val dir = relativePath.substringBefore('/')
                    parsed[dir] = (parsed[dir] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("wxhook:scan", "读取清单文件失败: ${e.message}")
        }
        outFile.delete()
        for (dir in attachmentDirs) {
            val n = parsed[dir] ?: 0
            val found = scanned[dir] ?: -1 // -1 = 降级路径（shell 兜底）未回传条数
            if (n == 0) {
                // 目录存在但清单为空 = 异常（正常应至少能列出文件），记日志便于定位
                val dirExists = RootGateways.runQuiet("test -d \"$wxBasePath/$dir\" && echo 1 || echo 0", 10_000).trim() == "1"
                android.util.Log.w("wxhook:scan", "$dir: 0 条 (root扫描=$found, 目录存在=$dirExists, base=$wxBasePath)")
            }
            android.util.Log.i("wxhook:scan", "$dir: $n 条 (root扫描=$found)")
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

    /**
     * 计算旧清单与当前文件的差异。
     * @param sizeOnly true 时忽略 mtime 差异（只按 size 判断 modified）——用于微信恢复/迁移后
     *  mtime 大规模变化但内容未变的场景，避免把同名文件全部误判为修改而重复备份。
     */
    fun diff(oldManifest: JSONObject, newFiles: List<FileEntry>, sizeOnly: Boolean = false): FileDiff {
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
            } else if (old.size != entry.size || (!sizeOnly && old.mtime != entry.mtime)) {
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
