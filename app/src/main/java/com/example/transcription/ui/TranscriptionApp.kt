package com.example.transcription.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.transcription.AppContainer
import com.example.transcription.data.AppSettings
import com.example.transcription.data.ApiKeyProvider
import com.example.transcription.data.BrowserRenderingPolicy
import com.example.transcription.data.ClipboardFeedback
import com.example.transcription.data.AudioCaptureOptions
import com.example.transcription.data.BaseCleanupMode
import com.example.transcription.data.baseCleanupPrompt
import com.example.transcription.data.withBaseCleanupPrompt
import com.example.transcription.data.DEFAULT_CLEANUP_PROMPT
import com.example.transcription.data.DEFAULT_MULTIMODAL_PROMPT
import com.example.transcription.data.ScreenshotReader
import com.example.transcription.data.LanguageCode
import com.example.transcription.data.HistoryNavigationController
import com.example.transcription.data.ModelLanguageCatalog
import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.RecordingState
import com.example.transcription.data.ThemeMode
import com.example.transcription.data.TranscriptionEntry
import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.ModelAvailabilityPolicy
import com.example.transcription.data.TranscriptionProvider
import com.example.transcription.data.SendAudioFormat
import com.example.transcription.ime.VoiceInputMethodService
import com.example.transcription.recording.NotificationHelper
import com.example.transcription.recording.CleanupCoordinator
import com.example.transcription.recording.CleanupPhase
import com.example.transcription.recording.RecordingController
import com.example.transcription.recording.RecordingService
import com.example.transcription.ui.theme.Ink
import com.example.transcription.ui.theme.RecordRed
import com.example.transcription.ui.theme.Signal
import com.example.transcription.widget.TranscriptionWidgetProvider
import java.text.DateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppPage(val label: String, val icon: VoiceIcon) {
    RECORD("Record", VoiceIcon.MIC), HISTORY("Notes", VoiceIcon.HISTORY), SETTINGS("Setup", VoiceIcon.SETTINGS)
}

