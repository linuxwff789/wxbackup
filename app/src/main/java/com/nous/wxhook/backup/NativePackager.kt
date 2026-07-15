package com.nous.wxhook.backup

object NativePackager {
    init { System.loadLibrary("native_packager") }

    external fun execCommand(args: Array<String>, envVars: Array<String>? = null): Int

    fun packageFiles(outputPath: String, backupDir: String, backupFiles: List<String>,
                     microMsgDir: String, attDirs: List<String>): Int {
        val tarBin = "/data/data/com.termux/files/usr/bin/tar"
        val zstdBin = "/data/local/tmp/wxhook_bin/zstd"
        val libDir = "/data/data/com.termux/files/usr/lib"

        val args = mutableListOf(tarBin,
            "--use-compress-program", zstdBin,
            "-cf", outputPath, "-C", backupDir)
        args.addAll(backupFiles)
        args.add("-C"); args.add(microMsgDir)
        args.addAll(attDirs)

        return execCommand(args.toTypedArray(), arrayOf(
            "LD_LIBRARY_PATH=$libDir", "ZSTD_CLEVEL=3"))
    }
}
