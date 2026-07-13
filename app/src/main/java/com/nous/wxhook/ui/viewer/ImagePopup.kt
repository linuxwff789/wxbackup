package com.nous.wxhook.ui.viewer

import android.app.Dialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class ImagePopup(context: Context) {

    private val dialog: Dialog
    private val imageView: ImageView

    init {
        val root = FrameLayout(context).apply { setBackgroundColor(0xFF000000.toInt()) }

        root.addView(TextView(context).apply {
            text = "✕"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x44000000.toInt())
            setOnClickListener { dialog.dismiss() }
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP or Gravity.END; setMargins(0, 96, 32, 0)
            }
        })

        imageView = ZoomImageView(context)
        root.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(root)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    fun show(path: String) {
        imageView.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
        dialog.show()
    }

    /** ImageView with built-in pinch-zoom and pan */
    private class ZoomImageView(ctx: Context) : ImageView(ctx) {

        private val matrix = Matrix()
        private val saved = Matrix()
        private val start = PointF()
        private var mode = 0
        private var maxScale = 5f; private var minScale = 0.5f
        private val scaleDetector: ScaleGestureDetector

        init {
            scaleType = ScaleType.MATRIX
            scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val sf = d.scaleFactor
                    val cx = d.focusX; val cy = d.focusY
                    // Prevent excessive zoom
                    val cur = getCurrentScale()
                    val next = cur * sf
                    val clamped = if (next > maxScale) maxScale / cur else if (next < minScale) minScale / cur else sf
                    matrix.postScale(clamped, clamped, cx, cy)
                    fixTranslate()
                    imageMatrix = matrix
                    return true
                }
            })
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            val masked = event.actionMasked
            when (masked) {
                MotionEvent.ACTION_DOWN -> {
                    saved.set(matrix); start.set(event.x, event.y); mode = 1
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> mode = 2
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = 0
                MotionEvent.ACTION_MOVE -> if (mode == 1) {
                    matrix.set(saved)
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                    fixTranslate()
                    imageMatrix = matrix
                }
            }
            return true
        }

        private fun getCurrentScale(): Float {
            val v = FloatArray(9); matrix.getValues(v); return v[Matrix.MSCALE_X]
        }

        private fun fixTranslate() {
            val vw = width.toFloat(); val vh = height.toFloat()
            val dw = drawable?.intrinsicWidth?.toFloat() ?: vw
            val dh = drawable?.intrinsicHeight?.toFloat() ?: vh
            val vals = FloatArray(9); matrix.getValues(vals)
            val sx = vals[Matrix.MSCALE_X]; val sy = vals[Matrix.MSCALE_Y]
            var tx = vals[Matrix.MTRANS_X]; var ty = vals[Matrix.MTRANS_Y]
            val iw = dw * sx; val ih = dh * sy
            // Clamp to prevent image from going off-screen
            if (tx > 0) tx = 0f
            if (ty > 0) ty = 0f
            if (tx < vw - iw) tx = vw - iw
            if (ty < vh - ih) ty = vh - ih
            // Don't over-clamp for small images
            if (iw <= vw) tx = (vw - iw) / 2f
            if (ih <= vh) ty = (vh - ih) / 2f
            vals[Matrix.MTRANS_X] = tx; vals[Matrix.MTRANS_Y] = ty
            matrix.setValues(vals)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            post { centerImage() }
        }

        private fun centerImage() {
            val vw = width.toFloat(); val vh = height.toFloat()
            val dw = drawable?.intrinsicWidth?.toFloat() ?: vw
            val dh = drawable?.intrinsicHeight?.toFloat() ?: vh
            val s = minOf(vw / dw, vh / dh, 1f)
            matrix.reset(); matrix.setScale(s, s)
            matrix.postTranslate((vw - dw * s) / 2, (vh - dh * s) / 2)
            imageMatrix = matrix
        }
    }
}