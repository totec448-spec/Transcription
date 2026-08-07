package com.example.transcription.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.example.transcription.data.BrowserRenderingPolicy
import com.example.transcription.data.TranscriptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeInteractionPolicyTest {
    @Test
    fun historyRowsAreExposedInThirtyItemPages() {
        assertEquals(30, BrowserRenderingPolicy.nextBatchEnd(0, 440))
        assertEquals(60, BrowserRenderingPolicy.nextBatchEnd(30, 440))
        assertEquals(47, BrowserRenderingPolicy.nextBatchEnd(30, 47))
        assertEquals(0, BrowserRenderingPolicy.nextBatchEnd(0, 0))
    }

    @Test
    fun appModelBrowsersExcludeLiveModels() {
        val batch = TranscriptionModel("batch", "Batch", "", null)
        val flaggedLive = TranscriptionModel("flagged-live", "Live", "", null, streaming = true)
        val knownLive = TranscriptionModel(
            "assemblyai/live/universal-streaming-english",
            "Known live",
            "",
            null
        )

        assertEquals(
            listOf(batch),
            BrowserRenderingPolicy.appModels(listOf(flaggedLive, batch, knownLive))
        )
    }

    @Test
    fun modelGroupsCollapseFromThreeItems() {
        assertFalse(BrowserRenderingPolicy.modelGroupStartsCollapsed(2))
        assertTrue(BrowserRenderingPolicy.modelGroupStartsCollapsed(3))
        assertTrue(BrowserRenderingPolicy.modelGroupStartsCollapsed(200))
    }

    @Test
    fun staleOrUnownedBatchStateIsNeverAccepted() {
        assertFalse(ImeInteractionPolicy.ownsBatchState(null, "old"))
        assertFalse(ImeInteractionPolicy.ownsBatchState("new", null))
        assertFalse(ImeInteractionPolicy.ownsBatchState("new", "old"))
        assertTrue(ImeInteractionPolicy.ownsBatchState("new", "new"))
    }

    @Test
    fun explicitSearchFieldRunsSearchAction() {
        val behavior = ImeInteractionPolicy.enterBehavior(
            inputType = InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        )

        assertEquals(EditorInfo.IME_ACTION_SEARCH, behavior.editorAction)
        assertEquals("Search", behavior.description)
    }

    @Test
    fun multilineFieldInsertsNewLineEvenIfEditorAdvertisesSend() {
        val behavior = ImeInteractionPolicy.enterBehavior(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            imeOptions = EditorInfo.IME_ACTION_SEND
        )

        assertTrue(behavior.insertsNewLine)
        assertNull(behavior.editorAction)
    }

    @Test
    fun noEnterActionFlagKeepsSendStyleEditorMultiline() {
        val behavior = ImeInteractionPolicy.enterBehavior(
            inputType = InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        )

        assertTrue(behavior.insertsNewLine)
    }

    @Test
    fun unspecifiedEditorDefaultsToNewLine() {
        val behavior = ImeInteractionPolicy.enterBehavior(
            inputType = InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
        )

        assertTrue(behavior.insertsNewLine)
    }
}
