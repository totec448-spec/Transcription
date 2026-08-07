package com.example.transcription.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min

/**
 * The flyout of the space key's long press.
 *
 * Cells are painted instead of composed from child views because the finger
 * that opened the menu never leaves the space key: highlighting and committing
 * are driven from that key's own touch stream, not from child click listeners.
 *
 * The shape follows the recorder controls rather than a stock keyboard popup:
 * one pill, one round accent coin under the finger, and a real elevation shadow
 * so it reads as a layer above the microphone instead of a pasted-on panel.
 */
class PunctuationStripView(context: Context) : View(context) {
    var symbols: List<String> = DEFAULT_SYMBOLS
        set(value) {
            field = value
            highlighted = NO_CELL
            requestLayout()
            invalidate()
        }

    var surfaceColor: Int = Color.WHITE

    /**
     * A dark-on-dark elevation shadow alone is almost invisible, so the pill
     * keeps a hairline edge in this color to stay separated from the keyboard.
     */
    var edgeColor: Int = Color.BLACK
    var foregroundColor: Int = Color.BLACK
    var accentColor: Int = Color.BLUE
    var accentForegroundColor: Int = Color.WHITE

    private val density = resources.displayMetrics.density
    private val preferredCellWidth = (44f * density).toInt()
    private val minimumCellWidth = (30f * density).toInt()
    private val cellHeight = (48f * density).toInt()
    private val edgePadding = (6f * density).toInt()
    // The finger stays on the space key below the pill, so the reach downward
    // has to cover that key. Past it the gesture is a deliberate cancel.
    private val slopAbove = 40f * density
    private val slopBelow = 100f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f * density
    }
    private val bounds = RectF()
    private val location = IntArray(2)
    private var cellWidth = preferredCellWidth
    private var highlighted = NO_CELL

    /** The symbol under the finger, or `null` while the finger is off the strip. */
    val highlightedSymbol: String?
        get() = symbols.getOrNull(highlighted)

    init {
        elevation = 10f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
    }

    fun clearHighlight() {
        if (highlighted == NO_CELL) return
        highlighted = NO_CELL
        invalidate()
    }

    /** Screen coordinates so the caller can forward raw touch positions unchanged. */
    fun updateHighlight(screenX: Float, screenY: Float) {
        getLocationOnScreen(location)
        val index = cellAt(screenX - location[0], screenY - location[1])
        if (index == highlighted) return
        highlighted = index
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = symbols.size.coerceAtLeast(1)
        cellWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            preferredCellWidth
        } else {
            ((MeasureSpec.getSize(widthMeasureSpec) - edgePadding * 2) / count)
                .coerceIn(minimumCellWidth, preferredCellWidth)
        }
        setMeasuredDimension(
            edgePadding * 2 + cellWidth * count,
            edgePadding * 2 + cellHeight
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pillRadius = height / 2f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.style = Paint.Style.FILL
        fill.color = surfaceColor
        canvas.drawRoundRect(bounds, pillRadius, pillRadius, fill)
        bounds.inset(density / 2f, density / 2f)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = density
        fill.color = Color.argb(
            48,
            Color.red(edgeColor),
            Color.green(edgeColor),
            Color.blue(edgeColor)
        )
        canvas.drawRoundRect(bounds, pillRadius, pillRadius, fill)
        fill.style = Paint.Style.FILL

        val coinRadius = min(cellWidth, cellHeight) / 2f - 3f * density
        val centerY = height / 2f
        val baseline = centerY - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        symbols.forEachIndexed { index, symbol ->
            val centerX = edgePadding + index * cellWidth + cellWidth / 2f
            if (index == highlighted) {
                fill.color = accentColor
                canvas.drawCircle(centerX, centerY, coinRadius, fill)
            }
            text.color = if (index == highlighted) accentForegroundColor else foregroundColor
            canvas.drawText(symbol, centerX, baseline, text)
        }
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (symbols.isEmpty()) return NO_CELL
        if (y < -slopAbove || y > height + slopBelow) return NO_CELL
        if (x < 0f || x > width.toFloat()) return NO_CELL
        return ((x - edgePadding) / cellWidth).toInt().coerceIn(0, symbols.lastIndex)
    }

    companion object {
        private const val NO_CELL = -1

        /**
         * Sentence punctuation only. `!` and `?` are deliberately absent because
         * dictation already produces them from intonation.
         */
        val DEFAULT_SYMBOLS = listOf(".", ",", ":", "-", "(", ")")
    }
}
