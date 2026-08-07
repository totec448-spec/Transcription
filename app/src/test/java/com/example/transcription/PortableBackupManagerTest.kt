package com.example.transcription

import com.example.transcription.data.PortableBackupManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PortableBackupManagerTest {
    @Test
    fun backupPathsAcceptPortableIdsAndSupportedAudioFormats() {
        assertEquals("note_123-A", PortableBackupManager.safeId("note_123-A"))
        assertEquals("ogg", PortableBackupManager.safeFormat("OGG"))
        assertEquals("wav", PortableBackupManager.safeFormat("WAV"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun backupIdsRejectPathTraversal() {
        PortableBackupManager.safeId("../../files/settings")
    }

    @Test(expected = IllegalArgumentException::class)
    fun backupRejectsUnknownAudioFormat() {
        PortableBackupManager.safeFormat("flac")
    }
}
