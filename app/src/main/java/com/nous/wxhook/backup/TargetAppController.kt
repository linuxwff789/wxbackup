package com.nous.wxhook.backup
import com.nous.wxhook.core.command.CommandResult

import com.nous.wxhook.root.RootGateways

/**
 * Manages WeChat process lifecycle: detect PID, force-stop, kill.
 */
object TargetAppController {

    /**
     * Returns the PID of com.tencent.mm, or null if not running.
     */
    fun findWeChatPid(): String? {
        val result = runCatching { RootGateways.run("pidof com.tencent.mm", 10_000) }
            .onFailure { android.util.Log.e("wxhook:discover", "pidof failed", it) }
            .getOrNull() ?: return null
        val pid = result.stdout.trim().split(Regex("\\s+")).firstOrNull { it.all(Char::isDigit) }
        android.util.Log.i("wxhook:discover", "pidof com.tencent.mm: stdout=${result.stdout.trim()} stderr=${result.stderr.trim()} exit=${result.exitCode} selected=$pid")
        return pid
    }

    /**
     * Force-stops WeChat.
     */
    fun forceStopWeChat() {
        RootGateways.run("am force-stop com.tencent.mm", 10_000)
    }

    /**
     * Kills all sqlcipher processes (used before opening DB to avoid lock conflicts).
     */
    fun killSqlcipher() {
        RootGateways.run("killall sqlcipher 2>/dev/null", 10_000)
    }

    /**
     * Check if a file path exists under a PID's namespace.
     */
    fun checkPathExists(path: String): Boolean {
        val result = RootGateways.runQuiet("ls $path 2>/dev/null")
        return result.trim().isNotEmpty()
    }
}
