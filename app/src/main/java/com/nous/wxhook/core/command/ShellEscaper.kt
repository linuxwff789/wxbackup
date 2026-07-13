package com.nous.wxhook.core.command

object ShellEscaper {
    private val UNSAFE = Regex("[^a-zA-Z0-9_./=-]")

    fun quote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (!UNSAFE.containsMatchIn(arg)) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    fun escapePath(path: String): String = quote(path)

    fun buildCommand(vararg parts: String): String = parts.joinToString(" ") { quote(it) }
}
