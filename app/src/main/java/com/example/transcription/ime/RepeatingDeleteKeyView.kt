package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * A single-character delete key with keyboard-style accelerated hold repeat.
 *
 * Repetition is one main-thread callback at a time; no animator, background
 * thread, or frame loop exists while the key is not pressed.
 */
class RepeatingDeleteKeyView(context: Context) : View(context) {
    var onDelete: () -> Unit = {}
    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val handler = Handler(Looper.getMainLooper())
    private var heldSince = 0L
    private var held = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private val repeat = object : Runnable {
        override fun run() {
            if (!held) return
            onDelete()
            val elapsed = SystemClock.uptimeMillis() - heldSince
            val delayMs = when {
                elapsed >= 2_000L -> 32L
                elapsed >= 1_000L -> 48L
                else -> 78L
            }
            handler.postDelayed(this, delayMs)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        onDelete()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = true
                heldSince = SystemClock.uptimeMillis()
                isPressed = true
                performClick()
                handler.removeCallbacks(repeat)
                handler.postDelayed(repeat, INITIAL_REPEAT_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                held = false
                isPressed = false
                handler.removeCallbacks(repeat)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        val left = centerX - 15f * density
        val right = centerX + 15f * density
        val top = centerY - 10f * density
        val bottom = centerY + 10f * density
        val notch = centerX - 6f * density

        paint.color = iconColor
        paint.strokeWidth = 2.1f * density
        path.reset()
        path.moveTo(left, centerY)
        path.lineTo(notch, top)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(notch, bottom)
        path.close()
        canvas.drawPath(path, paint)

        val crossCenterX = centerX + 5f * density
        val crossHalf = 4.5f * density
        canvas.drawLine(
            crossCenterX - crossHalf,
            centerY - crossHalf,
            crossCenterX + crossHalf,
            centerY + crossHalf,
            paint
        )
        canvas.drawLine(
            crossCenterX + crossHalf,
            centerY - crossHalf,
            crossCenterX - crossHalf,
            centerY + crossHalf,
            paint
        )
    }

    override fun onDetachedFromWindow() {
        held = false
        handler.removeCallbacks(repeat)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val INITIAL_REPEAT_DELAY_MS = 360L
    }
}
