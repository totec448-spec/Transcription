package com.example.transcription.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.transcription.AppContainer
import com.example.transcription.R
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.RecordingState

object NotificationHelper {
    const val ACTIVE_ID = 1101
    const val READY_ID = 1102
    const val IMPORT_ID = 1103
    private const val ACTIVE_CHANNEL = "recording"
    private const val READY_CHANNEL = "quick_record"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ACTIVE_CHANNEL, "Active recording", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(READY_CHANNEL, "Quick record", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /**
     * The body PendingIntent is deliberately independent from action PendingIntents. Android's
     * expansion affordance is System UI chrome and does not fire this content intent.
     */
    fun active(context: Context, state: RecordingState): Notification {
        ensureChannels(context)
        val provider = ProviderModels.provider(
            state.modelId ?: AppContainer.settings.settings.value.selectedModel
        ).label
        val title = when (state.phase) {
            RecordingPhase.PAUSED -> "Recording paused"
            // The pipeline's own words when it has them: uploading, waiting on
            // the provider and cleaning up all live inside PROCESSING.
            RecordingPhase.PROCESSING -> state.statusLabel?.takeIf { it.isNotBlank() }?.plus("…")
                ?: "Sending to $provider…"
            else -> "Recording"
        }
        val builder = Notification.Builder(context, ACTIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText("")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setDeleteIntent(action(context, RecordingActionReceiver.ACTION_RESTORE_NOTIFICATION, 42))

        // Custom RemoteViews suppress the template chronometer on some Xiaomi
        // SystemUI versions. Render exactly one explicit timer in our row.
        builder.setShowWhen(false).setUsesChronometer(false)

        if (state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.PAUSED) {
            val bodyAction = action(context, RecordingService.ACTION_FINISH, 40)
            builder
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(
                    content(
                        context,
                        title,
                        bodyAction,
                        elapsedMs = state.elapsedMs,
                        timerRunning = state.phase == RecordingPhase.RECORDING
                    )
                )
                .setCustomBigContentView(
                    content(
                        context,
                        title,
                        bodyAction,
                        elapsedMs = state.elapsedMs,
                        timerRunning = state.phase == RecordingPhase.RECORDING
                    )
                )
            val pauseLabel = if (state.phase == RecordingPhase.PAUSED) "Resume" else "Pause"
            if (!state.isLive) {
                builder.addAction(R.drawable.ic_pause, pauseLabel, action(context, RecordingService.ACTION_TOGGLE_PAUSE, 2))
            }
            builder.addAction(R.drawable.ic_delete, "Discard", action(context, RecordingService.ACTION_DISCARD, 3))
            builder.addAction(R.drawable.ic_done, "Done", action(context, RecordingService.ACTION_FINISH, 4))
        } else if (state.phase == RecordingPhase.PROCESSING) {
            builder
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(content(context, title, null))
                .setCustomBigContentView(content(context, title, null))
            if (!state.isLive) {
                builder.addAction(R.drawable.ic_done, "Use fallback", action(context, RecordingService.ACTION_USE_FALLBACK, 5))
            }
            builder.addAction(
                R.drawable.ic_close,
                "Abandon",
                action(
                    context,
                    if (state.isLive) RecordingService.ACTION_DISCARD else RecordingService.ACTION_ABANDON,
                    6
                )
            )
        }
        return builder.build()
    }

    fun showReady(context: Context, preview: String = "") {
        ensureChannels(context)
        val bodyAction = action(context, RecordingService.ACTION_START, 10)
        val title = if (preview.isBlank()) "Ready to transcribe" else "Transcript copied"
        val notification = Notification.Builder(context, READY_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText("")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(content(context, title, bodyAction))
            .setCustomBigContentView(content(context, title, bodyAction))
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setDeleteIntent(action(context, RecordingActionReceiver.ACTION_RESTORE_NOTIFICATION, 41))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(READY_ID, notification)
    }

    fun importing(context: Context, text: String): Notification {
        ensureChannels(context)
        return Notification.Builder(context, ACTIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Importing voice note…")
            .setContentText(text.take(120))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun cancelReady(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(READY_ID)

    fun restoreCurrent(context: Context) {
        val state = RecordingController.state.value
        if (state.phase == RecordingPhase.RECORDING ||
            state.phase == RecordingPhase.PAUSED ||
            state.phase == RecordingPhase.PROCESSING
        ) {
            context.getSystemService(NotificationManager::class.java).notify(ACTIVE_ID, active(context, state))
        } else if (AppContainer.settings.settings.value.persistentReadyNotification) {
            showReady(context)
        }
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, RecordingActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun content(
        context: Context,
        title: String,
        bodyAction: PendingIntent?,
        elapsedMs: Long? = null,
        timerRunning: Boolean = false
    ) = RemoteViews(context.packageName, R.layout.notification_transcription).apply {
        setTextViewText(R.id.notification_title, title)
        if (elapsedMs != null) {
            setViewVisibility(R.id.notification_chronometer, View.VISIBLE)
            setChronometer(
                R.id.notification_chronometer,
                SystemClock.elapsedRealtime() - elapsedMs,
                null,
                timerRunning
            )
        } else {
            setViewVisibility(R.id.notification_chronometer, View.GONE)
        }
        if (bodyAction != null) setOnClickPendingIntent(R.id.notification_body, bodyAction)
    }
}
