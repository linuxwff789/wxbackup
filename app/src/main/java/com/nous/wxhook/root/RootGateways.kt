package com.nous.wxhook.root

object RootGateways {
    var gateway: RootGateway = RootGatewayImpl()
    fun set(g: RootGateway) { gateway = g }
}
