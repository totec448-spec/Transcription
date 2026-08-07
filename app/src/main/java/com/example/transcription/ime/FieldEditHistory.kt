package com.example.transcription.ime

import android.os.SystemClock

/**
 * Undo/redo for text the keyboard can see, in any app.
 *
 * Android gives an IME no access to a host app's undo stack, so this keeps its
 * own: the full field text is captured and stepping back writes an earlier
 * capture into the field again.
 *
 * Two kinds of capture feed it. Our own operations — inserting a transcript,
 * pasting from the clipboard, a spoken edit — bracket themselves, so each is
 * one undo step. Ordinary typing is sampled on a timer instead, because a
 * snapshot costs a binder round trip and per-character capture would put one on
 * every keytap. That makes undo land on roughly ten-second boundaries while
 * typing, and exactly on the edit when the keyboard did something.
 *
 * The window is deliberately short. This is a way back out of the last thing
 * that happened, not a document history, and restoring text from minutes ago
 * over what someone has since written is worse than offering nothing.
 */
class FieldEditHistory(
    private val maxEntries: Int = 16,
    private val retentionMs: Long = RETENTION_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Snapshot(val text: String, val recordedAt: Long)

    private val entries = ArrayDeque<Snapshot>()
    private var cursor = -1

    val canUndo: Boolean
        get() {
            prune()
            return cursor > 0
        }

    val canRedo: Boolean
        get() {
            prune()
            return cursor >= 0 && cursor < entries.size - 1
        }

    fun clear() {
        entries.clear()
        cursor = -1
    }

    /**
     * The state before an operation. Ignored when it matches the current head,
     * so a repeated capture cannot create an undo step that changes nothing.
     */
    fun recordBefore(text: String) {
        prune()
        if (cursor >= 0 && entries[cursor].text == text) return
        dropRedoBranch()
        push(text)
    }

    /** The state after an operation, which becomes the new head. */
    fun recordAfter(text: String) {
        prune()
        if (cursor >= 0 && entries[cursor].text == text) return
        dropRedoBranch()
        push(text)
    }

    /**
     * A timer sample of whatever the user has been typing. Unlike an operation
     * it has no "before", so it simply becomes the newest state; the step back
     * from it is the previous sample.
     */
    fun recordSample(text: String) = recordAfter(text)

    /**
     * Pulls the live field in as the newest state before stepping back, so the
     * characters typed since the last sample are themselves undone first rather
     * than skipped over. Ignored while the cursor is already inside the stack,
     * where the head is our own doing and not the user's.
     */
    fun captureUncommitted(text: String) {
        prune()
        if (cursor != entries.size - 1) return
        if (cursor >= 0 && entries[cursor].text == text) return
        push(text)
    }

    fun undo(): String? {
        if (!canUndo) return null
        cursor -= 1
        return entries[cursor].text
    }

    fun redo(): String? {
        if (!canRedo) return null
        cursor += 1
        return entries[cursor].text
    }

    private fun push(text: String) {
        entries.addLast(Snapshot(text, now()))
        while (entries.size > maxEntries) entries.removeFirst()
        cursor = entries.size - 1
    }

    private fun dropRedoBranch() {
        while (entries.size > cursor + 1) entries.removeLast()
    }

    /**
     * Expiry is per entry, so the reachable depth is always "about a minute of
     * work" rather than a fixed number of steps whose age depends on how fast
     * the user was typing.
     *
     * The newest entry always survives, expired or not: it is the state the
     * field is in, and dropping it would leave the next capture with nothing to
     * compare against.
     */
    private fun prune() {
        val deadline = now() - retentionMs
        while (entries.size > 1 && entries.first().recordedAt < deadline) {
            entries.removeFirst()
            cursor -= 1
        }
        cursor = cursor.coerceIn(-1, entries.size - 1)
    }

    private companion object {
        /** One minute back, sampled about every ten seconds while typing. */
        const val RETENTION_MS = 60 * 1_000L
    }
}
