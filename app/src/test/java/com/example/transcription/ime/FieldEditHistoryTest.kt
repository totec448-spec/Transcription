package com.example.transcription.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldEditHistoryTest {
    private var clock = 0L
    private fun history(maxEntries: Int = 20, retentionMs: Long = 120_000L) =
        FieldEditHistory(maxEntries, retentionMs) { clock }

    @Test
    fun oneOperationBecomesExactlyOneUndoStep() {
        val history = history()
        history.recordBefore("hello")
        history.recordAfter("hello world")

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals("hello", history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals("hello world", history.redo())
    }

    @Test
    fun severalOperationsStepBackOneAtATime() {
        val history = history()
        history.recordBefore("a")
        history.recordAfter("a b")
        history.recordBefore("a b")
        history.recordAfter("a b c")

        assertEquals("a b", history.undo())
        assertEquals("a", history.undo())
        assertNull(history.undo())
        assertEquals("a b", history.redo())
        assertEquals("a b c", history.redo())
        assertNull(history.redo())
    }

    @Test
    fun anOperationAfterUndoDropsTheAbandonedRedoBranch() {
        val history = history()
        history.recordBefore("a")
        history.recordAfter("a b")
        history.undo()

        history.recordBefore("a")
        history.recordAfter("a z")

        assertFalse(history.canRedo)
        assertEquals("a", history.undo())
    }

    @Test
    fun anUnchangedFieldNeverCreatesAnEmptyStep() {
        val history = history()
        history.recordBefore("same")
        history.recordAfter("same")

        assertFalse(history.canUndo)
    }

    @Test
    fun theStackLapsesOnceItHasBeenLeftAlone() {
        val history = history(retentionMs = 120_000L)
        history.recordBefore("a")
        history.recordAfter("a b")
        assertTrue(history.canUndo)

        clock += 119_000L
        assertTrue(history.canUndo)

        clock += 2_000L
        assertFalse(history.canUndo)
        assertNull(history.undo())
    }

    @Test
    fun theOldestSnapshotsFallOffAtTheCap() {
        val history = history(maxEntries = 3)
        history.recordBefore("1")
        history.recordAfter("2")
        history.recordAfter("3")
        history.recordAfter("4")

        assertEquals("3", history.undo())
        assertEquals("2", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun typingSampledOnTheTimerBecomesUndoableInSteps() {
        val history = history()
        history.recordSample("")
        clock += 10_000L
        history.recordSample("hello")
        clock += 10_000L
        history.recordSample("hello there")

        assertEquals("hello", history.undo())
        assertEquals("", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun undoFirstRewindsTheCharactersTypedSinceTheLastSample() {
        val history = history()
        history.recordSample("hello")
        clock += 4_000L

        // Four characters typed, no sample taken yet: undo must still reach the
        // state before them rather than doing nothing.
        history.captureUncommitted("hello beep")
        assertEquals("hello", history.undo())
    }

    @Test
    fun undoMidStackIgnoresAnUncommittedCapture() {
        val history = history()
        history.recordSample("a")
        history.recordSample("a b")
        history.recordSample("a b c")
        assertEquals("a b", history.undo())

        // The field now holds what we just restored; capturing it as new typing
        // would strand the redo branch.
        history.captureUncommitted("a b")
        assertEquals("a", history.undo())
        assertEquals("a b", history.redo())
        assertEquals("a b c", history.redo())
    }

    @Test
    fun onlyTheLastMinuteStaysReachable() {
        val history = history(retentionMs = 60_000L)
        history.recordSample("oldest")
        clock += 30_000L
        history.recordSample("middle")
        clock += 40_000L
        history.recordSample("newest")

        // "oldest" is now 70 s back and drops; "middle" at 40 s survives.
        assertEquals("middle", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun clearingLeavesNothingToRestore() {
        val history = history()
        history.recordBefore("a")
        history.recordAfter("a b")
        history.clear()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
