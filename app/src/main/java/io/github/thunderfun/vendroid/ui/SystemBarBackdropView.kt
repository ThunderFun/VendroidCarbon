package io.github.thunderfun.vendroid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Paints the strips exposed by edge-to-edge system bars. The content root is
 * inset by the same values, so this view only shows through in the status,
 * navigation, and display-cutout areas.
 *
 * Status-bar and navigation-bar colors are kept separate because fullscreen
 * video blacks out the status bar while the navigation bar is hidden.
 */
class SystemBarBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val navigationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val insets = Rect()

    init {
        setWillNotDraw(false)
    }

    fun setBarColors(statusColor: Int, navigationColor: Int) {
        if (statusPaint.color == statusColor && navigationPaint.color == navigationColor) {
            return
        }
        statusPaint.color = statusColor
        navigationPaint.color = navigationColor
        invalidate()
    }

    fun setSystemBarInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (insets.left == left && insets.top == top &&
            insets.right == right && insets.bottom == bottom
        ) {
            return
        }
        insets.set(left, top, right, bottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = insets.left
        val top = insets.top
        val right = insets.right
        val bottom = insets.bottom
        val w = width.toFloat()
        val h = height.toFloat()

        if (top > 0) {
            canvas.drawRect(0f, 0f, w, top.toFloat(), statusPaint)
        }
        if (bottom > 0) {
            canvas.drawRect(0f, h - bottom, w, h, navigationPaint)
        }
        // Side system bars/cutouts get the navigation color; in landscape
        // the navigation bar can be on either side.
        val bodyTop = top.toFloat()
        val bodyBottom = (h - bottom).coerceAtLeast(bodyTop)
        if (left > 0) {
            canvas.drawRect(0f, bodyTop, left.toFloat(), bodyBottom, navigationPaint)
        }
        if (right > 0) {
            canvas.drawRect(w - right, bodyTop, w, bodyBottom, navigationPaint)
        }
    }
}
