package com.nous.wxhook.backup
import com.nous.wxhook.core.command.CommandResult

import com.nous.wxhook.root.RootGateways

/**
 * Discovers WeChat data directories and user hashes from /proc/<pid>/root.
 */
object WeChatSourceResolver {

    /**
     * Android 14+ root 可直读的稳定基目录（不依赖微信 pid）。
     * /proc/<pid>/root 会因微信重启/pid 缓存过期整体失效——症状是所有附件目录
     * "目录存在=false"、扫描全空（备份日志里的"⚠️ 附件扫描为空"就是它）。
     */
    private val STABLE_BASES = listOf(
        "/data_mirror/data_ce/null/0/com.tencent.mm/MicroMsg",
        "/data_mirror/data_ce/null/0/0/com.tencent.mm/MicroMsg",
        "/data/data/com.tencent.mm/MicroMsg",
    )

    /**
     * Locates all WeChat user MicroMsg data directories that contain EnMicroMsg.db.
     * 优先返回稳定路径（/data_mirror/data_ce/null/0/...），失败才回退
     * "/proc/<pid>/root/data/data/com.tencent.mm/MicroMsg/<hash>"。
     */
    fun findWxPaths(): List<String> {
        // 1) 稳定路径优先（与项目其它模块一致：diff/restore/phone stats 均用 data_mirror）
        for (base in STABLE_BASES) {
            val paths = collectUserDirs(base)
            if (paths.isNotEmpty()) {
                android.util.Log.i("wxhook:discover", "resolved via stable base=$base paths=$paths")
                return paths
            }
        }

        // 2) 回退 /proc/<pid>/root
        val pid = TargetAppController.findWeChatPid()
        if (pid == null) {
            android.util.Log.e("wxhook:discover", "WeChat PID not found")
            return emptyList()
        }
        val basePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg"
        val paths = collectUserDirs(basePath)
        android.util.Log.i("wxhook:discover", "resolved via proc base=$basePath paths=$paths")
        return paths
    }

    /** 列出 base 下所有含 EnMicroMsg.db 的用户目录（/data_mirror 与 /proc 都适用）。 */
    private fun collectUserDirs(basePath: String): List<String> {
        return try {
            val dirsResult = RootGateways.run("ls $basePath 2>&1", 10_000)
            android.util.Log.i("wxhook:discover", "base=$basePath ls=${dirsResult.summary()} output=${dirsResult.output().take(1000)}")
            val dirs = dirsResult.stdout.lines().filter { it.isNotBlank() }
            val out = mutableListOf<String>()
            for (d in dirs) {
                if (d.endsWith(".db") || d.endsWith(".db.ini")) continue
                val dbPath = "$basePath/$d/EnMicroMsg.db"
                val dbResult = RootGateways.run("ls $dbPath 2>&1", 10_000)
                android.util.Log.i("wxhook:discover", "db=$dbPath result=${dbResult.summary()} output=${dbResult.output().take(300)}")
                if (dbResult.isSuccess) out.add("$basePath/$d")
            }
            out
        } catch (e: Throwable) {
            android.util.Log.e("wxhook:discover", "collectUserDirs failed base=$basePath", e)
            emptyList()
        }
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
