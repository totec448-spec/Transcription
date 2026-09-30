package com.example.transcription.recording

import com.example.transcription.data.ReasoningEffortPolicy
import com.example.transcription.data.TranscriptionModel
import org.junit.Assert.*
import org.junit.Test

class CleanupReasoningResolverTest {
    private val model = TranscriptionModel("openrouter-text/test/model", "Test", "", null,
        reasoningEfforts = listOf("high", "medium", "low", "minimal"),
        defaultReasoningEffort = "high", reasoningMandatory = true)

    @Test fun mandatoryNoneAndAutoChooseLowestReportedEffort() {
        for (preference in listOf("none", "auto", "max")) {
            assertEquals(ResolvedCleanupReasoning(true, "minimal"), CleanupReasoningResolver.resolve(model, preference))
        }
    }

    @Test fun supportedExplicitPreferenceSurvives() {
        assertEquals("medium", CleanupReasoningResolver.resolve(model, "medium").effort)
    }

    @Test fun lowestDoesNotDependOnCatalogOrdering() {
        assertEquals("low", ReasoningEffortPolicy.lowest(model.copy(reasoningEfforts = listOf("high", "low", "max"))))
    }

    @Test fun optionalReasoningAllowsOffAndAutoWithoutInventingEfforts() {
        val optional = model.copy(reasoningMandatory = false, reasoningEfforts = listOf("low", "high"))
        assertEquals(listOf("auto", "none", "low", "high"), ReasoningEffortPolicy.options(optional))
        assertEquals("none", CleanupReasoningResolver.resolve(optional, "none").effort)
        assertNull(CleanupReasoningResolver.resolve(optional, "auto").effort)
        assertFalse("medium" in ReasoningEffortPolicy.options(optional))
    }

    @Test fun missingEffortMetadataNeverCreatesLevels() {
        assertEquals(listOf("auto"), ReasoningEffortPolicy.options(model.copy(reasoningEfforts = emptyList())))
    }

    @Test fun missingSelectedModelIsNotSubstituted() {
        assertEquals("openrouter-text/meta/spark", CleanupModelResolver.resolve("openrouter-text/meta/spark", listOf(model)).id)
    }
}