@Composable
fun TranscriptionApp(
    onRecordingAction: (String) -> Unit,
    onRetranscribe: (String) -> Unit,
    onImportAudio: (List<android.net.Uri>) -> Unit
) {
    var page by rememberSaveable { mutableStateOf(AppPage.RECORD) }
    val pagerState = rememberPagerState(initialPage = page.ordinal, pageCount = { AppPage.entries.size })
    val scope = rememberCoroutineScope()
    val pageHistory = remember { mutableStateListOf<Int>() }
    var lastSettledPage by remember { mutableIntStateOf(pagerState.currentPage) }
    var suppressNextHistory by remember { mutableStateOf(false) }
    val requestedHistoryId by HistoryNavigationController.requestedEntryId.collectAsState()
    LaunchedEffect(requestedHistoryId) {
        if (requestedHistoryId != null && pagerState.currentPage != AppPage.HISTORY.ordinal) {
            pageHistory.add(pagerState.currentPage)
            page = AppPage.HISTORY
            pagerState.animateScrollToPage(AppPage.HISTORY.ordinal)
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        val target = pagerState.settledPage
        if (target != lastSettledPage) {
            if (suppressNextHistory) suppressNextHistory = false else pageHistory.add(lastSettledPage)
            lastSettledPage = target
            page = AppPage.entries[target]
        }
    }
    BackHandler(enabled = pageHistory.isNotEmpty()) {
        val target = pageHistory.removeAt(pageHistory.lastIndex)
        suppressNextHistory = true
        page = AppPage.entries[target]
        scope.launch { pagerState.animateScrollToPage(target) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomDock(page) { target ->
                page = target
                scope.launch { pagerState.animateScrollToPage(target.ordinal) }
            }
        }
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { index ->
            when (AppPage.entries[index]) {
                AppPage.RECORD -> RecordScreen(onRecordingAction, Modifier.padding(padding))
                AppPage.HISTORY -> HistoryScreen(
                    onRetranscribe = onRetranscribe,
                    onImportAudio = onImportAudio,
                    requestedEntryId = requestedHistoryId,
                    modifier = Modifier.padding(padding)
                )
                AppPage.SETTINGS -> SettingsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun BottomDock(selected: AppPage, onSelect: (AppPage) -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp).navigationBarsPadding().fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 4.dp
    ) {
        Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppPage.entries.forEach { page -> DockItem(page, page == selected) { onSelect(page) } }
        }
    }
}

@Composable
private fun RowScope.DockItem(page: AppPage, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, spring(dampingRatio = .6f), label = "dockScale")
    Column(
        Modifier.weight(1f).heightIn(min = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp))
            .clickable(source, null, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LineIcon(page.icon, Modifier.size(21.dp), if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(16.dp).height(3.dp).clip(CircleShape).background(if (selected) Signal else Color.Transparent))
    }
}

@Composable
private fun RecordScreen(onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by RecordingController.state.collectAsState()
    val settings by AppContainer.settings.settings.collectAsState()
    val models by AppContainer.models.models.collectAsState()
    val cleanup by CleanupCoordinator.state.collectAsState()
    val history by AppContainer.history.entries.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val model = models.firstOrNull { it.id == settings.selectedModel }
    var editableText by rememberSaveable { mutableStateOf(state.resultText.ifBlank { history.firstOrNull()?.text.orEmpty() }) }
    LaunchedEffect(state.resultText) { if (state.resultText.isNotBlank() || state.phase == RecordingPhase.SUCCESS) editableText = state.resultText }
    LaunchedEffect(editableText) {
        delay(700)
        val id = state.historyId ?: history.firstOrNull()?.id
        if (id != null && history.firstOrNull { it.id == id }?.text != editableText) {
            AppContainer.history.updateText(id, editableText)
            RecordingController.update { it.copy(resultText = editableText) }
            TranscriptionWidgetProvider.updateAll(context)
        }
    }
    val action: (String) -> Unit = {
        if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onAction(it)
    }

    Column(
        modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TranscriptEditor(
            text = editableText,
            onText = { editableText = it },
            onCopy = { copy(context, editableText) },
            onCleanup = { editableText = it },
            editingEnabled = !(cleanup.ownerId == "home" && cleanup.busy),
            modelName = model?.name?.substringAfter(": ") ?: "OpenRouter",
            fallbackMessage = state.fallbackMessage,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        if (state.phase == RecordingPhase.ERROR) ErrorPanel(state.errorMessage.orEmpty(), state.historyId != null, action)
        ThumbRecorder(state, action)
    }
}

@Composable
private fun ThumbRecorder(state: RecordingState, action: (String) -> Unit) {
    val active = state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.PAUSED
    Surface(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                when {
                    active -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape).background(
                                if (state.phase == RecordingPhase.RECORDING) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            duration(state.elapsedMs),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    state.phase == RecordingPhase.PROCESSING -> Text(
                        "Processing",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Unit
                }
                LivingWaveform(
                    levels = state.waveform,
                    active = state.phase == RecordingPhase.RECORDING,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(initialScale = .88f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(targetScale = .92f))
                },
                label = "thumbControl"
            ) { phase ->
                when (phase) {
                    RecordingPhase.PROCESSING -> ProcessingButton(action)
                    RecordingPhase.RECORDING, RecordingPhase.PAUSED -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BouncyIconButton(
                            if (phase == RecordingPhase.PAUSED) VoiceIcon.PLAY else VoiceIcon.PAUSE,
                            { action(RecordingService.ACTION_TOGGLE_PAUSE) },
                            size = 48.dp
                        )
                        BouncyIconButton(VoiceIcon.CLOSE, { action(RecordingService.ACTION_DISCARD) }, size = 48.dp)
                        BouncyIconButton(
                            VoiceIcon.CHECK,
                            { action(RecordingService.ACTION_FINISH) },
                            size = 58.dp,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    else -> RecordButton { action(RecordingService.ACTION_START) }
                }
            }
        }
    }
}

@Composable
private fun ProcessingButton(action: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BouncyIconButton(
            VoiceIcon.REFRESH,
            { action(RecordingService.ACTION_USE_FALLBACK) },
            size = 48.dp
        )
        BouncyIconButton(
            VoiceIcon.CLOSE,
            { action(RecordingService.ACTION_ABANDON) },
            size = 48.dp
        )
    }
}

@Composable
private fun RecorderHeader(model: TranscriptionModel?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                Text("VOICE NOTE", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
            Text("Say it.", style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(top = 10.dp))
            Text("We’ll keep the audio and write it down.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(model?.name?.substringAfter(": ") ?: "OpenRouter", Modifier.padding(horizontal = 11.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecorderStage(state: RecordingState, action: (String) -> Unit) {
    val active = state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.PAUSED
    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = state.phase,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = .96f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = .98f)) },
            label = "recorderState"
        ) { phase ->
            when (phase) {
                RecordingPhase.PROCESSING -> ProcessingState(action)
                RecordingPhase.RECORDING, RecordingPhase.PAUSED -> ActiveRecorder(state, action)
                else -> IdleRecorder(state, action)
            }
        }
        if (!active && state.phase != RecordingPhase.PROCESSING) {
            Spacer(Modifier.height(10.dp))
            Text(if (state.phase == RecordingPhase.SUCCESS) "Start a new note" else "Tap once. Speak. Done.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IdleRecorder(state: RecordingState, action: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LivingWaveform(emptyList(), false, Modifier.fillMaxWidth().height(92.dp))
        Spacer(Modifier.height(18.dp))
        RecordButton { action(RecordingService.ACTION_START) }
        if (state.phase == RecordingPhase.SUCCESS) {
            Text("Saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun ActiveRecorder(state: RecordingState, action: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (state.phase == RecordingPhase.RECORDING) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant))
            Text(if (state.phase == RecordingPhase.RECORDING) "  RECORDING" else "  PAUSED", style = MaterialTheme.typography.labelSmall, color = if (state.phase == RecordingPhase.RECORDING) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(duration(state.elapsedMs), fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 6.dp))
        LivingWaveform(state.waveform, state.phase == RecordingPhase.RECORDING, Modifier.fillMaxWidth().height(112.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlAction(if (state.phase == RecordingPhase.PAUSED) VoiceIcon.PLAY else VoiceIcon.PAUSE, if (state.phase == RecordingPhase.PAUSED) "Resume" else "Pause") { action(RecordingService.ACTION_TOGGLE_PAUSE) }
            ControlAction(VoiceIcon.CLOSE, "Discard") { action(RecordingService.ACTION_DISCARD) }
            ControlAction(VoiceIcon.CHECK, "Done", emphasized = true) { action(RecordingService.ACTION_FINISH) }
        }
    }
}

@Composable
private fun ProcessingState(action: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().height(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(54.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 6.dp)
        Text("Writing it down…", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
        Text("The recording is already safe.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { action(RecordingService.ACTION_USE_FALLBACK) }) { Text("Use fallback") }
            OutlinedButton(onClick = { action(RecordingService.ACTION_ABANDON) }) { Text("Abandon") }
        }
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .90f else 1f, spring(dampingRatio = .48f, stiffness = 520f), label = "recordPress")
    Box(
        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(15.dp)).clickable(source, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(46.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = .10f)),
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                LineIcon(VoiceIcon.MIC, Modifier.size(23.dp), MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun ControlAction(icon: VoiceIcon, label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BouncyIconButton(
            icon, onClick, size = if (emphasized) 64.dp else 56.dp,
            container = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            content = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LivingWaveform(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    Canvas(modifier) {
        val count = 45
        val values = levels.takeLast(count)
        val gap = size.width / count
        drawLine(color.copy(alpha = .20f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        repeat(count) { index ->
            val raw = if (values.isNotEmpty()) values.getOrElse(index - (count - values.size)) { 0f }
            else (.025f + abs(sin(index * .31f)) * abs(sin(index * .83f + 1.2f)) * .22f)
            val value = raw.coerceIn(0f, 1f)
            // Linear. The square root here used to compensate for a level that
            // spent its life near zero; against a normalized level it only
            // pushes ordinary speech back up against the ceiling.
            val half = if (active) .7f + value * size.height * .32f else 1.1f + value * size.height * .30f
            val x = gap * index + gap / 2
            drawLine(color, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), gap * .42f, StrokeCap.Round)
        }
    }
}

@Composable
private fun ErrorPanel(message: String, archivedAudio: Boolean, action: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().heightIn(max = 180.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Audio kept", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { action(RecordingService.ACTION_RETRY) }, shape = RoundedCornerShape(16.dp)) { Text("Retry") }
                OutlinedButton(onClick = { action(RecordingService.ACTION_DISCARD) }, shape = RoundedCornerShape(16.dp)) {
                    Text(if (archivedAudio) "Close" else "Discard")
                }
            }
        }
    }
}

@Composable
private fun TranscriptEditor(
    text: String,
    onText: (String) -> Unit,
    onCopy: () -> Unit,
    onCleanup: (String) -> Unit,
    editingEnabled: Boolean,
    modelName: String,
    fallbackMessage: String?,
    modifier: Modifier = Modifier
) {
    val cleanup by CleanupCoordinator.state.collectAsState()
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(modelName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fallbackMessage?.takeIf(String::isNotBlank)?.let { message ->
                Text(
                    text = "Fallback used · ${message.replace(Regex("""^Part \d+:\s*"""), "")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                enabled = editingEnabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field -> if (text.isBlank()) Text("Transcript", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)) else field() }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CleanupButton(
                    ownerId = "home",
                    text = text,
                    onReplacement = onCleanup,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary
                )
                if (cleanup.ownerId == "home" && cleanup.busy) {
                    BouncyIconButton(
                        VoiceIcon.CLOSE,
                        { CleanupCoordinator.cancel("home") },
                        size = 48.dp,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary
                    )
                }
                BouncyIconButton(
                    VoiceIcon.COPY,
                    onCopy,
                    size = 48.dp,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun CleanupButton(
    ownerId: String,
    text: String,
    onReplacement: (String) -> Unit,
    container: Color,
    content: Color,
    available: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cleanup by CleanupCoordinator.state.collectAsState()
    val owned = cleanup.ownerId == ownerId
    val enabled = available && text.isNotBlank() && (!cleanup.busy || owned)
    LaunchedEffect(cleanup.phase, cleanup.message, cleanup.ownerId) {
        if (owned && cleanup.phase == CleanupPhase.ERROR && !cleanup.message.isNullOrBlank()) {
            Toast.makeText(context, cleanup.message, Toast.LENGTH_LONG).show()
        }
    }
    when {
        owned && cleanup.phase in setOf(CleanupPhase.TRANSCRIBING, CleanupPhase.REWRITING) -> {
            Surface(
                modifier = modifier.size(48.dp),
                shape = CircleShape,
                color = container.copy(alpha = .78f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(21.dp),
                        color = content,
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
        owned && cleanup.phase == CleanupPhase.RECORDING -> {
            BouncyIconButton(
                VoiceIcon.CHECK,
                { CleanupCoordinator.finish(ownerId) },
                modifier = modifier,
                size = 48.dp,
                container = RecordRed,
                content = Color.White
            )
        }
        owned && cleanup.phase == CleanupPhase.SUCCESS -> {
            BouncyIconButton(
                VoiceIcon.CHECK,
                {},
                modifier = modifier,
                size = 48.dp,
                container = container,
                content = content,
                enabled = false
            )
        }
        else -> {
            BouncyIconButton(
                VoiceIcon.CLEANUP,
                {
                    val started = CleanupCoordinator.start(context, ownerId, text, onReplacement)
                    if (!started) {
                        CleanupCoordinator.state.value.message?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = modifier,
                size = 48.dp,
                container = container,
                content = content,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    onRetranscribe: (String) -> Unit,
    onImportAudio: (List<android.net.Uri>) -> Unit,
    requestedEntryId: String?,
    modifier: Modifier = Modifier
) {
    val entries by AppContainer.history.entries.collectAsState()
    val settings by AppContainer.settings.settings.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<TranscriptionEntry?>(null) }
    var longRetryCandidate by remember { mutableStateOf<TranscriptionEntry?>(null) }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImportAudio(uris)
    }
    LaunchedEffect(requestedEntryId, entries) {
        if (requestedEntryId != null) {
            selectedId = requestedEntryId
            if (entries.any { it.id == requestedEntryId }) HistoryNavigationController.consume(requestedEntryId)
        }
    }
    val visibleEntries = remember(entries, searchQuery) {
        NoteSearch.filter(entries, searchQuery)
    }
    val archiveBytes by produceState<Long?>(initialValue = null, entries) {
        val snapshot = entries
        value = withContext(Dispatchers.IO) {
            snapshot.sumOf { entry ->
                entry.audioPath
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                    ?.length()
                    ?: 0L
            }
        }
    }
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }
    LaunchedEffect(searchQuery) {
        if (visibleEntries.isNotEmpty()) historyListState.scrollToItem(0)
    }
    val current = selectedId?.let { id -> entries.firstOrNull { it.id == id } }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    BackHandler(enabled = selectedId == null && searchActive) {
        searchQuery = ""
        searchActive = false
        focusManager.clearFocus()
    }
    if (current != null) {
        HistoryEditor(
            current,
            onDismiss = { selectedId = null },
            onRetranscribe = { id ->
                val candidate = entries.firstOrNull { it.id == id }
                if (candidate != null && candidate.durationMs > settings.maxAutomaticUploadMinutes * 60_000L) {
                    longRetryCandidate = candidate
                } else onRetranscribe(id)
            },
            modifier = modifier
        )
    } else if (selectedId != null) {
        PendingImportDetail(onDismiss = { selectedId = null }, modifier = modifier)
    } else {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (searchActive) {
                    NotesSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        resultCount = visibleEntries.size,
                        totalCount = entries.size,
                        focusRequester = searchFocusRequester,
                        onFocusLost = {
                            if (searchQuery.isBlank()) searchActive = false
                        },
                        onClose = {
                            searchQuery = ""
                            searchActive = false
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Notes", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append("  ")
                            append(entries.size)
                            archiveBytes?.let {
                                append("  ·  ")
                                append(fileSize(it))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BouncyIconButton(
                        VoiceIcon.UPLOAD,
                        { importAudio.launch(arrayOf("audio/*")) },
                        size = 48.dp,
                        container = Color.Transparent
                    )
                    if (entries.isNotEmpty()) {
                        BouncyIconButton(
                            VoiceIcon.SEARCH,
                            { searchActive = true },
                            modifier = Modifier.semantics { contentDescription = "Search notes" },
                            size = 48.dp,
                            container = Color.Transparent
                        )
                        BouncyIconButton(
                            VoiceIcon.TRASH,
                            { confirmClear = true },
                            size = 48.dp,
                            container = Color.Transparent
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .14f))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (visibleEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No matching notes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = historyListState,
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(visibleEntries, key = { _, item -> item.id }) { _, entry ->
                        HistoryRow(
                            entry = entry,
                            onEdit = { selectedId = entry.id },
                            onCopy = { ClipboardFeedback.copy(context, entry.text) },
                            onRetranscribe = {
                                if (entry.durationMs > settings.maxAutomaticUploadMinutes * 60_000L) {
                                    longRetryCandidate = entry
                                } else {
                                    onRetranscribe(entry.id)
                                    selectedId = entry.id
                                }
                            },
                            onDeleteRequest = { deleteCandidate = entry }
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete the archive?") },
        text = { Text("All transcripts and their saved audio will be removed.") },
        confirmButton = { TextButton(onClick = { AppContainer.history.clear(); confirmClear = false }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete this note?") },
            text = { Text("Its transcript and saved audio will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    AppContainer.history.delete(candidate.id)
                    deleteCandidate = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }
        )
    }
    longRetryCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { longRetryCandidate = null },
            title = { Text("Upload long recording?") },
            text = {
                Text(
                    "This recording is ${duration(candidate.durationMs)} long and was kept locally. " +
                        "Uploading it can take longer and cost more."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    longRetryCandidate = null
                    selectedId = candidate.id
                    onRetranscribe(candidate.id)
                }) { Text("Upload and transcribe") }
            },
            dismissButton = { TextButton(onClick = { longRetryCandidate = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NotesSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int,
    focusRequester: FocusRequester,
    onFocusLost: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var hasReceivedFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.height(48.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = .18f)
        )
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineIcon(
                VoiceIcon.SEARCH,
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onSurfaceVariant
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (NoteSearch.shouldCollapseAfterFocusChange(
                                previouslyFocused = hasReceivedFocus,
                                currentlyFocused = state.isFocused,
                                query = value
                            )
                        ) {
                            onFocusLost()
                        }
                        if (state.isFocused) hasReceivedFocus = true
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                decorationBox = { field ->
                    if (value.isBlank()) {
                        Text(
                            "Search $totalCount notes",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        field()
                    }
                }
            )
            if (value.isNotBlank()) {
                Text(
                    resultCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BouncyIconButton(
                VoiceIcon.CLOSE,
                onClose,
                modifier = Modifier.semantics { contentDescription = "Close notes search" },
                size = 36.dp,
                container = Color.Transparent,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingImportDetail(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                LineIcon(VoiceIcon.CHEVRON, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f })
            }
            Text("Importing audio", style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Text("Transcribing…", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: TranscriptionEntry,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onRetranscribe: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val failed = entry.transcriptionFailed
    val hasAudio = entry.audioPath?.let { File(it).isFile } == true
    var menuExpanded by remember(entry.id) { mutableStateOf(false) }
    var menuOffset by remember(entry.id) { mutableStateOf(DpOffset.Zero) }
    var rowHeightPx by remember(entry.id) { mutableIntStateOf(0) }
    var editingTitle by remember(entry.id) { mutableStateOf(false) }
    var titleDraft by remember(entry.id) { mutableStateOf(entry.title) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .onSizeChanged { rowHeightPx = it.height }
                .pointerInput(entry.id) {
                    detectTapGestures(
                        onTap = { onEdit() },
                        onLongPress = { position ->
                            menuOffset = with(density) {
                                // DropdownMenu is positioned below its anchor. Cancel
                                // the row height so the popup follows the pressed finger.
                                DpOffset(position.x.toDp(), (position.y - rowHeightPx).toDp())
                            }
                            menuExpanded = true
                        }
                    )
                }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        entry.processing -> entry.progressLabel ?: "Transcribing…"
                        failed && hasAudio -> "Transcription failed · audio saved"
                        failed -> "Import failed"
                        else -> (if (entry.pinned) "PINNED · " else "") + entry.displayTitle
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAt))}  ·  ${duration(entry.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (entry.processing) {
                    LinearProgressIndicator(
                        progress = { entry.completedChunks.toFloat() / entry.chunkCount.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(2.dp),
                        color = Signal,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            LineIcon(VoiceIcon.CHEVRON, Modifier.padding(start = 14.dp).size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = menuOffset,
            modifier = Modifier.width(214.dp).padding(vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        ) {
            val menuPadding = PaddingValues(horizontal = 16.dp)
            DropdownMenuItem(text = { Text("Open", fontWeight = FontWeight.Medium) }, contentPadding = menuPadding, onClick = {
                menuExpanded = false
                onEdit()
            })
            DropdownMenuItem(
                text = { Text(if (entry.pinned) "Unpin" else "Pin") },
                contentPadding = menuPadding,
                onClick = {
                    menuExpanded = false
                    AppContainer.history.updateMetadata(entry.id, entry.title, !entry.pinned)
                }
            )
            DropdownMenuItem(
                text = { Text("Note title") },
                contentPadding = menuPadding,
                enabled = !entry.processing,
                onClick = {
                    menuExpanded = false
                    titleDraft = entry.title
                    editingTitle = true
                }
            )
            DropdownMenuItem(
                text = { Text("Copy") },
                contentPadding = menuPadding,
                enabled = entry.text.isNotBlank() && !failed && !entry.processing,
                onClick = {
                    menuExpanded = false
                    onCopy()
                }
            )
            DropdownMenuItem(
                text = { Text("Transcribe again") },
                contentPadding = menuPadding,
                enabled = hasAudio && !entry.processing,
                onClick = {
                    menuExpanded = false
                    onRetranscribe()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                contentPadding = menuPadding,
                enabled = !entry.processing,
                onClick = {
                    menuExpanded = false
                    onDeleteRequest()
                }
            )
        }
    }
    if (editingTitle) {
        AlertDialog(
            onDismissRequest = { editingTitle = false },
            title = { Text("Note title") },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(entry.displayTitle) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppContainer.history.updateMetadata(entry.id, titleDraft, entry.pinned)
                    editingTitle = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingTitle = false }) { Text("Cancel") } }
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
}

@Composable
private fun HistoryEditor(
    entry: TranscriptionEntry,
    onDismiss: () -> Unit,
    onRetranscribe: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val recordingState by RecordingController.state.collectAsState()
    val cleanupState by CleanupCoordinator.state.collectAsState()
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var wasProcessing by remember(entry.id) { mutableStateOf(entry.processing) }
    var showTranscribed by remember(entry.id) { mutableStateOf(false) }
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    val hasAudio = entry.audioPath?.let { File(it).isFile } == true
    LaunchedEffect(entry.text, entry.failureMessage) { text = entry.text }
    LaunchedEffect(entry.processing, entry.failureMessage) {
        if (wasProcessing && !entry.processing && !entry.transcriptionFailed) {
            showTranscribed = true
            delay(1_600)
            showTranscribed = false
        }
        wasProcessing = entry.processing
    }
    LaunchedEffect(text) {
        delay(700)
        if (!entry.processing && !entry.transcriptionFailed && AppContainer.history.entries.value.firstOrNull { it.id == entry.id }?.text != text) {
            AppContainer.history.updateText(entry.id, text)
        }
    }
    val exportMime = when (entry.audioFormat) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }
    val exportAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(exportMime)) { uri ->
        if (uri != null && !entry.audioPath.isNullOrBlank()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> File(entry.audioPath).inputStream().use { it.copyTo(output) } }
            }.onSuccess { Toast.makeText(context, "Audio saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Could not save audio", Toast.LENGTH_SHORT).show() }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(VoiceIcon.CHEVRON, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f }, MaterialTheme.colorScheme.onSurface)
            }
            Text(
                buildString {
                    entry.sourceName?.takeIf { it.isNotBlank() }?.let { append(it.take(34)); append("  ·  ") }
                    append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAt)))
                    append("  ·  ")
                    append(duration(entry.durationMs))
                },
                Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(showTranscribed) {
            Text(
                "Transcribed",
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Signal
            )
        }
        Surface(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (entry.processing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Text(entry.progressLabel ?: "Transcribing…", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
                        if (entry.chunkCount > 1) Text(
                            "${entry.completedChunks} / ${entry.chunkCount} parts",
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { entry.completedChunks.toFloat() / entry.chunkCount.coerceAtLeast(1) },
                            modifier = Modifier.width(180.dp).padding(top = 12.dp).height(3.dp),
                            color = Signal,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            "The imported audio is already in Notes.",
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (entry.transcriptionFailed) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(if (hasAudio) "Audio saved" else "Import failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        entry.failureMessage.orEmpty(),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (hasAudio) "Use the re-transcribe button below to try the current primary and fallback models."
                        else "Share the file again or choose it with the Notes upload button.",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    entry.fallbackMessage?.let { reason ->
                        Text(
                            "Fallback used · primary failed: $reason",
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
                    }
                    BasicTextField(
                        text,
                        { text = it },
                        Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                        enabled = !(cleanupState.ownerId == "history:${entry.id}" && cleanupState.busy),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 28.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CleanupButton(
                            ownerId = "history:${entry.id}",
                            text = text,
                            onReplacement = { replacement ->
                                text = replacement
                                AppContainer.history.updateText(entry.id, replacement)
                            },
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary
                        )
                        if (cleanupState.ownerId == "history:${entry.id}" && cleanupState.busy) {
                            BouncyIconButton(
                                VoiceIcon.CLOSE,
                                { CleanupCoordinator.cancel("history:${entry.id}") },
                                size = 48.dp,
                                container = MaterialTheme.colorScheme.primary,
                                content = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        BouncyIconButton(
                            VoiceIcon.COPY,
                            { copy(context, text) },
                            size = 48.dp,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            enabled = text.isNotBlank()
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val metadata = buildList {
            if (hasAudio) add(entry.audioFormat.uppercase())
            if (entry.inputBytes > 0L) add(fileSize(entry.inputBytes))
            if (entry.chunkCount > 1) add("${entry.chunkCount} parts")
            // What the pre-upload pass took out. The recording still plays at
            // its full length, so without this the shorter billed time has no
            // visible explanation.
            entry.silenceRemovedMs?.takeIf { it > 0L }?.let { add("${shortDuration(it)} silence cut") }
            // Transcription and cleanup are separate purchases from separate
            // providers, so the line shows the sum and then names the part of
            // it that was not the transcription.
            val total = listOfNotNull(entry.costUsd, entry.cleanupCostUsd).takeIf { it.isNotEmpty() }?.sum()
            total?.let { add(cost(it) + if (entry.costEstimated) " estimated" else "") }
            entry.cleanupCostUsd?.let { add("cleanup ${cost(it)}") }
        }
        if (metadata.isNotEmpty()) Text(
            metadata.joinToString("  ·  "),
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AudioScrubber(entry.audioPath, entry.id.hashCode(), Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        val isThisProcessing = entry.processing || recordingState.phase == RecordingPhase.PROCESSING && recordingState.historyId == entry.id
        val isThisError = recordingState.phase == RecordingPhase.ERROR && recordingState.historyId == entry.id
        if (isThisError && !entry.transcriptionFailed) {
            Text(
                recordingState.errorMessage.orEmpty(),
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (isThisProcessing) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
            } else {
                BouncyIconButton(
                    VoiceIcon.REFRESH,
                    { onRetranscribe(entry.id) },
                    size = 48.dp,
                    container = Color.Transparent,
                    enabled = !entry.audioPath.isNullOrBlank() && File(entry.audioPath).exists() &&
                        recordingState.phase !in setOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED, RecordingPhase.PROCESSING)
                )
            }
            BouncyIconButton(
                VoiceIcon.DOWNLOAD,
                { exportAudio.launch("voice-note-${entry.createdAt}.${entry.audioFormat}") },
                size = 48.dp,
                container = Color.Transparent,
                enabled = !entry.audioPath.isNullOrBlank() && File(entry.audioPath).exists()
            )
            BouncyIconButton(
                VoiceIcon.TRASH,
                { confirmDelete = true },
                size = 48.dp,
                container = Color.Transparent,
                enabled = !entry.processing
            )
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this note?") },
        text = { Text("Its transcript and saved audio will be removed.") },
        confirmButton = {
            TextButton(onClick = {
                AppContainer.history.delete(entry.id)
                confirmDelete = false
                onDismiss()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val settings by AppContainer.settings.settings.collectAsState()
    val models by AppContainer.models.models.collectAsState()
    val cleanupModels by AppContainer.models.cleanupModels.collectAsState()
    val appModels = remember(models) { BrowserRenderingPolicy.appModels(models) }
    val loading by AppContainer.models.loading.collectAsState()
    val catalogError by AppContainer.models.error.collectAsState()
    val selectedModel = appModels.firstOrNull { it.id == settings.selectedModel }
    val selectedCleanupModel = cleanupModels.firstOrNull { it.id == settings.cleanupModel }
    val context = LocalContext.current
    val inputMethodManager = remember {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    fun isVoiceKeyboardEnabled() = inputMethodManager.enabledInputMethodList.any {
        it.packageName == context.packageName &&
            it.serviceName == VoiceInputMethodService::class.java.name
    }
    var voiceKeyboardEnabled by remember { mutableStateOf(isVoiceKeyboardEnabled()) }
    val openKeyboardSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        voiceKeyboardEnabled = isVoiceKeyboardEnabled()
    }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(AppContainer.secrets.readApiKey().isNotBlank()) }
    var elevenKeySaved by remember { mutableStateOf(AppContainer.secrets.readApiKey(ApiKeyProvider.ELEVENLABS).isNotBlank()) }
    var assemblyKeySaved by remember { mutableStateOf(AppContainer.secrets.readApiKey(ApiKeyProvider.ASSEMBLYAI).isNotBlank()) }
    val providerAvailability = remember(keySaved, elevenKeySaved, assemblyKeySaved) {
        mapOf(
            TranscriptionProvider.OPENROUTER_STT to keySaved,
            TranscriptionProvider.OPENROUTER_MULTIMODAL to keySaved,
            TranscriptionProvider.OPENROUTER_TEXT to keySaved,
            TranscriptionProvider.ELEVENLABS to elevenKeySaved,
            TranscriptionProvider.ASSEMBLYAI to assemblyKeySaved
        )
    }
    var editingKeyProvider by remember { mutableStateOf<ApiKeyProvider?>(null) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var editingMultimodalPrompt by remember { mutableStateOf(false) }
    var multimodalControlsExpanded by rememberSaveable { mutableStateOf(false) }
    var multimodalPromptDraft by rememberSaveable { mutableStateOf(settings.multimodalPrompt) }
    var editingCleanupPrompt by remember { mutableStateOf(false) }
    var editingBaseCleanupPrompt by remember { mutableStateOf(false) }
    var usageBreakdownExpanded by rememberSaveable { mutableStateOf(false) }
    var baseCleanupPromptDraft by rememberSaveable { mutableStateOf(settings.baseCleanupPrompt()) }
    var cleanupControlsExpanded by rememberSaveable { mutableStateOf(false) }
    var cleanupPromptDraft by rememberSaveable { mutableStateOf(settings.cleanupPrompt) }
    var cleanupMinimumWordsDraft by rememberSaveable {
        mutableStateOf(settings.cleanupMinimumInstructionWords.toString())
    }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val backupScope = rememberCoroutineScope()
    LaunchedEffect(settings.cleanupMinimumInstructionWords) {
        cleanupMinimumWordsDraft = settings.cleanupMinimumInstructionWords.toString()
    }
    LaunchedEffect(models, appModels, settings.selectedModel) {
        val selectedCatalogModel = models.firstOrNull { it.id == settings.selectedModel }
        if (selectedCatalogModel != null &&
            selectedCatalogModel !in appModels &&
            appModels.isNotEmpty()
        ) {
            val replacement = appModels.firstOrNull {
                it.id != settings.fallbackModel && providerAvailability[it.provider] == true
            } ?: appModels.firstOrNull { it.id != settings.fallbackModel }
                ?: appModels.first()
            updateSettings { it.copy(selectedModel = replacement.id) }
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backupScope.launch {
            runCatching { withContext(Dispatchers.IO) { AppContainer.backup.export(uri) } }
                .onSuccess { Toast.makeText(context, "$it notes backed up", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Backup failed", Toast.LENGTH_LONG).show() }
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch {
            runCatching { withContext(Dispatchers.IO) { AppContainer.backup.restore(uri) } }
                .onSuccess { Toast.makeText(context, "Restored ${it.restoredNotes} notes and ${it.restoredAudioFiles} audio files", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Restore failed", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(selectedModel?.id) {
        if (selectedModel != null && !ModelLanguageCatalog.supports(selectedModel.id, settings.language)) {
            updateSettings { it.copy(language = "auto") }
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        item {
            SettingGroup("API KEYS") {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clickable { editingKeyProvider = ApiKeyProvider.OPENROUTER },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OpenRouter", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(if (keySaved) "••••••••••••" else "Not set", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LineIcon(VoiceIcon.CHEVRON, Modifier.padding(start = 8.dp).size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingDivider()
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clickable { editingKeyProvider = ApiKeyProvider.ELEVENLABS },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ElevenLabs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(if (elevenKeySaved) "••••••••••••" else "Not set", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LineIcon(VoiceIcon.CHEVRON, Modifier.padding(start = 8.dp).size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingDivider()
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clickable { editingKeyProvider = ApiKeyProvider.ASSEMBLYAI },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AssemblyAI", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(if (assemblyKeySaved) "••••••••••••" else "Not set", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LineIcon(VoiceIcon.CHEVRON, Modifier.padding(start = 8.dp).size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SettingGroup("TRANSCRIPTION") {
                ModelPicker(appModels, settings.selectedModel, providerAvailability) { selectedModel ->
                    updateSettings { current ->
                        current.copy(
                            selectedModel = selectedModel,
                            fallbackModel = if (current.fallbackModel == selectedModel) {
                                appModels.firstOrNull {
                                    it.id != selectedModel && providerAvailability[it.provider] == true
                                }?.id ?: appModels.firstOrNull { it.id != selectedModel }?.id
                                ?: current.fallbackModel
                            } else current.fallbackModel
                        )
                    }
                }
                if (selectedModel?.provider == TranscriptionProvider.OPENROUTER_MULTIMODAL) {
                    SettingDivider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clickable { multimodalControlsExpanded = !multimodalControlsExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Multimodal controls", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (selectedModel.reasoningMandatory) {
                                    "Reasoning required by this model · hidden from transcript"
                                } else {
                                    "Prompt and reasoning"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LineIcon(
                            VoiceIcon.CHEVRON,
                            Modifier.size(18.dp).graphicsLayer { rotationZ = if (multimodalControlsExpanded) 90f else 0f },
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(multimodalControlsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (supportsMultimodalReasoning(selectedModel)) {
                                val reasoningOptions = multimodalReasoningOptions(selectedModel)
                                CompactChoicePicker(
                                    "Reasoning",
                                    multimodalReasoningLabel(
                                        settings.multimodalReasoningEffort,
                                        selectedModel
                                    ),
                                    reasoningOptions
                                ) { effort ->
                                    updateSettings { it.copy(multimodalReasoningEffort = effort) }
                                }
                            } else {
                                InfoRow("Reasoning", "Not supported by this model; no reasoning parameter is sent.")
                            }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                    multimodalPromptDraft = settings.multimodalPrompt
                                    editingMultimodalPrompt = true
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Transcription prompt", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Edit the instructions sent with the audio.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LineIcon(VoiceIcon.CHEVRON, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                catalogError?.let { Text("Offline list in use: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live OpenRouter catalog", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BouncyIconButton(VoiceIcon.REFRESH, { AppContainer.models.refresh(force = true) }, size = 48.dp, enabled = !loading)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .15f))
                LanguagePicker(settings.language, selectedModel) { value -> updateSettings { it.copy(language = value) } }
                Text(
                    "Universal OpenRouter STT request · provider-only fields stay off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SettingGroup("CLEANUP") {
                Text(
                    "Speak an edit instruction; the complete current text is replaced only after the model returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModelPicker(cleanupModels, settings.cleanupModel, providerAvailability) { model ->
                    updateSettings { it.copy(cleanupModel = model) }
                }
                SettingDivider()
                Text(
                    "Automatic cleanup runs on every finished transcription before you see it, " +
                        "in the app and in the keyboard. It uses the same model as the spoken edit above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Cleanup runs on OpenRouter text models. Without that key it
                // cannot run at all, so the effective mode — not the stored
                // preference — is what this section reports. The preference
                // itself is left alone and returns as soon as a key is added.
                val effectiveCleanupMode = ModelAvailabilityPolicy.effectiveBaseCleanupMode(
                    settings.baseCleanupMode,
                    if (keySaved) setOf(TranscriptionProvider.OPENROUTER_STT) else emptySet()
                )
                CompactChoicePicker(
                    "Automatic cleanup",
                    effectiveCleanupMode.label,
                    BaseCleanupMode.entries.map { it.stored to it.label }
                ) { stored ->
                    updateSettings { it.copy(baseCleanupMode = BaseCleanupMode.fromStored(stored)) }
                }
                Text(
                    if (!keySaved) {
                        "Off — cleanup needs an OpenRouter API key. Transcription keeps working; " +
                            "the raw transcript is inserted unchanged. Your choice returns once a key is added."
                    } else {
                        effectiveCleanupMode.description
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (effectiveCleanupMode != BaseCleanupMode.OFF) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            baseCleanupPromptDraft = settings.baseCleanupPrompt()
                            editingBaseCleanupPrompt = true
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Edit ${settings.baseCleanupMode.label} system prompt",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (settings.baseCleanupMode.stored in settings.baseCleanupPrompts) {
                                    "Edited. Only the selected mode's prompt is shown."
                                } else {
                                    "Follows the shipped default. Only the selected mode's prompt is shown."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LineIcon(VoiceIcon.CHEVRON, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clickable { cleanupControlsExpanded = !cleanupControlsExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Cleanup controls", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "System prompt and reasoning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LineIcon(
                        VoiceIcon.CHEVRON,
                        Modifier.size(18.dp).graphicsLayer { rotationZ = if (cleanupControlsExpanded) 90f else 0f },
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(cleanupControlsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cleanupMinimumWordsDraft,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(2)
                                cleanupMinimumWordsDraft = digits
                                digits.toIntOrNull()?.coerceIn(0, 50)?.let { minimum ->
                                    if (minimum.toString() != digits) {
                                        cleanupMinimumWordsDraft = minimum.toString()
                                    }
                                    updateSettings {
                                        it.copy(cleanupMinimumInstructionWords = minimum)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Minimum instruction words") },
                            supportingText = {
                                Text(
                                    "Shorter instructions are discarded before the edit-model call. " +
                                        "Punctuation does not count; 0 disables the filter."
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (selectedCleanupModel != null && supportsMultimodalReasoning(selectedCleanupModel)) {
                            CompactChoicePicker(
                                "Reasoning · spoken edit",
                                multimodalReasoningLabel(settings.cleanupReasoningEffort, selectedCleanupModel),
                                multimodalReasoningOptions(selectedCleanupModel)
                            ) { effort ->
                                updateSettings { it.copy(cleanupReasoningEffort = effort) }
                            }
                            // The automatic pass runs on every recording, so its
                            // effort is worth setting far lower than a rare edit's.
                            CompactChoicePicker(
                                "Reasoning · automatic cleanup",
                                multimodalReasoningLabel(settings.baseCleanupReasoningEffort, selectedCleanupModel),
                                multimodalReasoningOptions(selectedCleanupModel)
                            ) { effort ->
                                updateSettings { it.copy(baseCleanupReasoningEffort = effort) }
                            }
                        } else {
                            InfoRow("Reasoning", "Not supported by this model; no reasoning parameter is sent.")
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                cleanupPromptDraft = settings.cleanupPrompt
                                editingCleanupPrompt = true
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Cleanup system prompt", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Edit the exact instruction that constrains every replacement.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LineIcon(VoiceIcon.CHEVRON, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            SettingGroup("USAGE") {
                val totals by AppContainer.usage.totals.collectAsState()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Transcribed", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${"%.1f".format(Locale.ROOT, totals.minutes)} min · ${totals.requests} requests",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${if (totals.estimated) "~" else ""}US$ ${"%.3f".format(Locale.ROOT, totals.costUsd)}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (totals.estimated) {
                            Text(
                                "partly estimated",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                totals.cleanup?.let { cleanup ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Cleanup",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${cleanup.requests} passes  ·  US$ ${"%.3f".format(Locale.ROOT, cleanup.costUsd)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (totals.perProvider.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable { usageBreakdownExpanded = !usageBreakdownExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Per API key",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LineIcon(
                            VoiceIcon.CHEVRON,
                            Modifier.size(16.dp).graphicsLayer {
                                rotationZ = if (usageBreakdownExpanded) 90f else 0f
                            },
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(usageBreakdownExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            totals.transcription.forEach { entry ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.provider.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${"%.1f".format(Locale.ROOT, entry.minutes)} min · ${entry.requests} requests",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        "${if (entry.estimated) "~" else ""}US$ ${"%.3f".format(Locale.ROOT, entry.costUsd)}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Text(
                                "Only OpenRouter reports a real price per request. ElevenLabs and AssemblyAI " +
                                    "bill per audio second without returning a figure, so their totals are " +
                                    "derived from the catalog rate and marked with ~.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { AppContainer.usage.reset() }) { Text("Reset counters") }
                        }
                    }
                }
            }
        }
        item {
            SettingGroup("LOOK") {
                ThemePicker(settings.themeMode) { mode ->
                    updateSettings { it.copy(themeMode = mode) }
                    TranscriptionWidgetProvider.updateAll(context)
                }
            }
        }
        item {
            SettingGroup("BEHAVIOR") {
                SettingSwitch("Copy automatically", "Put finished text on the clipboard.", settings.autoCopy) { updateSettings { s -> s.copy(autoCopy = it) } }
                SettingDivider()
                SettingSwitch("Quick-record notification", "Keep Record within reach.", settings.persistentReadyNotification) { enabled ->
                    updateSettings { it.copy(persistentReadyNotification = enabled) }
                    if (enabled) NotificationHelper.showReady(context) else NotificationHelper.cancelReady(context)
                }
                SettingDivider()
                SettingSwitch("Wi-Fi only uploads", "Keep the audio and retry later on mobile data.", settings.wifiOnly) { updateSettings { s -> s.copy(wifiOnly = it) } }
                SettingDivider()
                run {
                    var screenshotsAllowed by remember {
                        mutableStateOf(ScreenshotReader.hasPermission(context))
                    }
                    val photoPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { screenshotsAllowed = it }
                    SettingSwitch(
                        "Screenshots in the keyboard clipboard",
                        "Screenshots are files rather than clipboard entries, so the keyboard reads the " +
                            "newest ones from your photo library. An input method may not ask for this " +
                            "itself, which is why the switch lives here.",
                        screenshotsAllowed
                    ) { enabled ->
                        if (enabled) {
                            photoPermission.launch(ScreenshotReader.permission)
                        } else {
                            // Only Android can revoke a granted permission.
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                    SettingDivider()
                }
                SettingSwitch(
                    "Trim silence before upload",
                    "Normalizes quiet microphones and cuts the pauses out of the upload copy. " +
                        "Providers bill wall-clock audio, so this is cheaper and uploads less — " +
                        "at the cost of one short encode before each request. The archived recording keeps its pauses. " +
                        "Live models cannot be trimmed afterwards, so they pause the stream instead: " +
                        "sending stops after 700 ms of silence and resumes on the next word.",
                    settings.trimSilenceBeforeUpload
                ) { updateSettings { s -> s.copy(trimSilenceBeforeUpload = it) } }
                SettingDivider()
                SettingSwitch("Touch feedback", "A small physical click on recorder controls.", settings.haptics) { updateSettings { s -> s.copy(haptics = it) } }
                SettingDivider()
                InfoRow("Audio archive", "Successful recordings are always saved privately with their transcript.")
            }
        }
        item {
            SettingGroup("VOICE KEYBOARD") {
                Text(
                    if (voiceKeyboardEnabled) "Transcription voice input is enabled." else "Enable it once in Android, then select it as your keyboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            openKeyboardSettings.launch(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (voiceKeyboardEnabled) "Manage" else "Enable") }
                    Button(
                        onClick = { inputMethodManager.showInputMethodPicker() },
                        enabled = voiceKeyboardEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("Switch") }
                }
                Text(
                    "Android requires this manual approval. The app cannot silently replace Gboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SettingGroup("DATA") {
                Text("Portable backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keeps notes, audio and settings in one ZIP. API keys are never included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportBackup.launch("transcription-backup.zip") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Back up") }
                    OutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.weight(1f)) { Text("Restore") }
                }
                Text(
                    "Android cloud backup is requested after every change; the ZIP is the reliable way to survive a true uninstall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SettingGroup("ADVANCED") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { advancedExpanded = !advancedExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Fallback & audio capture", style = MaterialTheme.typography.titleMedium)
                        Text("Used automatically; defaults are suitable for voice notes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LineIcon(
                        VoiceIcon.CHEVRON,
                        Modifier.size(18.dp).graphicsLayer { rotationZ = if (advancedExpanded) 90f else 0f },
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(advancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingDivider()
                        Text("Fallback model", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "Called automatically when the primary model returns an error.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ModelPicker(
                            appModels.filterNot { it.id == settings.selectedModel },
                            settings.fallbackModel,
                            providerAvailability
                        ) { fallback ->
                            updateSettings { it.copy(fallbackModel = fallback) }
                        }
                        SettingDivider()
                        Text("Audio sent to providers", style = MaterialTheme.typography.labelMedium)
                        CompactChoicePicker(
                            "Wire format",
                            settings.sendAudioFormat.label,
                            SendAudioFormat.entries.map { it.wireName to it.label }
                        ) { value ->
                            updateSettings { it.copy(sendAudioFormat = SendAudioFormat.fromStored(value)) }
                        }
                        Text(
                            when (settings.sendAudioFormat) {
                                SendAudioFormat.AUTO ->
                                    "Normal recordings upload immediately as M4A. Only MAI-Transcribe 1.5 waits for a temporary MP3 conversion."
                                SendAudioFormat.M4A ->
                                    "No conversion delay. The unchanged M4A archive is uploaded directly."
                                SendAudioFormat.MP3 ->
                                    "Adds a short on-device conversion before upload. The original M4A archive stays unchanged."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingDivider()
                        CompactChoicePicker(
                            "Skip automatic upload after",
                            "${settings.maxAutomaticUploadMinutes} min",
                            listOf(5, 10, 20, 30, 60).map { it.toString() to "$it min" }
                        ) { value -> updateSettings { it.copy(maxAutomaticUploadMinutes = value.toInt()) } }
                        CompactChoicePicker(
                            "Fallback timeout",
                            "${settings.providerTimeoutSeconds} sec",
                            listOf(10, 15, 20, 30, 60, 120).map { it.toString() to "$it sec" }
                        ) { value -> updateSettings { it.copy(providerTimeoutSeconds = value.toInt()) } }
                        SettingSwitch(
                            "Cancel on abandon",
                            "Cancel the active network request instead of only hiding its late result.",
                            settings.cancelRequestOnAbandon
                        ) { enabled -> updateSettings { it.copy(cancelRequestOnAbandon = enabled) } }
                        SettingDivider()
                        SettingSwitch(
                            "Save failed recordings",
                            "Keep failed audio in Notes so it can be downloaded or transcribed again.",
                            settings.saveFailedAudio
                        ) { enabled -> updateSettings { it.copy(saveFailedAudio = enabled) } }
                        SettingDivider()
                        Text("Saved audio quality", style = MaterialTheme.typography.labelMedium)
                        CompactChoicePicker(
                            "Quality preset",
                            AudioCaptureOptions.matchingPreset(
                                settings.audioSampleRateHz,
                                settings.audioBitRateBps,
                                settings.audioChannels
                            )?.label ?: "Custom",
                            AudioCaptureOptions.presets.map { it.id to it.label }
                        ) { id ->
                            AudioCaptureOptions.preset(id)?.let { preset ->
                                updateSettings {
                                    it.copy(
                                        audioSampleRateHz = preset.sampleRateHz,
                                        audioBitRateBps = preset.bitRateBps,
                                        audioChannels = preset.channels
                                    )
                                }
                            }
                        }
                        CompactChoicePicker(
                            "Sample rate",
                            "${formatKhz(settings.audioSampleRateHz)} kHz",
                            AudioCaptureOptions.sampleRates.map { it.toString() to "${formatKhz(it)} kHz" }
                        ) { value -> updateSettings { it.copy(audioSampleRateHz = value.toInt()) } }
                        CompactChoicePicker(
                            "AAC bitrate",
                            "${settings.audioBitRateBps / 1_000} kbps",
                            AudioCaptureOptions.bitRates.map { it.toString() to "${it / 1_000} kbps" }
                        ) { value -> updateSettings { it.copy(audioBitRateBps = value.toInt()) } }
                        CompactChoicePicker(
                            "Channels",
                            if (settings.audioChannels == 1) "Mono" else "Stereo",
                            listOf("1" to "Mono", "2" to "Stereo")
                        ) { value -> updateSettings { it.copy(audioChannels = value.toInt()) } }
                        Text(
                            "AAC in M4A · about ${"%.1f".format(Locale.US, settings.audioBitRateBps * 0.00045)} MB per hour. This unchanged original is the archive. Changes apply to new recordings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingDivider()
                        SettingSwitch(
                            "Keep debug send copies",
                            "Retain temporary converted files for compatibility debugging.",
                            settings.keepDebugSendCopies
                        ) { enabled -> updateSettings { it.copy(keepDebugSendCopies = enabled) } }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset settings") }
        }
        item { Text("Batch prices are shown per audio hour; multimodal models show token pricing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    editingKeyProvider?.let { provider ->
        val providerKeySaved = when (provider) {
            ApiKeyProvider.OPENROUTER -> keySaved
            ApiKeyProvider.ELEVENLABS -> elevenKeySaved
            ApiKeyProvider.ASSEMBLYAI -> assemblyKeySaved
        }
        AlertDialog(
        onDismissRequest = { editingKeyProvider = null },
        title = { Text("${provider.label} API key") },
        text = {
            OutlinedTextField(
                keyDraft, { keyDraft = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(provider.placeholder) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = keyDraft.isNotBlank(),
                onClick = {
                    AppContainer.secrets.saveApiKey(provider, keyDraft)
                    when (provider) {
                        ApiKeyProvider.OPENROUTER -> keySaved = true
                        ApiKeyProvider.ELEVENLABS -> elevenKeySaved = true
                        ApiKeyProvider.ASSEMBLYAI -> assemblyKeySaved = true
                    }
                    keyDraft = ""
                    editingKeyProvider = null
                    // A new key can make a better model reachable, so the stored
                    // pick is re-resolved here rather than only at request time.
                    AppContainer.persistResolvedTranscriptionModel()
                    // OpenRouter and ElevenLabs expose live catalogs; refreshing
                    // for every provider is cheap and keeps the shared picker in sync.
                    AppContainer.models.refresh(force = true)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (providerKeySaved) TextButton(onClick = {
                    AppContainer.secrets.saveApiKey(provider, "")
                    when (provider) {
                        ApiKeyProvider.OPENROUTER -> keySaved = false
                        ApiKeyProvider.ELEVENLABS -> elevenKeySaved = false
                        ApiKeyProvider.ASSEMBLYAI -> assemblyKeySaved = false
                    }
                    editingKeyProvider = null
                    // Removing a key can strand the stored model on a provider
                    // this install can no longer reach.
                    AppContainer.persistResolvedTranscriptionModel()
                }) { Text("Remove") }
                TextButton(onClick = { editingKeyProvider = null }) { Text("Cancel") }
            }
        }
    )
    }
    if (editingMultimodalPrompt) AlertDialog(
        onDismissRequest = { editingMultimodalPrompt = false },
        title = {
            Column {
                Text("Audio model prompt", fontWeight = FontWeight.Bold)
                Text(
                    "OpenRouter multimodal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            OutlinedTextField(
                value = multimodalPromptDraft,
                onValueChange = { multimodalPromptDraft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                supportingText = { Text("Sent only to OpenRouter multimodal audio models.") },
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(onClick = {
                updateSettings {
                    it.copy(multimodalPrompt = multimodalPromptDraft.trim().ifBlank { DEFAULT_MULTIMODAL_PROMPT })
                }
                editingMultimodalPrompt = false
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { multimodalPromptDraft = DEFAULT_MULTIMODAL_PROMPT }) { Text("Default") }
                TextButton(onClick = { editingMultimodalPrompt = false }) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
    if (editingCleanupPrompt) AlertDialog(
        onDismissRequest = { editingCleanupPrompt = false },
        title = {
            Column {
                Text("Cleanup system prompt", fontWeight = FontWeight.Bold)
                Text(
                    "Controls every spoken edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            OutlinedTextField(
                value = cleanupPromptDraft,
                onValueChange = { cleanupPromptDraft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                supportingText = { Text("The original text and spoken instruction are added separately.") },
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(onClick = {
                updateSettings {
                    it.copy(cleanupPrompt = cleanupPromptDraft.trim().ifBlank { DEFAULT_CLEANUP_PROMPT })
                }
                editingCleanupPrompt = false
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { cleanupPromptDraft = DEFAULT_CLEANUP_PROMPT }) { Text("Default") }
                TextButton(onClick = { editingCleanupPrompt = false }) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
    // Deliberately one dialog for every mode: only the selected mode's prompt is
    // ever on screen, so there is never a choice of which prompt is being edited.
    if (editingBaseCleanupPrompt) AlertDialog(
        onDismissRequest = { editingBaseCleanupPrompt = false },
        title = {
            Column {
                Text("${settings.baseCleanupMode.label} system prompt", fontWeight = FontWeight.Bold)
                Text(
                    "Runs automatically on every transcription",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            OutlinedTextField(
                value = baseCleanupPromptDraft,
                onValueChange = { baseCleanupPromptDraft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                supportingText = { Text("The transcript is sent as the user message; this is the whole instruction.") },
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(onClick = {
                val mode = settings.baseCleanupMode
                updateSettings { it.withBaseCleanupPrompt(mode, baseCleanupPromptDraft) }
                editingBaseCleanupPrompt = false
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    baseCleanupPromptDraft = settings.baseCleanupMode.defaultPrompt
                }) { Text("Default") }
                TextButton(onClick = { editingBaseCleanupPrompt = false }) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset settings?") },
        text = { Text("Models and behavior return to reasonable defaults. API keys, notes, and audio are kept.") },
        confirmButton = {
            TextButton(onClick = {
                AppContainer.settings.resetToReasonableDefaults()
                multimodalPromptDraft = DEFAULT_MULTIMODAL_PROMPT
                cleanupPromptDraft = DEFAULT_CLEANUP_PROMPT
                confirmReset = false
                TranscriptionWidgetProvider.updateAll(context)
                NotificationHelper.showReady(context)
            }) { Text("Reset") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore backup?") },
        text = { Text("Notes are merged. Matching note IDs and settings are replaced; API keys stay unchanged.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRestore = false
                restoreBackup.launch(arrayOf("application/zip", "application/octet-stream"))
            }) { Text("Choose backup") }
        },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } }
    )
}

@Composable
private fun SettingGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<TranscriptionModel>,
    selectedId: String,
    providerAvailability: Map<TranscriptionProvider, Boolean>,
    onSelect: (String) -> Unit
) {
    val appModels = remember(models) { BrowserRenderingPolicy.appModels(models) }
    var expanded by remember { mutableStateOf(false) }
    val groupExpansionOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val visibleModelCounts = remember { mutableStateMapOf<String, Int>() }
    val menuScrollState = rememberScrollState()
    val groups = remember(appModels, providerAvailability) {
        buildModelPickerGroups(appModels)
            .sortedBy { group ->
                if (group.models.any { providerAvailability[it.provider] == true }) 0 else 1
            }
    }
    val selected = appModels.firstOrNull { it.id == selectedId }
    val selectedAvailable = selected?.let { providerAvailability[it.provider] == true } ?: true
    LaunchedEffect(
        expanded,
        menuScrollState.value,
        menuScrollState.maxValue
    ) {
        if (expanded &&
            menuScrollState.maxValue > 0 &&
            menuScrollState.value >= menuScrollState.maxValue - 48
        ) {
            groups.firstOrNull { group ->
                val groupExpanded = groupExpansionOverrides[group.id]
                    ?: !BrowserRenderingPolicy.modelGroupStartsCollapsed(group.models.size)
                groupExpanded && visibleModelCounts.getOrDefault(group.id, 0) < group.models.size
            }?.let { group ->
                visibleModelCounts[group.id] = BrowserRenderingPolicy.nextBatchEnd(
                    visibleModelCounts.getOrDefault(group.id, 0),
                    group.models.size
                )
            }
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) menuScrollState.scrollTo(0)
    }
    BoxWithConstraints {
        val menuWidth = maxWidth
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable {
                groupExpansionOverrides.clear()
                visibleModelCounts.clear()
                expanded = true
            },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).alpha(if (selectedAvailable) 1f else .5f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selected?.name?.substringAfter(": ") ?: selectedId.substringAfter('/'),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            selected?.name?.substringBefore(":")?.uppercase() ?: selectedId.substringBefore('/').uppercase(),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        if (selectedAvailable) modelPrice(selected) else "API key missing · ${modelPrice(selected)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                LineIcon(VoiceIcon.CHEVRON, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                groupExpansionOverrides.clear()
                visibleModelCounts.clear()
            },
            modifier = Modifier.width(menuWidth).heightIn(max = 390.dp),
            scrollState = menuScrollState,
            shape = RoundedCornerShape(10.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        ) {
            groups.forEach { group ->
                val groupAvailable = group.models.any { providerAvailability[it.provider] == true }
                val groupExpanded = groupExpansionOverrides[group.id]
                    ?: !BrowserRenderingPolicy.modelGroupStartsCollapsed(group.models.size)
                DropdownMenuItem(
                    modifier = Modifier.alpha(if (groupAvailable) 1f else .45f),
                    text = {
                        Text(
                            buildString {
                                append(group.label)
                                append(" · ")
                                append(group.models.size)
                                if (!groupAvailable) append(" · API key missing")
                            }
                        )
                    },
                    trailingIcon = {
                        LineIcon(
                            VoiceIcon.CHEVRON,
                            Modifier.size(18.dp).graphicsLayer {
                                rotationZ = if (groupExpanded) 90f else 0f
                            },
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        if (!groupExpanded &&
                            BrowserRenderingPolicy.modelGroupStartsCollapsed(group.models.size)
                        ) {
                            groups.filter {
                                it.id != group.id &&
                                    BrowserRenderingPolicy.modelGroupStartsCollapsed(it.models.size)
                            }.forEach {
                                groupExpansionOverrides[it.id] = false
                                visibleModelCounts.remove(it.id)
                            }
                        }
                        groupExpansionOverrides[group.id] = !groupExpanded
                        if (groupExpanded) {
                            visibleModelCounts.remove(group.id)
                        } else {
                            visibleModelCounts[group.id] =
                                BrowserRenderingPolicy.nextBatchEnd(0, group.models.size)
                        }
                    }
                )
                if (groupExpanded) {
                    val sortedModels = group.models.sortedWith(
                        compareByDescending<TranscriptionModel> {
                            providerAvailability[it.provider] == true
                        }.thenByDescending(TranscriptionModel::createdAt)
                    )
                    val visibleCount = visibleModelCounts[group.id]
                        ?: BrowserRenderingPolicy.nextBatchEnd(0, sortedModels.size)
                    sortedModels.take(visibleCount).forEach { model ->
                        ModelMenuItem(
                            model = model,
                            selectedId = selectedId,
                            enabled = providerAvailability[model.provider] == true
                        ) {
                            onSelect(model.id)
                            expanded = false
                            groupExpansionOverrides.clear()
                            visibleModelCounts.clear()
                        }
                    }
                }
            }
        }
    }
}

private data class ModelPickerGroup(
    val id: String,
    val label: String,
    val models: List<TranscriptionModel>
)

private fun buildModelPickerGroups(models: List<TranscriptionModel>): List<ModelPickerGroup> = buildList {
    listOf(
        TranscriptionProvider.OPENROUTER_TEXT,
        TranscriptionProvider.OPENROUTER_STT,
        TranscriptionProvider.ELEVENLABS,
        TranscriptionProvider.ASSEMBLYAI,
        TranscriptionProvider.OPENROUTER_MULTIMODAL
    ).forEach { provider ->
        val providerModels = models.filter { it.provider == provider }
        if (providerModels.isNotEmpty()) {
            add(ModelPickerGroup(provider.name, provider.label, providerModels))
        }
    }
}

@Composable
private fun ModelMenuItem(
    model: TranscriptionModel,
    selectedId: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val chosen = model.id == selectedId
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .alpha(if (enabled) 1f else .38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name.substringAfter(": "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    model.name.substringBefore(":").uppercase(),
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                modelPrice(model),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (chosen) {
            LineIcon(
                VoiceIcon.CHECK,
                Modifier.padding(start = 10.dp).size(19.dp),
                MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CompactChoicePicker(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .clickable { expanded = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(selectedLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            LineIcon(VoiceIcon.CHEVRON, Modifier.padding(start = 8.dp).size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = { onSelect(option.first); expanded = false }
                )
            }
        }
    }
}

private data class LanguageOption(val code: String, val label: String)

@Composable
private fun LanguagePicker(value: String, model: TranscriptionModel?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val normalizedValue = LanguageCode.normalize(value)
    val deviceCode = Locale.getDefault().language.lowercase().takeIf { it.length == 2 } ?: "en"
    val displayLocale = Locale.getDefault()
    val supportedCodes = ModelLanguageCatalog.supportedCodes(model?.id.orEmpty())
    val standard = remember(displayLocale) {
        Locale.getISOLanguages().map { code ->
            val languageLocale = Locale(code)
            val nativeName = languageLocale.getDisplayLanguage(languageLocale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(languageLocale) else it.toString() }
            LanguageOption(code, nativeName.ifBlank { languageLocale.getDisplayLanguage(displayLocale) })
        }.distinctBy { it.code }.sortedBy { it.label.lowercase(displayLocale) }
    }
    // The unfiltered catalog is the complete ISO 639-1 list. Rebuilding and
    // re-sorting it on every recomposition was most of the cost of opening the
    // menu, so the finished option list is keyed to what can actually change it.
    val options = remember(standard, supportedCodes, deviceCode) {
        val modelLanguages =
            if (supportedCodes == null) standard else standard.filter { it.code in supportedCodes }
        buildList {
            add(LanguageOption("auto", "Auto detect"))
            modelLanguages.firstOrNull { it.code == deviceCode }?.let(::add)
            modelLanguages.filterNot { it.code == deviceCode }.forEach(::add)
        }
    }
    val selected = options.firstOrNull { it.code == normalizedValue }
    val provider = model?.provider?.label
    val menuScrollState = rememberScrollState()
    // Rendering ~180 menu items in one pass is what made opening this picker
    // stutter. The list grows by one batch whenever the menu is scrolled near
    // its end, using the same thresholds as the model browsers.
    // Seeded with the first batch rather than zero: the menu is composed in the
    // same frame it is opened, and starting empty would flash a blank surface.
    var visibleCount by remember(options) {
        mutableIntStateOf(BrowserRenderingPolicy.nextBatchEnd(0, options.size))
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            visibleCount = BrowserRenderingPolicy.nextBatchEnd(0, options.size)
            menuScrollState.scrollTo(0)
        }
    }
    LaunchedEffect(expanded, menuScrollState.value, menuScrollState.maxValue) {
        if (expanded &&
            menuScrollState.maxValue > 0 &&
            menuScrollState.value >= menuScrollState.maxValue - 48 &&
            visibleCount < options.size
        ) {
            visibleCount = BrowserRenderingPolicy.nextBatchEnd(visibleCount, options.size)
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text("Language", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints {
            val menuWidth = maxWidth
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable { expanded = true },
                color = Color.Transparent
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected?.let { "${it.label}  ·  ${it.code}" } ?: "Auto detect  ·  auto",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (supportedCodes == null) {
                                "${provider ?: "Provider"} · ISO 639-1 language code"
                            } else {
                                "${provider ?: "Provider"} · ${supportedCodes.size} supported languages"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LineIcon(VoiceIcon.CHEVRON, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth).heightIn(max = 390.dp),
                scrollState = menuScrollState,
                shape = RoundedCornerShape(10.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .18f))
            ) {
                options.take(visibleCount).forEach { option ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 50.dp),
                        text = {
                            Row(Modifier.fillMaxWidth()) {
                                Text(option.label, Modifier.weight(1f), fontWeight = if (option.code == normalizedValue) FontWeight.Bold else FontWeight.Normal)
                                Text(option.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        trailingIcon = { if (option.code == normalizedValue) LineIcon(VoiceIcon.CHECK, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurface) },
                        onClick = { onSelect(option.code); expanded = false }
                    )
                }
                if (visibleCount < options.size) {
                    Text(
                        "${options.size - visibleCount} more · scroll to load",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactTextSetting(label: String, value: String, onValue: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            decorationBox = { field -> if (value.isBlank()) Text("Not set", color = MaterialTheme.colorScheme.onSurfaceVariant) else field() }
        )
    }
}

@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        ThemeMode.entries.forEach { mode ->
            val chosen = selected == mode
            Surface(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(mode) },
                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) { Text(mode.name.lowercase().replaceFirstChar { it.titlecase() }, Modifier.padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked, onChecked)
    }
}

@Composable private fun SettingDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))

@Composable
private fun InfoRow(title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(start = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun updateSettings(transform: (AppSettings) -> AppSettings) = AppContainer.settings.update(transform)
private fun copy(context: Context, text: String) {
    ClipboardFeedback.copy(context, text)
}
private fun duration(ms: Long): String { val seconds = ms / 1000; return "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60) }
/** Short form for the metadata line, where a leading "00:" is just noise. */
private fun shortDuration(ms: Long): String {
    val seconds = ((ms + 500L) / 1000L).coerceAtLeast(1L)
    return if (seconds < 60L) "${seconds}s" else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
private fun fileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
private fun cost(value: Double): String = if (value < .0001) "< US$0.0001" else "US$ %.4f".format(Locale.US, value)
private fun price(value: Double?): String = value?.let { "US$ %.3f / hour".format(Locale.US, it) } ?: "Price unavailable"
private fun modelPrice(model: TranscriptionModel?): String = when {
    model == null -> "Price unavailable"
    model.pricePerHourUsd != null -> price(model.pricePerHourUsd)
    model.priceNote.isNotBlank() -> model.priceNote
    else -> "Usage priced"
}
private fun multimodalReasoningOptions(model: TranscriptionModel): List<Pair<String, String>> {
    val efforts = model.reasoningEfforts.ifEmpty {
        listOf("minimal", "low", "medium", "high")
    }
    return buildList {
        add(
            "auto" to if (model.reasoningMandatory) {
                "Model default · required"
            } else {
                "Automatic · off when possible"
            }
        )
        if (!model.reasoningMandatory) add("none" to "Off")
        efforts.distinct().forEach { effort ->
            add(effort to effort.replaceFirstChar { it.titlecase(Locale.ROOT) })
        }
    }
}

private fun supportsMultimodalReasoning(model: TranscriptionModel) =
    model.reasoningMandatory ||
        model.reasoningEfforts.isNotEmpty() ||
        "reasoning" in model.supportedParameters

private fun multimodalReasoningLabel(value: String, model: TranscriptionModel): String {
    val normalized = value.lowercase()
    return multimodalReasoningOptions(model).firstOrNull { it.first == normalized }?.second
        ?: if (model.reasoningMandatory) "Model default · required" else "Automatic · off when possible"
}
private fun formatKhz(value: Int): String = if (value % 1_000 == 0) (value / 1_000).toString() else "%.2f".format(Locale.US, value / 1_000.0)
