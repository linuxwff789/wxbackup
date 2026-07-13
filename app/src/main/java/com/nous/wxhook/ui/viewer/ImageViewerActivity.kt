package com.nous.wxhook.ui.viewer

import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class ImageViewerActivity : AppCompatActivity() {

    private var imgPath: String? = null
    private lateinit var imageView: ImageView
    private val matrix = Matrix()
    private var savedMatrix = Matrix()
    private var startPoint = PointF()
    private var mode = NONE
    private var scaleDetector: ScaleGestureDetector? = null

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imgPath = intent.getStringExtra("path") ?: return finish()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Toolbar with close button
        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(com.google.android.material.R.drawable.abc_ic_clear_material)
            setNavigationContentDescription("关闭")
            setNavigationOnClickListener { finish() }
            title = File(imgPath ?: "").name
        }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(imgPath))
            // Center initial image
            post {
                val vw = width.toFloat(); val vh = height.toFloat()
                val dw = drawable?.intrinsicWidth?.toFloat() ?: vw
                val dh = drawable?.intrinsicHeight?.toFloat() ?: vh
                val scale = minOf(vw/dw, vh/dh, 1f)
                matrix.setScale(scale, scale)
                matrix.postTranslate((vw - dw*scale)/2, (vh - dh*scale)/2)
                imageView.imageMatrix = matrix
            }
            setOnTouchListener { _, event ->
                scaleDetector?.onTouchEvent(event)
                when (event.action and MotionEvent.ACTION_MASK) {
                    MotionEvent.ACTION_DOWN -> {
                        savedMatrix.set(matrix)
                        startPoint.set(event.x, event.y)
                        mode = DRAG
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> mode = ZOOM
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = NONE
                    MotionEvent.ACTION_MOVE -> if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                    }
                }
                try { imageView.imageMatrix = matrix } catch (_: Exception) {}
                true
            }
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val sf = detector.scaleFactor
                val cx = detector.focusX; val cy = detector.focusY
                matrix.postScale(sf, sf, cx, cy)
                return true
            }
        })

        root.addView(toolbar)
        root.addView(imageView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}