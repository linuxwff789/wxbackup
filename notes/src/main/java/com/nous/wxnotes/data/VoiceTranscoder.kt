package com.nous.wxnotes.data

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 微信语音是 SILK v3（后缀 .amr，内容以 #!SILK_V3 开头），系统 MediaPlayer 放不了。
 * notes APK 里内置了 silk 解码可执行文件（jniLibs/arm64-v8a/libsilkdec.so），
 * 这里调用它把 SILK 转成 WAV（24kHz/16bit/mono），再用 MediaPlayer 播放。
 */
object VoiceTranscoder {

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

    /** 同目录生成 <name>.wav，成功返回 wav 文件。 */
    fun transcode(context: Context, src: File): File? {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libsilkdec.so")
        if (!exe.isFile) return null
        val stem = src.name.substringBeforeLast('.')
        val pcm = File(src.parentFile, "$stem.pcm")
        val wav = File(src.parentFile, "$stem.wav")
        return try {
            val p = ProcessBuilder(exe.absolutePath, src.absolutePath, pcm.absolutePath)
                .redirectErrorStream(true)
                .start()
            p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy()
                return null
            }
            if (!pcm.isFile || pcm.length() < 100) return null
            writeWav(pcm, wav)
            pcm.delete()
            wav
        } catch (_: Throwable) {
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
