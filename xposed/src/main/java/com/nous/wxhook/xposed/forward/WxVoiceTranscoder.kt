package com.nous.wxhook.xposed.forward

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 微信语音是 SILK v3（文件后缀 .amr 但内容以 #!SILK_V3 开头），
 * 系统 MediaPlayer / ffmpeg 都解不了。这里调用随 APK 打包的 silk 解码可执行文件
 * （jniLibs/arm64-v8a/libsilkdec.so，ELF 解释器 /system/bin/linker64）转成 PCM，
 * 再封装成 WAV 给笔记 App 播放。
 */
object WxVoiceTranscoder {

    /** decoder 默认输出 24kHz / 16bit / mono */
    private const val SAMPLE_RATE = 24000

    fun isSilk(file: File): Boolean {
        return try {
            val head = ByteArray(16)
            FileInputStream(file).use { if (it.read(head) < 10) return false }
            String(head, Charsets.ISO_8859_1).contains("SILK")
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile
    private var cachedExe: File? = null

    /**
     * 解码器在模块 APK 的 nativeLibraryDir。
     *
     * 注意：ctx 是微信进程的 Context，`applicationInfo.nativeLibraryDir` 是微信自己的 lib 目录；
     * 而微信进程受 Android 11+ 包可见性限制，`packageManager.getApplicationInfo(模块包名)` 会抛异常。
     * 所以直接扫描 /data/app 下的模块目录（/data/app 是 711，lib 目录 755，可读可执行）。
     */
    private fun findDecoder(ctx: Context): File? {
        cachedExe?.let { if (it.isFile) return it }
        // 1) /proc/self/maps 里能找到模块 APK 的映射（Xposed 的 ClassLoader 没有 codeSource）
        runCatching {
            val line = File("/proc/self/maps").readLines()
                .firstOrNull { it.contains("com.nous.wxhook") }
            WxDownloader.fileLog(ctx, "silk maps=${line?.takeLast(120)}")
            if (line != null) {
                val path = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
                val apk = File(path)
                val pkgDir = if (apk.name.endsWith(".apk")) apk.parentFile else apk.parentFile?.parentFile
                val f = pkgDir?.let { File(it, "lib/arm64/libsilkdec.so") }
                if (f != null && f.isFile) {
                    cachedExe = f
                    WxDownloader.fileLog(ctx, "silk decoder(maps): ${f.absolutePath}")
                    return f
                }
            }
        }
        // 2) 按包名查（多半被包可见性挡住）
        runCatching {
            val info = ctx.packageManager.getApplicationInfo("com.nous.wxhook.xposed", 0)
            val f = File(info.nativeLibraryDir, "libsilkdec.so")
            if (f.isFile) {
                cachedExe = f
                return f
            }
        }
        runCatching {
            File("/data/app").listFiles()?.forEach { hashDir ->
                hashDir.listFiles()?.forEach { pkgDir ->
                    if (!pkgDir.name.startsWith("com.nous.wxhook.xposed")) return@forEach
                    val f = File(pkgDir, "lib/arm64/libsilkdec.so")
                    if (f.isFile) {
                        cachedExe = f
                        WxDownloader.fileLog(ctx, "silk decoder: ${f.absolutePath}")
                        return f
                    }
                }
            }
        }
        return null
    }

    fun transcode(ctx: Context, src: File): File? {
        val exe = findDecoder(ctx)
        if (exe == null) {
            WxDownloader.fileLog(ctx, "silk decoder missing")
            return null
        }
        val stamp = System.currentTimeMillis()
        val pcm = File(ctx.cacheDir, "voice_$stamp.pcm")
        val wav = File(ctx.cacheDir, "voice_$stamp.wav")
        return try {
            val p = ProcessBuilder(exe.absolutePath, src.absolutePath, pcm.absolutePath)
                .redirectErrorStream(true)
                .start()
            p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy()
                WxDownloader.fileLog(ctx, "silk decoder timeout")
                return null
            }
            if (!pcm.isFile || pcm.length() < 100) {
                WxDownloader.fileLog(ctx, "silk decoder produced no pcm (exit=${p.exitValue()})")
                return null
            }
            writeWav(pcm, wav)
            pcm.delete()
            WxDownloader.fileLog(
                ctx,
                "silk transcode ok ${src.name} ${src.length()}B -> ${wav.length()}B"
            )
            wav
        } catch (e: Throwable) {
            WxDownloader.fileLog(ctx, "silk transcode failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { pcm.delete() }
        }
    }

    private fun writeWav(pcm: File, wav: File) {
        val dataLen = pcm.length().toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        BufferedOutputStream(wav.outputStream()).use { out ->
            out.write(header.array())
            pcm.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
    }
}
