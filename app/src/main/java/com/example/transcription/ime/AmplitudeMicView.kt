package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.example.transcription.R
import kotlin.math.abs

class AmplitudeMicView(context: Context) : View(context) {
    private val microphoneDrawable = context.getDrawable(R.drawable.ic_mic)?.mutate()

    var level: Float = 0f
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            // Audio arrives at up to 20 Hz. Ignore sub-visible noise jitter and
            // let Android coalesce redraws onto the display frame boundary.
            if (abs(field - normalized) < 0.01f) return
            field = normalized
            postInvalidateOnAnimation()
        }

    var accentColor: Int = Color.rgb(66, 133, 244)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var iconColor: Int = Color.rgb(17, 17, 15)
        set(value) {
            if (field == value) return
            field = value
            microphoneDrawable?.setTint(value)
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        microphoneDrawable?.setTint(iconColor)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.coerceAtMost(height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        // Linear in the normalized level. The square root that used to sit here
        // made the halo jump to its maximum on the first syllable.
        val haloRadius = size * (0.30f + level * 0.14f)
        paint.color = Color.argb(
            if (active) 48 else 22,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, haloRadius, paint)

        paint.color = Color.argb(
            if (active) 112 else 68,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.012f
        canvas.drawCircle(centerX, centerY, haloRadius * .86f, paint)

        paint.style = Paint.Style.FILL
        paint.color = accentColor
        canvas.drawCircle(centerX, centerY, size * BUTTON_DIAMETER_RATIO / 2f, paint)

        // Reuse the app's small, conventional vector icon rather than growing
        // hand-built geometry with the button.
        val iconSize = (size * .22f).toInt()
        val left = (centerX - iconSize / 2f).toInt()
        val top = (centerY - iconSize / 2f).toInt()
        microphoneDrawable?.setBounds(left, top, left + iconSize, top + iconSize)
        microphoneDrawable?.draw(canvas)
    }

    companion object {
        /**
         * Diameter of the painted button relative to the view's shorter side.
         * The touch target is deliberately much larger, which is why pressed
         * and hovered feedback has to be masked rather than filling the view.
         */
        const val BUTTON_DIAMETER_RATIO = 0.54f
    }
}
