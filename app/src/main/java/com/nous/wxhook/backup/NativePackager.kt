package com.nous.wxhook.backup

object NativePackager {
    init { System.loadLibrary("native_packager") }

    external fun execCommand(args: Array<String>, envVars: Array<String>? = null): Int

    fun packageFiles(outputPath: String, backupDir: String, backupFiles: List<String>,
                     microMsgDir: String, attDirs: List<String>): Int {
        val binDir = "/data/local/tmp/wxhook_bin"
        val args = mutableListOf("$binDir/tar",
            "--use-compress-program", "$binDir/zstd",
            "-cf", outputPath, "-C", backupDir)
        args.addAll(backupFiles)
        args.add("-C"); args.add(microMsgDir)
        args.addAll(attDirs)
        return execCommand(args.toTypedArray(), arrayOf(
            "LD_LIBRARY_PATH=$binDir", "ZSTD_CLEVEL=3"))
    }
}
