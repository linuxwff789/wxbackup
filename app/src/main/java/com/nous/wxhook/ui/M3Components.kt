package com.nous.wxhook.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.Dimension
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.CornerFamily
import com.google.android.material.textview.MaterialTextView

/**
 * MD3 (Material Design 3) UI component factory.
 *
 * All components are built programmatically using M3 theme attributes.
 * This ensures consistent styling across the app and proper
 * Dark Mode / Dynamic Colors support.
 */
object M3 {

    // ── Density helper ──
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ── Color helpers ──
    @ColorInt
    fun colorPrimary(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, "M3")

    @ColorInt
    fun onPrimary(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, "M3")

    @ColorInt
    fun colorPrimaryContainer(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, "M3")

    @ColorInt
    fun onPrimaryContainer(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, "M3")

    @ColorInt
    fun colorSecondary(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, "M3")

    @ColorInt
    fun colorSurface(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, "M3")

    @ColorInt
    fun onSurface(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, "M3")

    @ColorInt
    fun onSurfaceVariant(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, "M3")

    @ColorInt
    fun colorError(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorError, "M3")

    @ColorInt
    fun colorSurfaceVariant(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, "M3")

    @ColorInt
    fun colorOutline(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, "M3")

    // ── Card ──
    /**
     * Create an MD3 Elevated Card.
     */
    fun card(context: Context, block: (MaterialCardView.() -> Unit)? = null): MaterialCardView {
        val card = MaterialCardView(
            context,
            null,
            com.google.android.material.R.attr.materialCardViewElevatedStyle
        ).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setContentPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            // M3 shape: rounded corners
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(context, 12).toFloat())
                .build()
            // Stroke for outline variant
            strokeWidth = 0
            block?.invoke(this)
        }
        return card
    }

    /**
     * Create an MD3 Outlined Card.
     */
    fun outlinedCard(context: Context, block: (MaterialCardView.() -> Unit)? = null): MaterialCardView {
        val card = MaterialCardView(
            context,
            null,
            com.google.android.material.R.attr.materialCardViewOutlinedStyle
        ).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setContentPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(context, 12).toFloat())
                .build()
            block?.invoke(this)
        }
        return card
    }

    // ── Button ──
    /**
     * MD3 Filled Button (primary).
     */
    fun filledButton(context: Context, text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
            setOnClickListener { onClick() }
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
        }
    }

    /**
     * MD3 Filled Tonal Button (primary container).
     */
    fun tonalButton(context: Context, text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
            setOnClickListener { onClick() }
            backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer(context))
            setTextColor(onPrimaryContainer(context))
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
        }
    }

    /**
     * MD3 Outlined Button.
     */
    fun outlinedButton(context: Context, text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
            setOnClickListener { onClick() }
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
        }
    }

    /**
     * Small text-only button (for inline actions).
     */
    fun textButton(context: Context, text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            this.text = text
            setOnClickListener { onClick() }
            insetTop = 0
            insetBottom = 0
            minWidth = 0
        }
    }

    // ── Typography ──
    /**
     * MD3 Title Large (for screen headers).
     */
    fun title(context: Context, text: String): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceTitleLarge).apply {
            this.text = text
        }
    }

    /**
     * MD3 Title Medium (for card titles).
     */
    fun titleMedium(context: Context, text: String): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceTitleMedium).apply {
            this.text = text
        }
    }

    /**
     * MD3 Body Large.
     */
    fun body(context: Context, text: String = ""): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
            this.text = text
        }
    }

    /**
     * MD3 Body Medium.
     */
    fun bodyMedium(context: Context, text: String = ""): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
            this.text = text
        }
    }

    /**
     * MD3 Label Medium (for metadata, timestamps, etc.).
     */
    fun label(context: Context, text: String = ""): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
            this.text = text
        }
    }

    /**
     * MD3 Label Small (for fine print).
     */
    fun labelSmall(context: Context, text: String = ""): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceLabelSmall).apply {
            this.text = text
        }
    }

    /**
     * Monospace body for logs, code, status.
     */
    fun monoBody(context: Context, text: String = ""): MaterialTextView {
        return MaterialTextView(context, null, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
        }
    }

    // ── Spacing ──
    /**
     * Vertical spacer.
     */
    fun sp(context: Context, heightDp: Int): ViewGroup {
        return object : ViewGroup(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                setMeasuredDimension(0, dp(context, heightDp))
            }
        }
    }

    /**
     * Horizontal divider with M3 outline color.
     */
    fun divider(context: Context): ViewGroup {
        return object : ViewGroup(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                val w = View.MeasureSpec.getSize(wSpec)
                setMeasuredDimension(w, dp(context, 1))
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                paint.color = onSurfaceVariant(context)
                paint.alpha = 40
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            private val paint = android.graphics.Paint().apply { isAntiAlias = true }
        }
    }

    // ── Layout helper ──
    /**
     * Vertical LinearLayout with proper M3 padding.
     */
    fun vLayout(context: Context, block: (LinearLayout.() -> Unit)? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            block?.invoke(this)
        }
    }

    fun hLayout(context: Context, block: (LinearLayout.() -> Unit)? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            block?.invoke(this)
        }
    }

    // ── Avatar / Badge ──
    /**
     * Create a colored circle with initial for chat avatars.
     */
    private val AVATAR_COLORS = intArrayOf(
        0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF6750A4.toInt(), 0xFF3F51B5.toInt(),
        0xFF2196F3.toInt(), 0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFFFF5722.toInt(),
        0xFF795548.toInt(), 0xFF607D8B.toInt()
    )

    fun avatarColor(name: String): Int {
        val i = kotlin.math.abs(name.hashCode()) % AVATAR_COLORS.size
        return AVATAR_COLORS[i]
    }

    fun avatarChar(name: String): String {
        val c = name.firstOrNull { it.isLetterOrDigit() } ?: '#'
        return c.toString().uppercase()
    }

    /**
     * Avatar circle TextView.
     */
    fun avatar(context: Context, name: String, sizeDp: Int = 48, textSizeSp: Int = 20): TextView {
        val size = dp(context, sizeDp)
        return TextView(context).apply {
            text = avatarChar(name)
            setTextColor(Color.WHITE)
            textSize = textSizeSp.toFloat()
            gravity = Gravity.CENTER
            setBackgroundColor(avatarColor(name))
            layoutParams = ViewGroup.LayoutParams(size, size)
            // Apply circular shape
            background = GradientDrawable().apply {
                setColor(avatarColor(name))
                cornerRadius = size.toFloat() / 2f
            }
            minWidth = 0
            minHeight = 0
        }
    }

    /**
     * Unread badge.
     */
    fun badge(context: Context, count: Int): TextView {
        return TextView(context).apply {
            text = if (count > 99) "99+" else count.toString()
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFE53935.toInt())
            val pad = dp(context, 6)
            setPadding(pad, dp(context, 2), pad, dp(context, 2))
            minWidth = dp(context, 20)
            background = GradientDrawable().apply {
                setColor(0xFFE53935.toInt())
                cornerRadius = dp(context, 10).toFloat()
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
