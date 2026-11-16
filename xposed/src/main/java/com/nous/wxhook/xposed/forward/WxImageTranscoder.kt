package com.nous.wxhook.xposed.forward

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
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
            // 尺寸只用于 configure；真正宽高以解码器 output format / Image 为准
            val size = parseSpsSize(data, offset)
            val (w, h) = size ?: (1280 to 720)
            if (size == null) {
                WxDownloader.fileLog(ctx, "transcode: sps parse failed ${src.name}, fallback ${w}x$h")
            }
            val bmp = decodeFirstFrame(ctx, data, offset, w, h) ?: return null
            // 压到 550KB 以内：这样 publishUpdate 会走单次广播内联，
            // 不再依赖分片（分片在笔记进程被冻结时容易整包丢失）。
            var quality = 92
            while (true) {
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (out.length() <= 550_000 || quality <= 60) break
                quality -= 8
            }
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
            // HEVC 亮度采样宽高通常为偶数，可过滤掉误命中的偏移
            if (w % 2 != 0 || h % 2 != 0) return null
            w to h
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- MediaCodec 解码 ----------------

    private data class Nal(val type: Int, val start: Int, val end: Int)

    /** 按 Annex-B 切出所有 NAL（start 含起始码）。 */
    private fun parseNals(data: ByteArray, offset: Int): List<Nal> {
        val out = ArrayList<Nal>()
        var i = offset
        while (true) {
            val sc = findStartCode(data, i)
            if (sc < 0) break
            val len = startCodeLen(data, sc)
            if (len == 0) break
            val nalStart = sc + len
            if (nalStart + 2 > data.size) break
            val type = (data[nalStart].toInt() shr 1) and 0x3F
            val next = findStartCode(data, nalStart + 1)
            val end = if (next < 0) data.size else next
            out.add(Nal(type, sc, end))
            if (next < 0) break
            i = next
        }
        return out
    }

    private fun decodeFirstFrame(
        ctx: Context?,
        data: ByteArray,
        offset: Int,
        w: Int,
        h: Int
    ): Bitmap? {
        val allNals = parseNals(data, offset)
        if (allNals.isEmpty()) return null
        // wxgf 里可能有多组参数集+IDR（小图 + 主图）；同一 codec 解两段不同序列会进 error，
        // 只取最后一组（从最后一个 VPS 开始）。
        val vpsIdx = allNals.indices.filter { allNals[it].type == 32 }
        val groupStart = if (vpsIdx.size > 1) vpsIdx.last() else 0
        val nals = allNals.subList(groupStart, allNals.size)
        // 最后一组 VPS/SPS/PPS 作为 csd-0
        val ps = nals.filter { it.type == 32 || it.type == 33 || it.type == 34 }
        val csdBytes = java.io.ByteArrayOutputStream().also { bos ->
            ps.takeLast(3).forEach { bos.write(data, it.start, it.end - it.start) }
        }.toByteArray()
        WxDownloader.fileLog(
            ctx,
            "transcode: nals=${nals.size}/${allNals.size} types=${nals.joinToString(",") { it.type.toString() }} csd=${csdBytes.size}B"
        )

        for (name in decoderCandidates()) {
            val bmp = try {
                decodeWith(ctx, name, data, nals, csdBytes, w, h)
            } catch (e: Throwable) {
                WxDownloader.fileLog(ctx, "transcode: $name failed ${e.javaClass.simpleName} ${e.message}")
                null
            }
            if (bmp != null) return bmp
        }
        return null
    }

    private fun decoderCandidates(): List<String> {
        val out = ArrayList<String>()
        try {
            out.add(MIME)   // 默认（通常硬件）
        } catch (_: Throwable) {
        }
        try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
                .map { it.name }
                .filter { it !in out }
                .forEach { out.add(it) }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun decodeWith(
        ctx: Context?,
        codecName: String,
        data: ByteArray,
        nals: List<Nal>,
        csd: ByteArray,
        w: Int,
        h: Int
    ): Bitmap? {
        var codec: MediaCodec? = null
        try {
            val fmt = MediaFormat.createVideoFormat(MIME, w, h)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(data.size, 512 * 1024))
            if (csd.isNotEmpty()) {
                fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            }
            codec = try {
                if (codecName == MIME) MediaCodec.createDecoderByType(MIME)
                else MediaCodec.createByCodecName(codecName)
            } catch (e: Throwable) {
                WxDownloader.fileLog(ctx, "transcode: create $codecName failed: ${e.message}")
                return null
            }
            WxDownloader.fileLog(ctx, "transcode: codec=${codec.name} size=${w}x$h csd=${csd.size}B")
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            val pending = ArrayList(nals)
            var nalIdx = 0
            var eosQueued = false
            var bmp: Bitmap? = null

            while (System.currentTimeMillis() < deadline) {
                if (!eosQueued && nalIdx < pending.size) {
                    val inIdx = codec.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val nal = pending[nalIdx]
                            var off = nal.start
                            var left = nal.end - nal.start
                            if (left <= buf.capacity()) {
                                buf.put(data, off, left)
                                val last = nalIdx == pending.size - 1
                                codec.queueInputBuffer(
                                    inIdx, 0, left, 0,
                                    if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                )
                                if (last) eosQueued = true
                                nalIdx++
                            } else {
                                val n = buf.capacity()
                                buf.put(data, off, n)
                                codec.queueInputBuffer(inIdx, 0, n, 0, 0)
                                pending[nalIdx] = Nal(nal.type, off + n, nal.end)
                            }
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 1000)
                if (outIdx >= 0) {
                    val img = try {
                        codec.getOutputImage(outIdx)
                    } catch (e: Throwable) {
                        WxDownloader.fileLog(ctx, "transcode: getOutputImage err ${e.message}")
                        null
                    }
                    if (img != null) {
                        WxDownloader.fileLog(
                            ctx,
                            "transcode: frame ${img.width}x${img.height} planes=${img.planes.size} " +
                                "cf=${codec.outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT, -1)}"
                        )
                        bmp = toBitmap(img)
                        img.close()
                        codec.releaseOutputBuffer(outIdx, false)
                        break
                    }
                    WxDownloader.fileLog(
                        ctx,
                        "transcode: output $outIdx size=${info.size} flags=${info.flags} " +
                            "cf=${codec.outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT, -1)}"
                    )
                    codec.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    WxDownloader.fileLog(ctx, "transcode: output format changed ${codec.outputFormat}")
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            return bmp
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
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
