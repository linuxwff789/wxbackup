package com.nous.wxhook.xposed.forward

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream

/**
 * 微信 image2 下的大图是私有容器（魔数 "wxgf"），头部之后是标准 Annex-B HEVC。
 * Android/Coil 无法直接解码，这里用系统 MediaCodec 解出首帧并转存 JPEG。
 *
 * 容器布局（实测）：
 *   [0,40)     "wxgf" + 私有头
 *   [40, ...)  00 00 00 01 起始的 HEVC NAL（VPS/SPS/PPS + 图像）
 */
object WxImageTranscoder {

    private const val TAG = "[wxhook:TC]"
    private const val MIME = "video/hevc"
    private const val TIMEOUT_MS = 10_000L

    fun isWxContainer(file: File): Boolean {
        return try {
            val head = ByteArray(4)
            java.io.FileInputStream(file).use { if (it.read(head) != 4) return false }
            head[0] == 'w'.code.toByte() && head[1] == 'x'.code.toByte() &&
                (head[2] == 'g'.code.toByte() || head[2] == 'f'.code.toByte())
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 转码到 cacheDir/wxtc/，已存在则复用。失败返回 null（调用方应退回高清缩略图）。
     */
    fun transcode(ctx: Context?, src: File): File? {
        if (ctx == null) return null
        val dir = File(ctx.cacheDir, "wxtc").apply { mkdirs() }
        val out = File(dir, "${src.name.substringBefore('.')}_${src.length()}.jpg")
        if (out.isFile && out.length() > 0) return out

        val started = System.currentTimeMillis()
        return try {
            val data = src.readBytes()
            val offset = findStartCode(data)
            if (offset < 0) {
                WxDownloader.fileLog(ctx, "transcode: no annex-b start code in ${src.name}")
                return null
            }
            val size = parseSpsSize(data, offset)
            if (size == null) {
                WxDownloader.fileLog(ctx, "transcode: sps parse failed ${src.name}")
                return null
            }
            val (w, h) = size
            val bmp = decodeFirstFrame(ctx, data, offset, w, h) ?: return null
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            WxDownloader.fileLog(
                ctx,
                "transcode ok ${src.name} ${w}x${h} ${src.length()}B -> ${out.length()}B " +
                    "cost=${System.currentTimeMillis() - started}ms"
            )
            out
        } catch (e: Throwable) {
            WxDownloader.fileLog(
                ctx,
                "transcode failed ${src.name}: ${e.javaClass.simpleName} ${e.message}\n" +
                    e.stackTraceToString().lines().take(8).joinToString("\n")
            )
            null
        }
    }

    // ---------------- HEVC 解析 ----------------

    private fun findStartCode(data: ByteArray, from: Int = 0): Int {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLen(data: ByteArray, at: Int): Int = when {
        at + 3 < data.size && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte() -> 4
        at + 2 < data.size && data[at + 2] == 1.toByte() -> 3
        else -> 0
    }

    /** 去掉 00 00 03 防竞争字节。 */
    private fun unescape(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(to - from)
        var zeros = 0
        for (i in from until to) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(private val b: ByteArray) {
        var pos = 0
        fun bit(): Int {
            if (pos >= b.size * 8) throw IndexOutOfBoundsException("bits eof")
            val v = (b[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun skip(n: Int) {
            if (pos + n > b.size * 8) throw IndexOutOfBoundsException("bits eof")
            pos += n
        }
        fun ue(): Int {
            var lz = 0
            while (bit() == 0) {
                lz++
                if (lz > 31) throw IllegalStateException("ue overflow")
            }
            if (lz == 0) return 0
            var v = 0
            repeat(lz) { v = (v shl 1) or bit() }
            return ((1 shl lz) - 1) + v
        }
    }

    /** 遍历 NAL，取第一个 SPS(nal type 33) 的 RBSP。 */
    private fun findSpsRbsp(data: ByteArray, offset: Int): ByteArray? {
        var i = offset
        while (true) {
            val sc = findStartCode(data, i)
            if (sc < 0) return null
            val len = startCodeLen(data, sc)
            if (len == 0) return null
            val nalStart = sc + len
            if (nalStart + 2 >= data.size) return null
            val nalType = (data[nalStart].toInt() shr 1) and 0x3F
            val next = findStartCode(data, nalStart + 1)
            val nalEnd = if (next < 0) data.size else next
            if (nalType == 33) {
                val body = unescape(data, nalStart + 2, nalEnd)
                if (body.size >= 8) return body
            }
            if (next < 0) return null
            i = next
        }
    }

    /**
     * 解出 pic_width/height。
     *
     * 规范上 profile_tier_level 是 88 位，不同编码器/版本会有偏差（实测某个微信样本
     * 的有效起点在 102 位），所以先试标准偏移，再在 80..140 区间扫描并用取值范围校验。
     */
    private fun parseSpsSize(data: ByteArray, offset: Int): Pair<Int, Int>? {
        val rbsp = findSpsRbsp(data, offset) ?: return null
        parseAt(rbsp, 8 + 88)?.let { return it }
        for (p in 80..140) {
            parseAt(rbsp, p)?.let { return it }
        }
        return null
    }

    private fun parseAt(rbsp: ByteArray, start: Int): Pair<Int, Int>? {
        return try {
            val r = Bits(rbsp)
            r.skip(start)
            val sid = r.ue()          // sps_seq_parameter_set_id
            val chroma = r.ue()       // chroma_format_idc
            if (sid > 15 || chroma > 3) return null
            val w = r.ue()            // pic_width_in_luma_samples
            val h = r.ue()            // pic_height_in_luma_samples
            if (w < 16 || h < 16 || w > 16384 || h > 16384) return null
            w to h
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- MediaCodec 解码 ----------------

    private fun decodeFirstFrame(
        ctx: Context?,
        data: ByteArray,
        offset: Int,
        w: Int,
        h: Int
    ): Bitmap? {
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        try {
            reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
            val fmt = MediaFormat.createVideoFormat(MIME, w, h)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(data.size - offset, 512 * 1024))
            codec = MediaCodec.createDecoderByType(MIME)
            WxDownloader.fileLog(ctx, "transcode: codec=${codec.name} size=${w}x$h")
            codec.configure(fmt, reader.surface, null, 0)
            codec.start()
            WxDownloader.fileLog(ctx, "transcode: codec started")

            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var inOff = offset
            var inLeft = data.size - offset
            var eosQueued = false
            var image: Image? = null

            while (System.currentTimeMillis() < deadline) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val n = minOf(buf.capacity(), inLeft)
                            if (n > 0) buf.put(data, inOff, n)
                            val flags = if (inLeft - n <= 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            codec.queueInputBuffer(inIdx, 0, n, 0, flags)
                            inOff += n
                            inLeft -= n
                            if (flags != 0) eosQueued = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 1000)
                if (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true)
                    image = reader.acquireLatestImage()
                    if (image != null) break
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 输出格式变化，忽略
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }

            val img = image ?: return null
            return try {
                toBitmap(img)
            } finally {
                img.close()
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                reader?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun toBitmap(image: Image): Bitmap? {
        if (image.planes.size < 3) return null
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val y = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val u = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val v = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        if (yRowStride <= 0 || uvRowStride <= 0 || uvPixelStride <= 0) return null

        val out = IntArray(w * h)
        var idx = 0
        for (row in 0 until h) {
            val yBase = row * yRowStride
            val uvBase = (row / 2) * uvRowStride
            if (yBase + w > y.size) return null
            for (col in 0 until w) {
                val yv = y[yBase + col].toInt() and 0xFF
                val uvOff = uvBase + (col / 2) * uvPixelStride
                if (uvOff >= u.size || uvOff >= v.size) return null
                val uv = (u[uvOff].toInt() and 0xFF) - 128
                val vv = (v[uvOff].toInt() and 0xFF) - 128
                val r = yv + (1.370705f * vv).toInt()
                val g = yv - (0.337633f * uv).toInt() - (0.698001f * vv).toInt()
                val b = yv + (1.732446f * uv).toInt()
                out[idx++] = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
