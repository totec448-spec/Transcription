package com.example.transcription.ime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Keeps the microphone exactly centered and places each outer control column
 * halfway between the visible microphone button and its keyboard edge.
 *
 * Both columns deliberately contain two slots. The lower-left slot is empty
 * today, so the planned fourth control can be added without shifting the
 * existing layout or introducing another container.
 */
internal class BalancedRecorderControlsLayout(
    context: Context,
    private val centerButtonDiameterPx: Int,
    private val edgeInsetPx: Int
) : ViewGroup(context) {
    private lateinit var leftColumn: View
    private lateinit var centerControl: View
    private lateinit var rightColumn: View

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setControls(left: View, center: View, right: View) {
        removeAllViews()
        leftColumn = left
        centerControl = center
        rightColumn = right
        addView(left)
        addView(center)
        addView(right)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(::leftColumn.isInitialized) { "Recorder controls were not configured." }
        measureExactLayoutSize(leftColumn)
        measureExactLayoutSize(centerControl)
        measureExactLayoutSize(rightColumn)

        val desiredHeight = max(
            centerControl.measuredHeight,
            max(leftColumn.measuredHeight, rightColumn.measuredHeight)
        ) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentLeft = paddingLeft
        val contentRight = width - paddingRight
        val centerX = (contentLeft + contentRight) / 2f
        val centerY = (paddingTop + height - paddingBottom) / 2f

        layoutCentered(centerControl, centerX, centerY)

        val visibleCenterHalf = centerButtonDiameterPx / 2f
        val leftCorridorCenter =
            (contentLeft + edgeInsetPx + centerX - visibleCenterHalf) / 2f
        val rightCorridorCenter =
            (centerX + visibleCenterHalf + contentRight - edgeInsetPx) / 2f
        layoutCentered(leftColumn, leftCorridorCenter, centerY)
        layoutCentered(rightColumn, rightCorridorCenter, centerY)
    }

    private fun measureExactLayoutSize(child: View) {
        val params = child.layoutParams
        require(params.width >= 0 && params.height >= 0) {
            "Recorder controls require exact layout dimensions."
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY)
        )
    }

    private fun layoutCentered(child: View, centerX: Float, centerY: Float) {
        val childLeft = (centerX - child.measuredWidth / 2f).roundToInt()
        val childTop = (centerY - child.measuredHeight / 2f).roundToInt()
        child.layout(
            childLeft,
            childTop,
            childLeft + child.measuredWidth,
            childTop + child.measuredHeight
        )
    }
}
