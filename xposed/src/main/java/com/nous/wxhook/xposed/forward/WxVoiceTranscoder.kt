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

    fun transcode(ctx: Context, src: File): File? {
        // 注意：ctx 是微信进程的 Context，applicationInfo.nativeLibraryDir 指向微信自己的 lib 目录；
        // silk 解码器在模块 APK 里，必须按模块包名取。
        val exe = try {
            val info = ctx.packageManager.getApplicationInfo("com.nous.wxhook.xposed", 0)
            File(info.nativeLibraryDir, "libsilkdec.so")
        } catch (_: Throwable) {
            File(ctx.applicationInfo.nativeLibraryDir, "libsilkdec.so")
        }
        if (!exe.isFile) {
            WxDownloader.fileLog(ctx, "silk decoder missing: ${exe.absolutePath}")
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
