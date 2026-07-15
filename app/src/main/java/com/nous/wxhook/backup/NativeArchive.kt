package com.nous.wxhook.backup

/** libarchive/libzstd JNI entrypoints. Called only inside WxRootService. */
object NativeArchive {
    init {
        System.loadLibrary("wxarchive")
    }

    @JvmStatic
    external fun writeTarZstd(outputPath: String, sourceArchivePairs: Array<String>): Int

    /** @return positive entry count on success; negative error code otherwise. */
    @JvmStatic
    external fun verifyTarZstd(archivePath: String): Int
}
