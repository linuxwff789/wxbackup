package com.nous.wxhook

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.nous.wxhook.root.RootGateways
import com.nous.wxhook.root.RootGatewayImpl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Apply Dynamic Colors (Material You) on Android 12+
        // Falls back automatically to theme colors on older versions
        DynamicColors.applyToActivitiesIfAvailable(this)

        // 初始化 RootGateway (带 context，支持 libsu)
        val gateway = RootGatewayImpl(this)
        RootGateways.set(gateway)

        // 异步初始化 libsu 连接
        GlobalScope.launch {
            gateway.initialize()
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
