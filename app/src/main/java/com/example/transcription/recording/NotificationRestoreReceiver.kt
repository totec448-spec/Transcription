package com.example.transcription.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.transcription.AppContainer
import com.example.transcription.data.RecordingPhase

/**
 * Restores the opt-in quick-record notification after boot or an app update.
 *
 * Force-stop and disabled notification permission remain Android-controlled hard
 * boundaries; in every normal lifecycle the notification is posted again.
 */
class NotificationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppContainer.initialize(context)
        if (!AppContainer.settings.settings.value.persistentReadyNotification) return
        if (RecordingController.state.value.phase in ACTIVE_PHASES) return
        NotificationHelper.showReady(context)
    }

    private companion object {
        val ACTIVE_PHASES = setOf(
            RecordingPhase.RECORDING,
            RecordingPhase.PAUSED,
            RecordingPhase.PROCESSING
        )
    }
}
