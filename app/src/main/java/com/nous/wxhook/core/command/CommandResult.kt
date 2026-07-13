package com.nous.wxhook.core.command

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = !timedOut && exitCode == 0
    @Deprecated("Use isSuccess", ReplaceWith("isSuccess"))
    val ok: Boolean get() = isSuccess

    fun output(): String = if (stdout.isNotBlank()) stdout else stderr

    fun summary(): String = buildString {
        append("exit=$exitCode")
        if (timedOut) append(" TIMEOUT")
        if (stderr.isNotBlank()) append(" stderr=${stderr.take(200)}")
    }
}
