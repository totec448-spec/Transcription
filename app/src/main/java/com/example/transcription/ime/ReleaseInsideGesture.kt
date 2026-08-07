package com.example.transcription.ime

/**
 * A recorder-stage press is intentionally stricter than a drag gesture:
 * leaving the original surface disarms it permanently until the next down.
 */
internal class ReleaseInsideGesture {
    var pressed: Boolean = false
        private set

    fun down(inside: Boolean) {
        pressed = inside
    }

    fun move(inside: Boolean) {
        if (!inside) pressed = false
    }

    fun release(inside: Boolean): Boolean {
        val trigger = pressed && inside
        pressed = false
        return trigger
    }

    fun cancel() {
        pressed = false
    }
}
