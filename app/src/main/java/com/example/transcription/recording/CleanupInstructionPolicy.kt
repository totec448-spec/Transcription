package com.example.transcription.recording

/**
 * Decides whether a transcribed cleanup instruction is substantial enough to
 * justify a paid text-model request.
 *
 * Only Unicode letter/number runs count. Provider artifacts such as ".", "…",
 * punctuation, or whitespace therefore never satisfy the threshold. Apostrophe
 * and hyphen compounds remain one spoken word.
 */
internal object CleanupInstructionPolicy {
    private val meaningfulWord = Regex(
        """[\p{L}\p{N}]+(?:['’\-_][\p{L}\p{N}]+)*"""
    )

    fun meaningfulWordCount(instruction: String): Int =
        meaningfulWord.findAll(instruction).count()

    fun shouldRequestRewrite(instruction: String, minimumWords: Int): Boolean =
        meaningfulWordCount(instruction) >= minimumWords.coerceAtLeast(1)
}
