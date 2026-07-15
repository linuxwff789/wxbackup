package com.nous.wxhook.backup

object NativePackager {
    init { System.loadLibrary("native_packager") }

    external fun execCommand(args: Array<String>, envVars: Array<String>? = null): Int

    fun packageFiles(outputPath: String, backupDir: String, backupFiles: List<String>,
                     microMsgDir: String, attDirs: List<String>): Int {
        val args = mutableListOf("/data/data/com.termux/files/usr/bin/tar",
            "--use-compress-program", "/data/local/tmp/wxhook_bin/zstd",
            "-cf", outputPath, "-C", backupDir)
        args.addAll(backupFiles)
        args.add("-C"); args.add(microMsgDir)
        args.addAll(attDirs)
        return execCommand(args.toTypedArray(), arrayOf(
            "LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib", "ZSTD_CLEVEL=3"))
    }
}
