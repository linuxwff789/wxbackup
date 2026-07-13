package com.nous.wxhook.rootbridge

import com.nous.wxhook.core.command.CommandResult

object RootCommandRunner {

    fun runSu(cmd: String, timeoutMs: Long = 60_000, verifyUid: Boolean = false): CommandResult {
        return try {
            if (verifyUid) {
                val uidCheck = exec(arrayOf("su", "-c", "id -u"), 5_000)
                if (!uidCheck.isSuccess || uidCheck.stdout.trim() != "0") {
                    return CommandResult(-1, uidCheck.stdout, "Root UID check failed: ${uidCheck.stderr}", false)
                }
            }
            exec(arrayOf("su", "-c", cmd), timeoutMs)
        } catch (e: Exception) {
            CommandResult(-1, "", e.toString(), false)
        }
    }

    fun runSuQuiet(cmd: String, timeoutMs: Long = 60_000): String {
        val r = runSu(cmd, timeoutMs)
        return r.output()
    }

    fun run(cmd: Array<String>, timeoutMs: Long = 60_000): CommandResult {
        return try {
            exec(cmd, timeoutMs)
        } catch (e: Exception) {
            CommandResult(-1, "", e.toString(), false)
        }
    }

    private fun exec(cmd: Array<String>, timeoutMs: Long): CommandResult {
        val proc = Runtime.getRuntime().exec(cmd)
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.appendLine(it) } }
            } catch (_: Exception) {}
        }
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader().useLines { lines -> lines.forEach { err.appendLine(it) } }
            } catch (_: Exception) {}
        }
        outThread.start()
        errThread.start()
        val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            return CommandResult(-1, out.toString().trim(), (err.toString() + "\nTIMEOUT").trim(), timedOut = true)
        }
        outThread.join(1000)
        errThread.join(1000)
        return CommandResult(proc.exitValue(), out.toString().trim(), err.toString().trim(), timedOut = false)
    }
}
