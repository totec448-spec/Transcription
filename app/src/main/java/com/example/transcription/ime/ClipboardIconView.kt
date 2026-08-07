package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** The clipboard mark, drawn to match the other code-drawn toolbar icons. */
class ClipboardIconView(context: Context) : View(context) {
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
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        paint.color = iconColor
        paint.strokeWidth = 1.9f * density

        val halfWidth = 7.2f * density
        val halfHeight = 9f * density
        bounds.set(
            centerX - halfWidth,
            centerY - halfHeight + 2f * density,
            centerX + halfWidth,
            centerY + halfHeight
        )
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(bounds, 2.4f * density, 2.4f * density, paint)

        // The clip at the top is filled so the mark stays readable at 25 dp.
        val clipHalf = 3.6f * density
        bounds.set(
            centerX - clipHalf,
            centerY - halfHeight,
            centerX + clipHalf,
            centerY - halfHeight + 4f * density
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(bounds, 1.3f * density, 1.3f * density, paint)

        paint.style = Paint.Style.STROKE
        val lineInset = 3.4f * density
        listOf(-1.6f, 2.2f).forEach { offset ->
            canvas.drawLine(
                centerX - halfWidth + lineInset,
                centerY + offset * density,
                centerX + halfWidth - lineInset,
                centerY + offset * density,
                paint
            )
        }
    }
}
