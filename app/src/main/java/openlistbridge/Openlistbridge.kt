package openlistbridge

/**
 * Generated-style binding for openlistbridge Go library (gomobile).
 * JNI names match the gomobile-generated exports directly.
 */
object Openlistbridge {
    init {
        System.loadLibrary("openlist")
    }

    /** Initialize Seq and the Go bridge — call once before any other method. */
    fun init() {
        _init()
    }

    // ── JNI exports (match gomobile-generated Java_openlistbridge_Openlistbridge_*) ──
    private external fun _init()
    external fun create(driverType: String, configJSON: String): String
    external fun list(handle: String, path: String): String
    external fun getDownloadURL(handle: String, path: String): String
    external fun upload(handle: String, parentPath: String, fileName: String, localFilePath: String, mimeType: String): String
    external fun mkdir(handle: String, parentPath: String, dirName: String): String
    external fun delete(handle: String, path: String): String
    external fun rename(handle: String, path: String, newName: String): String
    external fun move(handle: String, srcPath: String, dstDirPath: String): String
    external fun copy(handle: String, srcPath: String, dstDirPath: String): String
    external fun destroy(handle: String): String
}
