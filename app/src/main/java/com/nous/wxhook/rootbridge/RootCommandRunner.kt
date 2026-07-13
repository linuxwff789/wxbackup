package com.nous.wxhook.rootbridge

object RootCommandRunner {
    data class CmdResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val ok: Boolean get() = exitCode == 0
        fun output(): String = if (stdout.isNotBlank()) stdout else stderr
    }

    fun runSu(cmd: String, timeoutMs: Long = 60_000): CmdResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread {
                try { proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.appendLine(it) } } } catch (_: Exception) {}
            }
            val errThread = Thread {
                try { proc.errorStream.bufferedReader().useLines { lines -> lines.forEach { err.appendLine(it) } } } catch (_: Exception) {}
            }
            outThread.start(); errThread.start()
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                outThread.join(1000); errThread.join(1000)
                return CmdResult(-1, out.toString().trim(), (err.toString() + "\nTIMEOUT").trim())
            }
            outThread.join(1000); errThread.join(1000)
            CmdResult(proc.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            CmdResult(-1, "", e.toString())
        }
    }

    fun runSuQuiet(cmd: String, timeoutMs: Long = 60_000): String {
        val r = runSu(cmd, timeoutMs)
        return if (r.stdout.isNotBlank()) r.stdout else r.stderr
    }
}
