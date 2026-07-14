package com.nous.wxhook.backup

import java.io.File

/**
 * /sdcard 路径操作统一走 su，避免 FUSE 问题
 */
object SdcardOps {
    
    fun mkdirs(path: String): Boolean {
        return BackupEnv.su("mkdir -p \\\"$path\\\"").isSuccess
    }

    fun write(path: String, content: String): Boolean {
        // 先写临时文件，再用 su 复制
        val tmp = File(BackupEnv.filesDirForWrite(), "tmp_${System.currentTimeMillis()}.txt")
        tmp.writeText(content)
        val result = BackupEnv.suCopy(tmp, File(path))
        tmp.delete()
        return result
    }

    fun exists(path: String): Boolean {
        return BackupEnv.suOut("test -e \\\"$path\\\" && echo 1").trim() == "1"
    }

    fun delete(path: String): Boolean {
        return BackupEnv.su("rm -f \\\"$path\\\"").isSuccess
    }

    fun copy(src: String, dst: String): Boolean {
        return BackupEnv.su("cp \\\"$src\\\" \\\"$dst\\\"").isSuccess
    }

    fun chmod(path: String, mode: String): Boolean {
        return BackupEnv.su("chmod $mode \\\"$path\\\"").isSuccess
    }

    fun size(path: String): Long {
        return BackupEnv.suOut("stat -c %s \\\"$path\\\" 2>/dev/null").trim().toLongOrNull() ?: 0L
    }
}
