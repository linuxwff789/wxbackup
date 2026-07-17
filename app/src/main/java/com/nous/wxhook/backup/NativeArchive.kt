package com.nous.wxhook.backup

/** libarchive/libzstd JNI entrypoints. Called only inside WxRootService. */
object NativeArchive {
    init {
        System.loadLibrary("wxarchive")
    }

    @JvmStatic
    external fun writeTar(outputPath: String, pairsFilePath: String, useZstd: Boolean): Int
    @JvmStatic
    external fun verifyTar(archivePath: String): Int
    @JvmStatic
    /** Read a single file from a tar[.zst|.gz] archive. Returns content or empty string. */
    external fun readFileFromTar(archivePath: String, filePath: String): String
    @JvmStatic
    /** List files in a tar[.zst|.gz] archive. Returns newline-separated filenames. */
    external fun listTar(archivePath: String): String
    @JvmStatic
    /** Get max rowid from SQL file in a tar archive. Returns 0 if not found. */
    external fun getTarSqlMaxRowId(archivePath: String, filePath: String): Long
}
