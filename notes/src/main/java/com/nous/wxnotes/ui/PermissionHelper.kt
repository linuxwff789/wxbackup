package com.nous.wxnotes.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {

    fun hasStorage(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 打开「所有文件访问权限」系统页面（引导用户手动开启）。 */
    fun requestAllFiles(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }
}
