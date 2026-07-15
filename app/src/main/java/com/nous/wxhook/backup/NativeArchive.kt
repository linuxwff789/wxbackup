package com.nous.wxhook.backup

/** libarchive/libzstd JNI entrypoints. Called only inside WxRootService. */
object NativeArchive {
    init {
        System.loadLibrary("wxarchive")
    }

    @JvmStatic
    external fun writeTarZstd(outputPath: String, pairsFilePath: String): Int
    @JvmStatic
    external fun verifyTarZstd(archivePath: String): Int
}
