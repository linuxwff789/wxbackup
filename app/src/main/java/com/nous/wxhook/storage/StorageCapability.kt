package com.nous.wxhook.storage

import android.content.Context
import android.os.Build
import android.os.Environment

data class StorageCapability(
    val manifestDeclared: Boolean,
    val runtimeGranted: Boolean,
    val manageStorageGranted: Boolean,
    val rootAvailable: Boolean,
) {
    val canWriteSdcard: Boolean get() = manageStorageGranted || rootAvailable
    val canReadWeChatData: Boolean get() = rootAvailable

    fun summary(): String = buildString {
        append("sdcard写入=")
        append(if (canWriteSdcard) "✅" else "❌")
        append(" 微信数据读取=")
        append(if (canReadWeChatData) "✅" else "❌")
        if (!manageStorageGranted && rootAvailable) append(" (仅root)")
    }
}

object StorageCapabilityChecker {
    fun check(context: Context, rootAvailable: Boolean): StorageCapability {
        val manifest = context.packageManager.getPackageInfo(context.packageName, 0)
        val manifestDeclared = true // if we're here, manifest exists

        val runtimeGranted = if (Build.VERSION.SDK_INT >= 23) {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        val manageStorage = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else runtimeGranted

        return StorageCapability(
            manifestDeclared = manifestDeclared,
            runtimeGranted = runtimeGranted,
            manageStorageGranted = manageStorage,
            rootAvailable = rootAvailable,
        )
    }
}
