package com.nous.wxnotes.ui

import android.content.Context
import android.media.MediaPlayer
import android.util.AttributeSet
import android.widget.VideoView

/**
 * 按视频原始宽高比测量自身，避免被父容器拉伸。
 * - 普通模式（宽度 EXACTLY / 高度 AT_MOST）：宽度撑满，高度按比例，超限时缩宽度
 * - 全屏模式（EXACTLY / EXACTLY）：等比 letterbox，居中由父 Box 负责
 */
class AspectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {

    var ratio: Float = 0f
        private set

    init {
        // 注意：VideoView 内部有自己的 mPreparedListener，外部 listener 会在它之后被调用，覆盖是安全的
        super.setOnPreparedListener { mp ->
            onVideoPrepared(mp)
        }
    }

    private fun onVideoPrepared(mp: MediaPlayer) {
        val w = mp.videoWidth
        val h = mp.videoHeight
        if (w > 0 && h > 0) {
            ratio = w.toFloat() / h
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ratio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        if (wSize <= 0 || hSize <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        var targetW: Int
        var targetH: Int
        if (wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY) {
            val boxRatio = wSize.toFloat() / hSize
            if (ratio > boxRatio) {
                targetW = wSize
                targetH = (wSize / ratio).toInt()
            } else {
                targetH = hSize
                targetW = (hSize * ratio).toInt()
            }
        } else {
            targetW = wSize
            targetH = (wSize / ratio).toInt()
            if (hMode == MeasureSpec.AT_MOST && targetH > hSize) {
                targetH = hSize
                targetW = (hSize * ratio).toInt()
            }
        }
        if (targetW <= 0) targetW = wSize
        if (targetH <= 0) targetH = hSize
        setMeasuredDimension(targetW, targetH)
    }
}
