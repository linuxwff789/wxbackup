package com.nous.wxhook.backup

import com.nous.wxhook.rootbridge.RootCommandRunner

/**
 * Manages WeChat process lifecycle: detect PID, force-stop, kill.
 */
object TargetAppController {

    /**
     * Returns the PID of com.tencent.mm, or null if not running.
     */
    fun findWeChatPid(): String? {
        val result = RootCommandRunner.runSu("pidof com.tencent.mm", 10_000)
        val pid = result.stdout.trim()
        return if (pid.isNotEmpty()) pid else null
    }

    /**
     * Force-stops WeChat.
     */
    fun forceStopWeChat() {
        RootCommandRunner.runSu("am force-stop com.tencent.mm", 10_000)
    }

    /**
     * Kills all sqlcipher processes (used before opening DB to avoid lock conflicts).
     */
    fun killSqlcipher() {
        RootCommandRunner.runSu("killall sqlcipher 2>/dev/null", 10_000)
    }

    /**
     * Check if a file path exists under a PID's namespace.
     */
    fun checkPathExists(path: String): Boolean {
        val result = RootCommandRunner.runSuQuiet("ls $path 2>/dev/null")
        return result.trim().isNotEmpty()
    }
}
