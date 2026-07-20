package com.openlist.bridge

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenList JNI bridge — wraps libopenlist.so exports.
 *
 * JNI names in libopenlist.so are hardcoded to
 * Java_com_openlist_bridge_OpenListDriver_n{Method},
 * so package/class must match exactly.
 */
class OpenListDriver private constructor(
    private val handle: String
) {
    companion object {
        init {
            System.loadLibrary("openlist")
        }

        /** Create a new driver instance (suspend for thread safety). */
        suspend fun create(driverType: String, configJson: String): OpenListDriver {
            return withContext(Dispatchers.IO) {
                val result = nCreate(driverType, configJson)
                val parsed = JSONObject(result)
                if (!parsed.optBoolean("success", false)) {
                    throw Exception(parsed.optString("error", "create failed"))
                }
                val h = parsed.getJSONObject("data").getString("handle")
                OpenListDriver(h)
            }
        }
    }

    // ── Public API ──

    data class CloudFile(
        val id: String = "",
        val name: String = "",
        val size: Long = 0,
        val isDir: Boolean = false,
        val modifiedAt: Long = 0,
        val path: String = "",
        val thumbnail: String? = null,
    )

    suspend fun list(path: String): List<CloudFile> = withContext(Dispatchers.IO) {
        val result = nList(handle, path)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "list $path")
        val arr = parsed.getJSONArray("data")
        (0 until arr.length()).map { parseCloudFile(arr.getJSONObject(it)) }
    }

    suspend fun get(path: String): CloudFile? = withContext(Dispatchers.IO) {
        val result = nGet(handle, path)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "get $path")
        if (parsed.has("data") && !parsed.isNull("data"))
            parseCloudFile(parsed.getJSONObject("data"))
        else null
    }

    suspend fun getDownloadUrl(path: String): String = withContext(Dispatchers.IO) {
        val result = nGetDownloadUrl(handle, path)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "get download url $path")
        parsed.getJSONObject("data").getString("url")
    }

    suspend fun upload(
        parentPath: String,
        fileName: String,
        localFilePath: String,
        mimeType: String = "application/octet-stream",
    ): CloudFile = withContext(Dispatchers.IO) {
        val result = nUploadFromFile(handle, parentPath, fileName, localFilePath, mimeType)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "upload $fileName")
        parseCloudFile(parsed.getJSONObject("data"))
    }

    suspend fun mkdir(parentPath: String, dirName: String): CloudFile = withContext(Dispatchers.IO) {
        val result = nMkdir(handle, parentPath, dirName)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "mkdir $dirName")
        if (parsed.getJSONObject("data").has("id"))
            parseCloudFile(parsed.getJSONObject("data"))
        else CloudFile(name = dirName, path = "$parentPath/$dirName", isDir = true)
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val result = nDelete(handle, path)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "delete $path")
    }

    suspend fun rename(path: String, newName: String): CloudFile = withContext(Dispatchers.IO) {
        val result = nRename(handle, path, newName)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "rename $path -> $newName")
        if (parsed.getJSONObject("data").has("id"))
            parseCloudFile(parsed.getJSONObject("data"))
        else CloudFile(name = newName)
    }

    suspend fun move(srcPath: String, dstDirPath: String): CloudFile = withContext(Dispatchers.IO) {
        val result = nMove(handle, srcPath, dstDirPath)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "move $srcPath -> $dstDirPath")
        parseCloudFile(parsed.getJSONObject("data"))
    }

    suspend fun copy(srcPath: String, dstDirPath: String): CloudFile = withContext(Dispatchers.IO) {
        val result = nCopy(handle, srcPath, dstDirPath)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "copy $srcPath -> $dstDirPath")
        parseCloudFile(parsed.getJSONObject("data"))
    }

    suspend fun getStorageDetails(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val result = nGetStorageDetails(handle)
        val parsed = JSONObject(result)
        checkSuccess(parsed, "get storage details")
        val data = parsed.getJSONObject("data")
        Pair(data.optLong("total_space", 0), data.optLong("used_space", 0))
    }

    fun destroy() {
        nDestroy(handle)
    }

    // ── JNI native methods ──

    private external fun nCreate(driverType: String, configJson: String): String
    private external fun nList(handle: String, path: String): String
    private external fun nGet(handle: String, path: String): String
    private external fun nGetDownloadUrl(handle: String, path: String): String
    private external fun nUploadFromFile(
        handle: String, parentPath: String, fileName: String,
        localFilePath: String, mimeType: String
    ): String
    private external fun nMkdir(handle: String, parentPath: String, dirName: String): String
    private external fun nDelete(handle: String, path: String): String
    private external fun nRename(handle: String, path: String, newName: String): String
    private external fun nMove(handle: String, srcPath: String, dstDirPath: String): String
    private external fun nCopy(handle: String, srcPath: String, dstDirPath: String): String
    private external fun nGetStorageDetails(handle: String): String
    private external fun nDestroy(handle: String)

    // ── Helpers ──

    private fun checkSuccess(parsed: JSONObject, op: String) {
        if (!parsed.optBoolean("success", false)) {
            throw Exception("$op failed: ${parsed.optString("error", "unknown")}")
        }
    }

    private fun parseCloudFile(obj: JSONObject): CloudFile = CloudFile(
        id = obj.optString("id", ""),
        name = obj.optString("name", ""),
        size = obj.optLong("size", 0),
        isDir = obj.optBoolean("is_dir", false),
        modifiedAt = obj.optLong("modified_at", 0),
        path = obj.optString("path", ""),
        thumbnail = obj.optString("thumbnail", null),
    )
}
