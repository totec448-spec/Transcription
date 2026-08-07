package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Tiny code-drawn keyboard glyph so the IME does not need a Gboard wordmark,
 * font-dependent symbol, or another raster asset.
 */
class KeyboardIconView(context: Context) : View(context) {
    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.coerceAtMost(height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        paint.color = iconColor
        paint.strokeWidth = size * .075f
        canvas.drawRoundRect(
            RectF(left + size * .18f, top + size * .26f, left + size * .82f, top + size * .74f),
            size * .07f,
            size * .07f,
            paint
        )
        paint.strokeWidth = size * .055f
        for (row in 0..1) {
            for (column in 0..3) {
                val x = left + size * (.29f + column * .14f)
                val y = top + size * (.39f + row * .13f)
                canvas.drawPoint(x, y, paint)
            }
        }
        canvas.drawLine(
            left + size * .32f,
            top + size * .65f,
            left + size * .68f,
            top + size * .65f,
            paint
        )
    }
}

/**
 * Native counterpart of the app's Notes icon. Keeping the same simple line
 * geometry makes the IME toolbar visually consistent without starting Compose
 * or loading a bitmap for one 28 dp glyph.
 */
internal class HistoryIconView(context: Context) : View(context) {
    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.coerceAtMost(height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        paint.color = iconColor
        paint.strokeWidth = size * .085f

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            canvas.drawLine(
                left + size * x1,
                top + size * y1,
                left + size * x2,
                top + size * y2,
                paint
            )
        }

        line(.18f, .24f, .82f, .24f)
        line(.18f, .50f, .70f, .50f)
        line(.18f, .76f, .55f, .76f)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(left + size * .86f, top + size * .76f, paint.strokeWidth * .55f, paint)
        paint.style = Paint.Style.STROKE
    }
}

/**
 * A geometry-centered chevron for the floating overlay Back button. A font
 * glyph has asymmetric bearings and was visibly offset inside the old button.
 */
internal class BackIconView(context: Context) : View(context) {
    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.coerceAtMost(height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        paint.color = iconColor
        paint.strokeWidth = size * .09f
        canvas.drawLine(
            left + size * .62f,
            top + size * .22f,
            left + size * .32f,
            top + size * .50f,
            paint
        )
        canvas.drawLine(
            left + size * .32f,
            top + size * .50f,
            left + size * .62f,
            top + size * .78f,
            paint
        )
    }
}
