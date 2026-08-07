package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The back/forward mark, drawn rather than shipped twice: forward is the exact
 * mirror of back, so one geometry serves both and they can never drift apart.
 *
 * A plain horizontal arrow, deliberately. The curved arrow this replaced was
 * the same shape language as the Enter key's bent return arrow, which is what
 * made the pair unreadable: two curved arrows sitting near a third meant
 * "undo", "redo" and "newline" all had to be told apart by curvature. A shaft
 * with one head is the most basic thing that can mean "back", and at 20 dp it
 * survives where an arc's tail turns to mush.
 */
class UndoIconView(context: Context) : View(context) {
    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var mirrored: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val head = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        paint.color = iconColor
        paint.strokeWidth = 2.1f * density

        val saved = canvas.save()
        if (mirrored) canvas.scale(-1f, 1f, centerX, centerY)

        // The head is inset from the shaft's end so the round cap does not sit
        // proud of the barbs and blunt the point.
        val half = 5.6f * density
        val barb = 4.0f * density
        canvas.drawLine(centerX - half, centerY, centerX + half, centerY, paint)

        head.reset()
        head.moveTo(centerX - half + barb, centerY - barb)
        head.lineTo(centerX - half, centerY)
        head.lineTo(centerX - half + barb, centerY + barb)
        canvas.drawPath(head, paint)

        canvas.restoreToCount(saved)
    }
}
