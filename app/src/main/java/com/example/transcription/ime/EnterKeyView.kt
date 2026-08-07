package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Contextual editor-action icon with geometry centered on the actual view
 * bounds. Font arrows have asymmetric bearings and looked visibly shifted even
 * inside a centered TextView.
 */
internal class EnterKeyView(context: Context) : View(context) {
    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }
    var icon: ImeEnterIcon = ImeEnterIcon.NEW_LINE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        val horizontal = 16f * density
        val arrow = 8f * density

        paint.color = iconColor
        paint.strokeWidth = 2.7f * density
        paint.style = Paint.Style.STROKE
        path.reset()

        when (icon) {
            ImeEnterIcon.NEW_LINE -> {
                // Bounds are exactly x=-16..16 dp and y=-13..13 dp.
                path.moveTo(centerX + horizontal, centerY - 13f * density)
                path.lineTo(centerX + horizontal, centerY + 2f * density)
                path.quadTo(
                    centerX + horizontal,
                    centerY + 5f * density,
                    centerX + 11f * density,
                    centerY + 5f * density
                )
                path.lineTo(centerX - horizontal, centerY + 5f * density)
                path.moveTo(centerX - horizontal, centerY + 5f * density)
                path.lineTo(centerX - horizontal + arrow, centerY - 3f * density)
                path.moveTo(centerX - horizontal, centerY + 5f * density)
                path.lineTo(centerX - horizontal + arrow, centerY + 13f * density)
            }
            ImeEnterIcon.FORWARD -> {
                path.moveTo(centerX - horizontal, centerY)
                path.lineTo(centerX + horizontal, centerY)
                path.moveTo(centerX + horizontal, centerY)
                path.lineTo(centerX + horizontal - arrow, centerY - arrow)
                path.moveTo(centerX + horizontal, centerY)
                path.lineTo(centerX + horizontal - arrow, centerY + arrow)
            }
            ImeEnterIcon.BACK -> {
                path.moveTo(centerX + horizontal, centerY)
                path.lineTo(centerX - horizontal, centerY)
                path.moveTo(centerX - horizontal, centerY)
                path.lineTo(centerX - horizontal + arrow, centerY - arrow)
                path.moveTo(centerX - horizontal, centerY)
                path.lineTo(centerX - horizontal + arrow, centerY + arrow)
            }
            ImeEnterIcon.SEND -> {
                paint.style = Paint.Style.FILL
                path.moveTo(centerX - 15f * density, centerY - 13f * density)
                path.lineTo(centerX + 16f * density, centerY)
                path.lineTo(centerX - 15f * density, centerY + 13f * density)
                path.lineTo(centerX - 7f * density, centerY)
                path.close()
            }
            ImeEnterIcon.DONE -> {
                path.moveTo(centerX - 15f * density, centerY - 1f * density)
                path.lineTo(centerX - 4f * density, centerY + 10f * density)
                path.lineTo(centerX + 16f * density, centerY - 11f * density)
            }
        }
        canvas.save()
        canvas.scale(.9f, .9f, centerX, centerY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
