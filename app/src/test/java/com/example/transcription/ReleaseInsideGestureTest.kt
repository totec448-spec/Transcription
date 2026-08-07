package com.example.transcription

import com.example.transcription.ime.ReleaseInsideGesture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInsideGestureTest {
    @Test
    fun releaseInsideTriggersOneRecorderClick() {
        val gesture = ReleaseInsideGesture()
        gesture.down(inside = true)

        assertTrue(gesture.pressed)
        assertTrue(gesture.release(inside = true))
        assertFalse(gesture.pressed)
    }

    @Test
    fun leavingSurfaceCancelsEvenIfFingerReturnsBeforeRelease() {
        val gesture = ReleaseInsideGesture()
        gesture.down(inside = true)
        gesture.move(inside = false)
        gesture.move(inside = true)

        assertFalse(gesture.release(inside = true))
    }

    @Test
    fun releaseOutsideNeverTriggers() {
        val gesture = ReleaseInsideGesture()
        gesture.down(inside = true)

        assertFalse(gesture.release(inside = false))
    }
}
