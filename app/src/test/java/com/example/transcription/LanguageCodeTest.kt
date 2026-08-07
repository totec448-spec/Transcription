package com.example.transcription

import com.example.transcription.data.LanguageCode
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageCodeTest {
    @Test fun normalizesLegacyAndLocaleValues() {
        assertEquals("de", LanguageCode.normalize("German"))
        assertEquals("de", LanguageCode.normalize("de-DE"))
        assertEquals("auto", LanguageCode.normalize(""))
        assertEquals("auto", LanguageCode.normalize("not-a-language"))
    }
}
