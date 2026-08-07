package com.example.transcription.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal enum class ImeEnterIcon { NEW_LINE, FORWARD, BACK, SEND, DONE }

internal data class ImeEnterBehavior(
    val editorAction: Int?,
    val icon: ImeEnterIcon,
    val description: String
) {
    val insertsNewLine: Boolean get() = editorAction == null
}

/** Deterministic editor behavior that must never depend on stale process state. */
internal object ImeInteractionPolicy {
    fun ownsBatchState(expectedRequestId: String?, stateRequestId: String?): Boolean =
        expectedRequestId != null && expectedRequestId == stateRequestId

    fun enterBehavior(
        inputType: Int,
        imeOptions: Int,
        actionId: Int = 0,
        hasCustomActionLabel: Boolean = false
    ): ImeEnterBehavior {
        val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val isMultilineText =
            inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

        if (noEnterAction || isMultilineText) return newLine()

        if (hasCustomActionLabel && actionId != 0) {
            return ImeEnterBehavior(actionId, ImeEnterIcon.FORWARD, "Run editor action")
        }

        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH ->
                ImeEnterBehavior(action, ImeEnterIcon.FORWARD, "Search")
            EditorInfo.IME_ACTION_SEND ->
                ImeEnterBehavior(action, ImeEnterIcon.SEND, "Send")
            EditorInfo.IME_ACTION_GO ->
                ImeEnterBehavior(action, ImeEnterIcon.FORWARD, "Go")
            EditorInfo.IME_ACTION_NEXT ->
                ImeEnterBehavior(action, ImeEnterIcon.FORWARD, "Next")
            EditorInfo.IME_ACTION_PREVIOUS ->
                ImeEnterBehavior(action, ImeEnterIcon.BACK, "Previous")
            EditorInfo.IME_ACTION_DONE ->
                ImeEnterBehavior(action, ImeEnterIcon.DONE, "Done")
            else -> newLine()
        }
    }

    private fun newLine() = ImeEnterBehavior(
        editorAction = null,
        icon = ImeEnterIcon.NEW_LINE,
        description = "Insert a new line"
    )
}
