package com.example.transcription.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AutomaticCleanupTaskTest {
    @Test fun skipUnblocksImmediatelyAndLateResponseCannotOverwriteRawText() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val task = AutomaticCleanupTask("raw", {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            returned.countDown()
            CleanedText("late rewrite", 0.01)
        }, { cancelled.countDown() }).start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(task.skip())
            assertEquals("raw", task.await().text)
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals("raw", task.await().text)
            assertFalse(task.skip())
        } finally { release.countDown() }
    }

    @Test fun skipBeforeWorkerStartsMakesNoRequest() {
        val called = AtomicBoolean(false)
        val task = AutomaticCleanupTask("raw", { called.set(true); CleanedText("clean", null) }, {})
        task.skip()
        assertEquals("raw", task.start().await().text)
        assertFalse(called.get())
    }

    @Test fun completedCleanupWinsBeforeSkip() {
        val task = AutomaticCleanupTask("raw", { CleanedText("clean", 0.01) }, {}).start()
        assertEquals("clean", task.await().text)
        assertFalse(task.skip())
        assertEquals("clean", task.await().text)
    }

    @Test fun failedCleanupKeepsRawWithVisibleReason() {
        val task = AutomaticCleanupTask("raw", { error("HTTP 503 provider unavailable") }, {}).start()
        val result = task.await()
        assertEquals("raw", result.text)
        assertTrue(result.warning!!.contains("HTTP 503"))
    }
}
