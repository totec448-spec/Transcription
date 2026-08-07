package com.example.transcription

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.transcription.data.ThemeMode
import com.example.transcription.data.HistoryNavigationController
import com.example.transcription.data.TranscriptionEntry
import com.example.transcription.recording.AudioImportService
import com.example.transcription.recording.NotificationHelper
import com.example.transcription.recording.RecordingService
import com.example.transcription.ui.OnboardingScreen
import com.example.transcription.ui.TranscriptionApp
import com.example.transcription.ui.theme.TranscriptionTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var startAfterPermission = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (startAfterPermission && grants[Manifest.permission.RECORD_AUDIO] == true) sendRecordingAction(RecordingService.ACTION_START)
        startAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.initialize(this)
        enableEdgeToEdge()
        if (intent.getBooleanExtra(EXTRA_REQUEST_RECORDING, false)) requestRecordingAndStart()
        AppContainer.models.refresh()
        // Corrects a stored model whose provider has no key — an install that
        // only ever entered one key would otherwise keep showing the shipped
        // default forever. A reachable choice is left exactly as the user set it.
        AppContainer.persistResolvedTranscriptionModel()
        if (AppContainer.settings.settings.value.persistentReadyNotification) NotificationHelper.showReady(this)
        setContent {
            val settings by AppContainer.settings.settings.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            TranscriptionTheme(darkTheme = dark) {
                if (settings.onboardingCompleted) {
                    TranscriptionApp(
                        onRecordingAction = { action ->
                            if (action == RecordingService.ACTION_START) requestRecordingAndStart() else sendRecordingAction(action)
                        },
                        onRetranscribe = ::sendRetranscriptionAction,
                        onImportAudio = { uris -> importAudio(uris) }
                    )
                } else {
                    OnboardingScreen(
                        onFinished = {
                            AppContainer.settings.update { it.copy(onboardingCompleted = true) }
                            AppContainer.persistResolvedTranscriptionModel()
                            // The catalog was fetched before any key existed, so
                            // the picker would otherwise open on an empty list.
                            AppContainer.models.refresh(force = true)
                        }
                    )
                }
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_RECORDING, false)) requestRecordingAndStart()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (AppContainer.settings.settings.value.persistentReadyNotification) {
            NotificationHelper.restoreCurrent(this)
        }
    }

    private fun requestRecordingAndStart() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) sendRecordingAction(RecordingService.ACTION_START)
        else {
            startAfterPermission = true
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun sendRecordingAction(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(action))
    }

    private fun sendRetranscriptionAction(historyId: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_RETRANSCRIBE)
                .putExtra(RecordingService.EXTRA_HISTORY_ID, historyId)
        )
    }

    private fun handleIncomingIntent(incoming: Intent) {
        val uris = when (incoming.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") incoming.getParcelableExtra(Intent.EXTRA_STREAM)
            })
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                @Suppress("DEPRECATION") incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            else -> return
        }
        if (uris.isNotEmpty()) importAudio(uris, incoming.type)
        incoming.action = null
    }

    private fun importAudio(uris: List<Uri>, mimeType: String? = null) {
        uris.distinct().forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val historyId = UUID.randomUUID().toString()
            HistoryNavigationController.open(historyId)
            val serviceIntent = Intent(this, AudioImportService::class.java)
                .setAction(AudioImportService.ACTION_IMPORT)
                .putExtra(AudioImportService.EXTRA_URI, uri.toString())
                .putExtra(AudioImportService.EXTRA_MIME_TYPE, mimeType ?: contentResolver.getType(uri))
                .putExtra(AudioImportService.EXTRA_HISTORY_ID, historyId)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            serviceIntent.clipData = android.content.ClipData.newRawUri("Audio", uri)
            runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
                .onFailure {
                    AppContainer.history.add(
                        TranscriptionEntry(
                            id = historyId,
                            text = "",
                            createdAt = System.currentTimeMillis(),
                            durationMs = 0L,
                            modelId = AppContainer.settings.settings.value.selectedModel,
                            sourceName = "Shared audio",
                            failureMessage = "Android did not grant access to this shared audio. Share it again or choose it from Notes."
                        )
                    )
                }
        }
    }

    companion object {
        const val EXTRA_REQUEST_RECORDING = "request_recording"
    }
}
