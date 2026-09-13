package com.nous.wxhook.util

import android.content.Context
import com.nous.wxhook.root.RootGateways
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object SetupManager {

    private const val TMP_DIR = "/data/local/tmp/wxhook_bin"
    private val BINS = listOf("zstd", "sqlcipher",
        "libz.so.1", "libcrypto.so.3", "libedit.so", "libncursesw.so.6")
    private val EXEC = listOf("sqlcipher", "zstd")
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 只按退出码判断能不能跑，**不要接管道**。
     * 原来写的是 `zstd --version 2>&1 | head -1` —— 退出码取到的是 head 的 0，
     * 于是 x86-64 的坏 zstd 也永远「校验通过」（2026-09-13 真机实测：
     * /data/local/tmp/wxhook_bin/zstd 是 x86-64 ELF，tar -I 直接报 syntax error）。
     * 不接管道才会拿到 126 (cannot execute)。
     */
    private fun verifyExec(path: String): Boolean =
        try { RootGateways.run("$path --version >/dev/null 2>&1", 10_000).isSuccess }
        catch (_: Exception) { false }

    fun setup(ctx: Context) {
        val dir = File(ctx.filesDir, "bin")
        if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, ".setup_done")
        // 即使已部署过（marker 存在），也校验 zstd 可执行：
        // 历史上 assets/bin/zstd 曾被错误架构（x86-64）替换，导致恢复解压失败
        // 且 marker 缓存让坏版本永不被重新部署。root 不可用时保持原行为。
        var force = !marker.exists()
        if (!force) {
            try {
                if (verifyExec("$TMP_DIR/zstd")) return
                android.util.Log.e("wxhook:Setup", "zstd 校验失败（架构错误/缺失），重新部署")
                marker.delete()
                force = true
            } catch (_: Exception) {
                return
            }
        }
        executor.submit {
            // Extract from APK assets to filesDir/bin (cache)
            for (name in BINS) {
                try {
                    val dst = File(dir, name)
                    if (!force && dst.exists() && dst.length() > 1000) continue
                    ctx.assets.open("bin/$name").use { i ->
                        FileOutputStream(dst).use { o -> i.copyTo(o, 65536) }
                    }
                    dst.setReadable(true, false)
                    if (name in EXEC) dst.setExecutable(true, false)
                    android.util.Log.i("wxhook:Setup", "extracted $name (${dst.length()})")
                } catch (e: Exception) {
                    android.util.Log.e("wxhook:Setup", "failed $name: $e")
                }
            }
            // Copy to /data/local/tmp/wxhook_bin/ (where SELinux allows execution)
            val tmpDir = TMP_DIR
            try {
                val copyResult = RootGateways.run("mkdir -p $tmpDir && cp " + dir.absolutePath + "/* $tmpDir/ && chmod 755 $tmpDir/*")
                if (!copyResult.isSuccess) {
                    android.util.Log.e("wxhook:Setup", "Failed to copy binaries to $tmpDir")
                    return@submit
                }

                // Verify binaries work (退出码判定, 见 verifyExec 注释)
                for (name in EXEC) {
                    if (!verifyExec("$tmpDir/$name")) {
                        android.util.Log.e("wxhook:Setup", "Binary $name verification failed")
                        return@submit
                    }
                    android.util.Log.i("wxhook:Setup", "verified $name")
                }

                marker.writeText("ok")
                android.util.Log.i("wxhook:Setup", "copied to $tmpDir")
            } catch (e: Exception) {
                android.util.Log.e("wxhook:Setup", "copy to tmp failed: $e")
            }
        }
    }

    fun sqlcipher(ctx: Context) = File(ctx.filesDir, "bin/sqlcipher").absolutePath
    fun libDir(ctx: Context) = File(ctx.filesDir, "bin").absolutePath
}
