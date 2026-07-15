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
}
