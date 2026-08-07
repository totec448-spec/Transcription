package com.example.transcription.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.transcription.AppContainer
import com.example.transcription.data.ApiKeyProvider
import com.example.transcription.ime.VoiceInputMethodService
import com.example.transcription.ui.theme.Signal

/**
 * The single screen a fresh install opens on.
 *
 * Deliberately not a wizard: everything the app needs before it can do anything
 * is visible at once, in the order it matters, and each item is finished in
 * place. Nothing here is a step the user has to walk through to reach the next
 * one, and only the API key genuinely blocks starting — Android's own microphone
 * and keyboard approvals can be granted later from Setup without breaking
 * anything.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val inputMethodManager = remember {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    fun keyboardEnabled() = inputMethodManager.enabledInputMethodList.any {
        it.packageName == context.packageName &&
            it.serviceName == VoiceInputMethodService::class.java.name
    }

    fun microphoneGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    var openRouterSaved by remember { mutableStateOf(AppContainer.secrets.readApiKey(ApiKeyProvider.OPENROUTER).isNotBlank()) }
    var elevenLabsSaved by remember { mutableStateOf(AppContainer.secrets.readApiKey(ApiKeyProvider.ELEVENLABS).isNotBlank()) }
    var assemblyAiSaved by remember { mutableStateOf(AppContainer.secrets.readApiKey(ApiKeyProvider.ASSEMBLYAI).isNotBlank()) }
    var microphoneReady by remember { mutableStateOf(microphoneGranted()) }
    var keyboardReady by remember { mutableStateOf(keyboardEnabled()) }
    var editingProvider by remember { mutableStateOf<ApiKeyProvider?>(null) }

    val anyKey = openRouterSaved || elevenLabsSaved || assemblyAiSaved

    // Both approvals are granted in Android's own UI, so the only reliable
    // moment to re-read them is when this screen comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                microphoneReady = microphoneGranted()
                keyboardReady = keyboardEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { microphoneReady = microphoneGranted() }

    val openKeyboardSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { keyboardReady = keyboardEnabled() }

    // This screen is shown outside the app's Scaffold, so it has to paint the
    // themed background itself — without it the window background shows through
    // and light-mode cream sits behind dark-mode cards. Edge-to-edge is enabled
    // for the Activity, so the insets are this screen's job too.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(22.dp, 24.dp, 22.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    "Transcription",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Three things to set up. Then you are done.",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SetupCard(
                index = 1,
                title = "API key",
                description = "One key is enough. It is encrypted on this device and never leaves it " +
                    "except to its own provider.",
                done = anyKey
            ) {
                KeyRow("OpenRouter", "Also unlocks cleanup", openRouterSaved) {
                    editingProvider = ApiKeyProvider.OPENROUTER
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
                KeyRow("ElevenLabs", "Scribe v2", elevenLabsSaved) {
                    editingProvider = ApiKeyProvider.ELEVENLABS
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
                KeyRow("AssemblyAI", "Universal-3.5 Pro", assemblyAiSaved) {
                    editingProvider = ApiKeyProvider.ASSEMBLYAI
                }
            }
        }

        item {
            SetupCard(
                index = 2,
                title = "Microphone",
                description = "Needed to record. The notification permission comes with it — " +
                    "recording runs in a notification you can stop from anywhere.",
                done = microphoneReady
            ) {
                if (!microphoneReady) {
                    Button(
                        onClick = {
                            requestMicrophone.launch(
                                buildList {
                                    add(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }.toTypedArray()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow microphone") }
                }
            }
        }

        item {
            // Both the card and the button open Android's keyboard settings,
            // including once the keyboard is already enabled: a finished item
            // that answers no tap at all reads as broken, and this is the only
            // place to turn the keyboard back off again.
            val openImeSettings = {
                openKeyboardSettings.launch(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            SetupCard(
                index = 3,
                title = "Voice keyboard",
                description = "Optional. Dictate straight into any text field. Android requires you " +
                    "to enable it by hand — the app cannot do it for you.",
                done = keyboardReady,
                onClick = openImeSettings
            ) {
                OutlinedButton(
                    onClick = openImeSettings,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (keyboardReady) "Manage in Android" else "Enable in Android") }
            }
        }

        item {
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onFinished,
                    enabled = anyKey,
                    shape = RoundedCornerShape(14.dp),
                    // The default disabled styling washes out to near-invisible
                    // on this palette, which reads as a rendering fault rather
                    // than as a control that is waiting for a key.
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) { Text("Start", fontWeight = FontWeight.SemiBold) }
                Text(
                    if (anyKey) {
                        "Anything left open can be finished later in Setup."
                    } else {
                        "Add at least one API key — the app cannot transcribe without one."
                    },
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }

    editingProvider?.let { provider ->
        ApiKeyDialog(
            provider = provider,
            onDismiss = { editingProvider = null },
            onSaved = {
                when (provider) {
                    ApiKeyProvider.OPENROUTER -> openRouterSaved = true
                    ApiKeyProvider.ELEVENLABS -> elevenLabsSaved = true
                    ApiKeyProvider.ASSEMBLYAI -> assemblyAiSaved = true
                }
                editingProvider = null
                // The shipped default names an AssemblyAI model. Without this the
                // app would open on a model whose key the user never entered.
                AppContainer.persistResolvedTranscriptionModel()
                AppContainer.models.refresh(force = true)
            }
        )
    }
}

@Composable
private fun SetupCard(
    index: Int,
    title: String,
    description: String,
    done: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().let { if (onClick == null) it else it.clickable(onClick = onClick) },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepMarker(index, done)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

/** A number until the item is finished, a check once it is. */
@Composable
private fun StepMarker(index: Int, done: Boolean) {
    Box(
        Modifier.size(26.dp).clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            Modifier.fillMaxSize(),
            shape = CircleShape,
            color = if (done) Signal else Color.Transparent,
            border = if (done) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .45f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (done) {
                    LineIcon(VoiceIcon.CHECK, Modifier.size(14.dp), Color.Black)
                } else {
                    Text(
                        index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(label: String, hint: String, saved: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (saved) "••••••••" else "Not set",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LineIcon(
            VoiceIcon.CHEVRON,
            Modifier.padding(start = 8.dp).size(18.dp),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ApiKeyDialog(
    provider: ApiKeyProvider,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var draft by rememberSaveable(provider) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.label} API key") },
        text = {
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text(provider.placeholder) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    AppContainer.secrets.saveApiKey(provider, draft)
                    draft = ""
                    onSaved()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
