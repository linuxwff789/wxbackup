package com.nous.wxhook.root

import com.nous.wxhook.core.command.CommandResult
import kotlinx.coroutines.runBlocking

object RootGateways {
    var gateway: RootGateway = RootGatewayImpl()
    fun set(g: RootGateway) { gateway = g }

    // 非 suspend 包装，供非协程上下文调用
    fun run(command: String, timeoutMs: Long = 60_000): CommandResult =
        runBlocking { gateway.run(command, timeoutMs) }

    fun runQuiet(command: String, timeoutMs: Long = 60_000): String =
        runBlocking { gateway.runQuiet(command, timeoutMs) }
}
