package com.nous.wxhook.backup
import com.nous.wxhook.core.command.CommandResult

import com.nous.wxhook.root.RootGateways

/**
 * Discovers WeChat data directories and user hashes from /proc/<pid>/root.
 */
object WeChatSourceResolver {

    /**
     * Locates all WeChat user MicroMsg data directories that contain EnMicroMsg.db.
     * Returns a list of absolute paths like "/proc/<pid>/root/data/data/com.tencent.mm/MicroMsg/<hash>".
     */
    fun findWxPaths(): List<String> {
        val paths = mutableListOf<String>()
        try {
            val pid = TargetAppController.findWeChatPid()
            if (pid == null) {
                android.util.Log.e("wxhook:discover", "WeChat PID not found")
                return emptyList()
            }
            val basePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg"
            val dirsResult = RootGateways.run("ls $basePath 2>&1", 10_000)
            android.util.Log.i("wxhook:discover", "base=$basePath ls=${dirsResult.summary()} output=${dirsResult.output().take(1000)}")
            val dirs = dirsResult.stdout.lines().filter { it.isNotBlank() }
            for (d in dirs) {
                val dbPath = "$basePath/$d/EnMicroMsg.db"
                val dbResult = RootGateways.run("ls $dbPath 2>&1", 10_000)
                android.util.Log.i("wxhook:discover", "db=$dbPath result=${dbResult.summary()} output=${dbResult.output().take(300)}")
                if (dbResult.isSuccess) paths.add("$basePath/$d")
            }
            android.util.Log.i("wxhook:discover", "resolved paths=$paths")
        } catch (e: Throwable) {
            android.util.Log.e("wxhook:discover", "findWxPaths failed", e)
        }
        return paths
    }

    /**
     * Extracts the user hash (last path segment) from a WeChat data path.
     */
    fun extractUserHash(wxBasePath: String): String =
        wxBasePath.substringAfterLast("/")

    /**
     * Finds all user backup directories under the backup root.
     * Returns list of File pointing to each user dir.
     */
    fun findUserBackupDirs(): List<java.io.File> {
        val backupDir = java.io.File(BackupEnv.backupDir)
        return backupDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("tmp") }
            ?: emptyList()
    }
}
