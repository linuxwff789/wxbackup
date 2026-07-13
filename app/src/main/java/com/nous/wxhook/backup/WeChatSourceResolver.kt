package com.nous.wxhook.backup

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
            val pid = TargetAppController.findWeChatPid() ?: return emptyList()
            val basePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg"
            val dirsOutput = RootGateways.runQuiet("ls $basePath 2>/dev/null")
            val dirs = dirsOutput.lines().filter { it.isNotBlank() }
            for (d in dirs) {
                val dbCheck = RootGateways.runQuiet("ls $basePath/$d/EnMicroMsg.db 2>/dev/null")
                if (dbCheck.trim().isNotEmpty()) {
                    paths.add("$basePath/$d")
                }
            }
        } catch (_: Exception) {}
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
