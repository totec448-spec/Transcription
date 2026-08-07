package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Code-drawn and bounds-centered because font spacebar glyphs have visibly
 * uneven bearings on different Android system fonts.
 *
 * A hold opens the punctuation flyout. The touch stream stays on this key for
 * the whole gesture, so the finger can slide onto a symbol and release there
 * without a second tap.
 */
class SpaceKeyView(context: Context) : View(context) {
    var onSpace: () -> Unit = {}
    var onMenuOpen: (Float, Float) -> Unit = { _, _ -> }
    var onMenuMove: (Float, Float) -> Unit = { _, _ -> }
    var onMenuClose: (Boolean) -> Unit = {}

    var iconColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val gesture = ReleaseInsideGesture()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var menuOpen = false
    private var downX = 0f
    private var downY = 0f
    private var screenX = 0f
    private var screenY = 0f

    private val openMenu = Runnable {
        if (menuOpen) return@Runnable
        menuOpen = true
        isPressed = false
        gesture.cancel()
        onMenuOpen(screenX, screenY)
    }

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        onSpace()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                screenX = event.rawX
                screenY = event.rawY
                menuOpen = false
                gesture.down(event.isInside(this))
                isPressed = gesture.pressed
                handler.removeCallbacks(openMenu)
                handler.postDelayed(openMenu, HOLD_TO_OPEN_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                screenX = event.rawX
                screenY = event.rawY
                if (menuOpen) {
                    onMenuMove(event.rawX, event.rawY)
                } else {
                    val drifted = abs(event.x - downX) > touchSlop ||
                        abs(event.y - downY) > touchSlop
                    if (drifted) handler.removeCallbacks(openMenu)
                    gesture.move(event.isInside(this))
                    isPressed = gesture.pressed
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(openMenu)
                isPressed = false
                if (menuOpen) {
                    menuOpen = false
                    onMenuMove(event.rawX, event.rawY)
                    onMenuClose(true)
                } else if (gesture.release(event.isInside(this))) {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(openMenu)
                isPressed = false
                gesture.cancel()
                if (menuOpen) {
                    menuOpen = false
                    onMenuClose(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(openMenu)
        if (menuOpen) {
            menuOpen = false
            onMenuClose(false)
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        val halfWidth = 14f * density
        val rise = 6f * density
        paint.color = iconColor
        paint.strokeWidth = 2.2f * density
        path.reset()
        path.moveTo(centerX - halfWidth, centerY - rise)
        path.lineTo(centerX - halfWidth, centerY + rise)
        path.lineTo(centerX + halfWidth, centerY + rise)
        path.lineTo(centerX + halfWidth, centerY - rise)
        canvas.drawPath(path, paint)
    }

    private fun MotionEvent.isInside(view: View) =
        x >= 0f && x < view.width.toFloat() && y >= 0f && y < view.height.toFloat()

    private companion object {
        const val HOLD_TO_OPEN_MS = 300L
    }
}
