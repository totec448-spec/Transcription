package com.example.transcription.data

/**
 * Shared expansion and batching thresholds for every large model browser and
 * the keyboard Notes browser. Keeping the thresholds here prevents the app and
 * IME implementations from drifting into separate behaviors.
 */
internal object BrowserRenderingPolicy {
    private const val BROWSER_PAGE_SIZE = 30

    fun nextBatchEnd(renderedCount: Int, totalCount: Int): Int =
        (renderedCount.coerceAtLeast(0) + BROWSER_PAGE_SIZE)
            .coerceAtMost(totalCount.coerceAtLeast(0))

    fun modelGroupStartsCollapsed(modelCount: Int): Boolean = modelCount >= 3

    fun appModels(models: List<TranscriptionModel>): List<TranscriptionModel> =
        models.filterNot { it.streaming || ProviderModels.isStreaming(it.id) }
}
