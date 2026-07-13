package com.nous.wxhook.util

import android.content.Context
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object SetupManager {

    private val BINS = listOf("git", "rclone", "zstd", "sqlcipher",
        "libz.so.1", "libcrypto.so.3", "libedit.so", "libncursesw.so.6")
    private val EXEC = listOf("git", "rclone", "sqlcipher")
    private val executor = Executors.newSingleThreadExecutor()

    fun setup(ctx: Context) {
        val dir = File(ctx.filesDir, "bin")
        if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, ".setup_done")
        if (marker.exists()) return
        executor.submit {
            // Extract from APK assets to filesDir/bin (cache)
            for (name in BINS) {
                try {
                    val dst = File(dir, name)
                    if (dst.exists() && dst.length() > 1000) continue
                    ctx.assets.open("bin/$name").use { i ->
                        FileOutputStream(dst).use { o -> i.copyTo(o, 65536) }
                    }
                    dst.setReadable(true, false)
                    android.util.Log.i("wxhook:Setup", "extracted $name (${dst.length()})")
                } catch (e: Exception) {
                    android.util.Log.e("wxhook:Setup", "failed $name: $e")
                }
            }
            // Copy to /data/local/tmp/wxhook_bin/ (where SELinux allows execution)
            val tmpDir = "/data/local/tmp/wxhook_bin"
            try {
                RootCommandRunner.runSu("mkdir -p $tmpDir && cp " + dir.absolutePath + "/* $tmpDir/ && chmod 755 $tmpDir/*")
                marker.writeText("ok")
                android.util.Log.i("wxhook:Setup", "copied to $tmpDir")
            } catch (e: Exception) {
                android.util.Log.e("wxhook:Setup", "copy to tmp failed: $e")
            }
        }
    }

    fun git(ctx: Context) = File(ctx.filesDir, "bin/git").absolutePath
    fun rclone(ctx: Context) = File(ctx.filesDir, "bin/rclone").absolutePath
    fun sqlcipher(ctx: Context) = File(ctx.filesDir, "bin/sqlcipher").absolutePath
    fun libDir(ctx: Context) = File(ctx.filesDir, "bin").absolutePath
}
