package com.nous.wxhook

import android.app.Application
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化 RootGateway (带 context，支持 libsu)
        val gateway = RootGatewayImpl(this)
        RootGateways.set(gateway)
        // 异步初始化 libsu 连接
        kotlinx.coroutines.GlobalScope.launch {
            gateway.initialize()
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
