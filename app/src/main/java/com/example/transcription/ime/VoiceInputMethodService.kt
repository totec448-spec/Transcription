package com.example.transcription.ime

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.example.transcription.AppContainer
import com.example.transcription.data.ApiKeyProvider
import com.example.transcription.data.BaseCleanupMode
import com.example.transcription.data.BrowserRenderingPolicy
import com.example.transcription.data.ClipboardHistory
import com.example.transcription.data.ClipboardItem
import com.example.transcription.data.ModelAvailabilityPolicy
import com.example.transcription.data.ScreenshotReader
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.RecordingState
import com.example.transcription.data.ThemeMode
import com.example.transcription.data.TranscriptionEntry
import com.example.transcription.data.TranscriptionProvider
import com.example.transcription.recording.BaseTextProcessor
import com.example.transcription.recording.LiveRecordingCoordinator
import com.example.transcription.recording.CleanupCoordinator
import com.example.transcription.recording.CleanupPhase
import com.example.transcription.recording.CleanupState
import com.example.transcription.recording.RecordingController
import com.example.transcription.recording.RecordingService
import com.example.transcription.widget.TranscriptionWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Compact voice-only IME with one shared batch/live recording surface.
 *
 * The model browser deliberately lives inside the IME instead of using Android's
 * PopupMenu. This keeps it scrollable, theme-correct and visually consistent on
 * vendor skins while retaining the small, familiar Gboard-sized footprint.
 */
class VoiceInputMethodService : InputMethodService() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var modelControl: LinearLayout
    private lateinit var modelName: TextView
    private lateinit var modelProvider: TextView
    private lateinit var historyButton: FrameLayout
    private lateinit var clipboardButton: FrameLayout
    private lateinit var keyboardButton: FrameLayout
    private lateinit var clipboardPanel: LinearLayout
    private lateinit var clipboardScroll: ScrollView
    private lateinit var clipboardList: LinearLayout
    private lateinit var recorderStage: FrameLayout
    private lateinit var modelBrowser: ScrollView
    private lateinit var modelList: LinearLayout
    private lateinit var historyPanel: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var historyList: LinearLayout
    private lateinit var overlayClose: FrameLayout
    private lateinit var status: TextView
    private lateinit var mic: AmplitudeMicView
    private lateinit var pause: TextView
    private lateinit var deleteKey: RepeatingDeleteKeyView
    private lateinit var spaceKey: SpaceKeyView
    private var punctuationStrip: PunctuationStripView? = null
    private lateinit var cleanupKey: TextView
    private lateinit var cleanupHalo: View
    private lateinit var enterKey: EnterKeyView
    private lateinit var abandon: TextView
    private lateinit var undoBar: LinearLayout
    private lateinit var undoKey: FrameLayout
    private lateinit var redoKey: FrameLayout
    private val editHistory = FieldEditHistory()
    private var historyFieldToken = ""
    private var typingSample: Runnable? = null

    private var palette = ImePalette.light()
    private var options: List<ModelOption> = emptyList()
    private var selectedModelId = ""
    private var streaming: StreamingVoiceSession? = null
    private var composingFinal = ""
    private var latestPartial = ""
    private var lastFinal = ""
    private var streamingStartedAt = 0L
    private var streamingModelId = ""
    private var streamingHistorySaved = false
    private var liveGatePaused = false
    private var lastLiveWidgetUpdateMs = 0L
    private var liveFinalize: Runnable? = null
    private var batchRequestId: String? = null
    private var enterBehavior = ImeInteractionPolicy.enterBehavior(0, 0)
    private var batchWatch: Job? = null
    private var lastStatusText: String? = null
    private var uiMode = UiMode.IDLE
    private var openPanel = ImePanel.NONE
    private var editorConnection: InputConnection? = null
    private var historyEntries: List<TranscriptionEntry> = emptyList()
    private var historyRenderedCount = 0
    private var historyNeedsRebuild = true
    private var historyPageLoadArmed = true
    private var modelPageLoadArmed = true
    private var pendingHistoryPageLoad: Runnable? = null
    private var pendingModelPageLoad: Runnable? = null
    private val modelGroupExpansionOverrides = mutableMapOf<String, Boolean>()
    private val modelGroupRows = linkedMapOf<String, LinearLayout>()
    private val modelRenderedCounts = mutableMapOf<String, Int>()
    private var overlayBackDispatcher: OnBackInvokedDispatcher? = null
    private var overlayBackCallback: OnBackInvokedCallback? = null
    private val historyDateTimeFormat by lazy {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    private val modelBrowserOpen: Boolean
        get() = openPanel == ImePanel.MODELS

    /**
     * Mirrors an in-flight batch request onto the strip.
     *
     * This was a `postDelayed` loop running every 120 ms for as long as the
     * request lasted. Eight wakeups a second is the right order of magnitude
     * for a level meter and the wrong one for the minute or more the keyboard
     * then spends waiting on a transcription, where the state does not change
     * at all: the loop was re-reading and re-rendering an unchanged phase a few
     * thousand times per note. `RecordingController.state` is a `StateFlow` and
     * already emits on every amplitude sample, so collecting it is both cheaper
     * when nothing happens and more responsive when something does.
     */
    private fun watchBatchState() {
        batchWatch?.cancel()
        batchWatch = serviceScope.launch {
            RecordingController.state.collect(::applyBatchState)
        }
    }

    private fun stopWatchingBatchState() {
        batchWatch?.cancel()
        batchWatch = null
    }

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
        AppContainer.models.refresh()
        serviceScope.launch {
            AppContainer.models.models.collect {
                if (::root.isInitialized && uiMode == UiMode.IDLE) {
                    refreshModelOptions()
                }
            }
        }
        serviceScope.launch {
            AppContainer.history.entries.collect {
                historyNeedsRebuild = true
                if (::root.isInitialized && openPanel == ImePanel.HISTORY) {
                    prepareHistoryBrowser()
                }
            }
        }
        serviceScope.launch {
            CleanupCoordinator.state.collect {
                if (::root.isInitialized) updateCleanupUi(it)
            }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (AppContainer.settings.settings.value.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }
        palette = if (dark) ImePalette.dark() else ImePalette.light()
        window?.window?.navigationBarColor = palette.background
        val keyboardHeight = keyboardContentHeightPx()

        root = FixedHeightLinearLayout(this, keyboardHeight).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(palette.background)
            minimumHeight = keyboardHeight
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight)
        }
        toolbar = buildToolbar()
        root.addView(toolbar)
        root.addView(buildContent())

        options = buildModelOptions()
        selectedModelId = resolveImeModel()
        updateModelControl()
        rebuildModelBrowser()
        showIdle()
        updateEnterKey(currentInputEditorInfo)
        updateCleanupUi(CleanupCoordinator.state.value)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorConnection = currentInputConnection
        updateEnterKey(info)
        resetHistoryForEditor(info)
        // Android only lets an IME read the clipboard while it is the focused
        // input method, so every activation is one of the few chances to see
        // what was copied elsewhere. One read, no listener, no polling.
        AppContainer.clipboard.capture(getSystemService(ClipboardManager::class.java))
        if (::root.isInitialized && streaming == null &&
            RecordingController.state.value.phase !in ACTIVE_PHASES &&
            !CleanupCoordinator.isOwnerActive(CLEANUP_OWNER)
        ) {
            options = buildModelOptions()
            if (options.none { it.id == selectedModelId && it.keyAvailable }) {
                selectedModelId = resolveImeModel()
            }
            updateModelControl()
            rebuildModelBrowser()
            showIdle()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopWatchingBatchState()
        cancelTypingSample()
        if (uiMode != UiMode.IDLE || batchRequestId != null) abandon()
        if (::root.isInitialized) closeOverlay()
        editorConnection = null
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        if (CleanupCoordinator.isOwnerActive(CLEANUP_OWNER)) {
            CleanupCoordinator.cancel(CLEANUP_OWNER)
        }
        if (streaming != null || RecordingController.state.value.isLive) {
            abandon()
        }
        liveFinalize?.let(handler::removeCallbacks)
        liveFinalize = null
        LiveRecordingCoordinator.detach()
        unregisterOverlayBackHandler()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && openPanel != ImePanel.NONE) {
            closeOverlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildToolbar(): LinearLayout {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        }

        modelControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedRipple(palette.surface, 16f, palette.ripple)
            isClickable = true
            isFocusable = false
            contentDescription = "Choose transcription model"
            setOnClickListener { toggleModelBrowser() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        }
        modelName = label("", 14f, palette.foreground, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        modelProvider = label("", 9f, palette.muted, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = .08f
        }
        modelControl.addView(modelName)
        modelControl.addView(modelProvider)
        toolbar.addView(modelControl)

        historyButton = FrameLayout(this).apply {
            background = roundedRipple(palette.surface, 16f, palette.ripple)
            isClickable = true
            isFocusable = false
            contentDescription = "Open Notes history"
            setOnClickListener { toggleHistoryBrowser() }
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                marginStart = dp(6)
            }
        }
        historyButton.addView(
            HistoryIconView(this).apply { iconColor = palette.foreground },
            FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER)
        )
        toolbar.addView(historyButton)

        clipboardButton = FrameLayout(this).apply {
            background = roundedRipple(palette.surface, 16f, palette.ripple)
            isClickable = true
            isFocusable = false
            contentDescription = "Open clipboard"
            setOnClickListener { toggleClipboardBrowser() }
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                marginStart = dp(6)
            }
        }
        clipboardButton.addView(
            ClipboardIconView(this).apply { iconColor = palette.foreground },
            FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER)
        )
        toolbar.addView(clipboardButton)

        keyboardButton = FrameLayout(this).apply {
            background = roundedRipple(palette.surface, 16f, palette.ripple)
            isClickable = true
            isFocusable = false
            contentDescription = "Switch back to previous keyboard"
            setOnClickListener {
                if (uiMode != UiMode.IDLE) abandon()
                switchBackToKeyboard()
            }
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                marginStart = dp(6)
            }
        }
        keyboardButton.addView(
            KeyboardIconView(this).apply { iconColor = palette.foreground },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        )
        toolbar.addView(keyboardButton)
        return toolbar
    }

    /**
     * Undo and Redo sit as one centered pair directly below the microphone,
     * inside the recorder stage.
     *
     * The keyboard's own bottom edge belongs to Android: its globe and collapse
     * controls are drawn there, outside our view, and a full-width row of ours
     * visually collides with them. Centering under the microphone also keeps
     * both keys on the thumb's natural arc instead of at the far corners.
     */
    private fun buildUndoBar(): LinearLayout {
        undoKey = undoButton("Undo the last change", mirrored = false) { stepHistory(forward = false) }
        redoKey = undoButton("Redo the last undone change", mirrored = true) { stepHistory(forward = true) }
        // One pill split by a hairline rather than two separate lozenges: the
        // pair is a single control, and drawing it as one keeps the shape
        // legible next to the round microphone instead of adding two more.
        //
        // The pill's own background is transparent and exists only to give the
        // group an outline to clip against. The surface is painted by the two
        // halves, because a half that cannot act has to fade as a whole button
        // — a dimmed glyph still sitting on a bright, fully present surface
        // reads as a working key with a faint icon.
        //
        // The border it used to carry is gone. Nothing else in the keyboard is
        // outlined, so the one stroked shape in the layout announced itself as
        // a different kind of object than the keys around it.
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedFill(Color.TRANSPARENT, UNDO_BAR_RADIUS_DP)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(undoKey, LinearLayout.LayoutParams(dp(54), dp(38)))
            addView(buildUndoDivider(), LinearLayout.LayoutParams(dp(1), dp(38)))
            addView(redoKey, LinearLayout.LayoutParams(dp(54), dp(38)))
        }
    }

    /**
     * The seam between the halves: a short centered hairline, not a full-height
     * rule, over the same surface the halves paint so the pill stays one
     * continuous shape behind it.
     */
    private fun buildUndoDivider() = FrameLayout(this).apply {
        background = roundedFill(palette.surface, 0f)
        addView(
            View(this@VoiceInputMethodService).apply {
                setBackgroundColor(withAlpha(palette.foreground, 40))
            },
            FrameLayout.LayoutParams(dp(1), dp(18), Gravity.CENTER)
        )
    }

    private fun undoButton(description: String, mirrored: Boolean, onClick: () -> Unit) =
        FrameLayout(this).apply {
            // Square corners: the pill's outline clips the two outer ones, and
            // rounding the inner pair would notch the seam at the divider.
            background = litRipple(palette.surface, 0f, palette.ripple)
            isClickable = true
            isFocusable = false
            contentDescription = description
            setOnClickListener { onClick() }
            addView(
                UndoIconView(this@VoiceInputMethodService).apply {
                    iconColor = palette.foreground
                    this.mirrored = mirrored
                },
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
        }

    private fun buildContent(): View {
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        recorderStage = buildRecorderStage()
        content.addView(
            recorderStage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        modelList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(8))
        }
        modelBrowser = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                scheduleModelPageLoad(scrollY)
            }
            visibility = View.GONE
            background = roundedFill(palette.background, 16f)
            addView(
                modelList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(
            modelBrowser,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = browserSystemBarExclusionPx() }
        )

        historyPanel = buildHistoryPanel()
        content.addView(
            historyPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = browserSystemBarExclusionPx() }
        )

        clipboardPanel = buildClipboardPanel()
        content.addView(
            clipboardPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = browserSystemBarExclusionPx() }
        )

        overlayClose = FrameLayout(this).apply {
            visibility = View.GONE
            background = outlinedRoundedRipple(
                palette.surface,
                28f,
                palette.foreground,
                palette.ripple
            )
            isClickable = true
            isFocusable = false
            contentDescription = "Return to voice keyboard"
            setOnClickListener { closeOverlay() }
            addView(
                BackIconView(this@VoiceInputMethodService).apply {
                    iconColor = palette.foreground
                },
                FrameLayout.LayoutParams(dp(31), dp(31), Gravity.CENTER)
            )
        }
        content.addView(
            overlayClose,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(8)
                bottomMargin = dp(64)
            }
        )
        return content
    }

    private fun buildHistoryPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = roundedFill(palette.background, 16f)

        historyList = LinearLayout(this@VoiceInputMethodService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        historyScroll = ScrollView(this@VoiceInputMethodService).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                scheduleHistoryPageLoad(scrollY)
            }
            addView(
                historyList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(
            historyScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun buildClipboardPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = roundedFill(palette.background, 16f)

        clipboardList = LinearLayout(this@VoiceInputMethodService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        clipboardScroll = ScrollView(this@VoiceInputMethodService).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                clipboardList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(
            clipboardScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /**
     * Clipboard entries and screenshots in one list, newest first.
     *
     * The whole list is built at once rather than paged like Notes: it is
     * capped at twenty entries, so paging would add machinery for a list that
     * can never grow large.
     *
     * Screenshots and copies are one sequence, not two sections. Both carry the
     * time the thing actually happened — MediaStore's `DATE_ADDED` for a shot,
     * Android's own clip timestamp for a copy — so they interleave by when the
     * user made them. The cap counts them together, because "the last twenty
     * things I put somewhere" is the list being asked for; pins survive past it
     * exactly as they do in Notes.
     */
    private fun rebuildClipboardPanel() {
        if (!::clipboardList.isInitialized) return
        clipboardList.removeAllViews()
        AppContainer.clipboard.capture(getSystemService(ClipboardManager::class.java))
        val clips = AppContainer.clipboard.items.value
        // Asked for a full panel's worth rather than a token dozen: the cap
        // below is what decides the length, so a run of screenshots and no
        // copying should still be able to fill the list.
        val screenshots = ScreenshotReader.recent(this, ClipboardHistory.MAX_ITEMS)
            .filterNot { shot -> clips.any { it.id == shot.id } }
        val newestFirst = compareByDescending<ClipboardItem> { it.pinned }.thenByDescending { it.copiedAt }
        val combined = (clips + screenshots).sortedWith(newestFirst)
        val merged = (
            combined.filter(ClipboardItem::pinned) +
                combined.filterNot(ClipboardItem::pinned).take(ClipboardHistory.MAX_ITEMS)
            ).sortedWith(newestFirst)

        if (!ScreenshotReader.hasPermission(this)) {
            clipboardList.addView(
                label(
                    "Open Transcription once to allow photo access, then screenshots appear here too.",
                    11f,
                    palette.muted,
                    Typeface.NORMAL
                ).apply {
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    setLineSpacing(0f, 1.1f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        if (merged.isEmpty()) {
            clipboardList.addView(
                label("Nothing copied yet", 14f, palette.muted, Typeface.NORMAL).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(52), dp(16), dp(24))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        merged.forEach { item ->
            clipboardList.addView(clipboardRow(item))
            clipboardList.addView(
                View(this).apply { setBackgroundColor(withAlpha(palette.foreground, 28)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            )
        }
    }

    private fun clipboardRow(item: ClipboardItem): View {
        val titleValue = buildString {
            if (item.pinned) append("PINNED  ·  ")
            append(if (item.isImage) "Image  ·  ${item.text.take(40)}" else "Text")
        }
        var expanded = false

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = pressHighlight(palette.selectedSurface, 8f)
            isClickable = true
            isFocusable = false
            isLongClickable = true
            contentDescription = "Insert ${item.preview}. Long press to expand."

            addView(
                label(titleValue, 11f, if (item.pinned) palette.accent else palette.muted, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    letterSpacing = .06f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val preview = label(item.text.ifBlank { "Image" }, 13f, palette.foreground, Typeface.NORMAL).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(4), 0, 0)
            }
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                label(historyDateTimeFormat.format(Date(item.copiedAt)), 10f, palette.muted, Typeface.NORMAL).apply {
                    maxLines = 1
                    setPadding(0, dp(5), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            setOnLongClickListener {
                expanded = !expanded
                preview.maxLines = if (expanded) Int.MAX_VALUE else 2
                preview.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
                preview.requestLayout()
                true
            }
            setOnClickListener { insertClipboardItem(item) }
        }
    }

    /**
     * Images can only be inserted where the editor accepts them: `commitContent`
     * requires the target to declare a matching MIME type, and many fields
     * declare none. Saying so is better than a tap that silently does nothing.
     */
    private fun insertClipboardItem(item: ClipboardItem) {
        closeOverlay()
        if (!item.isImage) {
            recordedEdit { currentInputConnection?.commitText(item.text, 1) }
            return
        }
        val uri = runCatching { Uri.parse(item.imageUri) }.getOrNull() ?: return
        val accepted = currentInputEditorInfo?.contentMimeTypes.orEmpty()
        val mimeType = contentResolver.getType(uri) ?: "image/*"
        val supported = accepted.any { advertised ->
            ClipDescription.compareMimeTypes(mimeType, advertised)
        }
        if (!supported) {
            Toast.makeText(this, "This field does not accept images.", Toast.LENGTH_SHORT).show()
            return
        }
        val description = ClipDescription(item.text.ifBlank { "image" }, arrayOf(mimeType))
        val committed = runCatching {
            InputConnectionCompat.commitContent(
                currentInputConnection ?: return,
                currentInputEditorInfo ?: return,
                InputContentInfoCompat(uri, description, null),
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        }.getOrDefault(false)
        if (!committed) {
            Toast.makeText(this, "The image could not be inserted here.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildRecorderStage(): FrameLayout {
        val stageGesture = ReleaseInsideGesture()
        val stage = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Start or finish voice input"
            setOnClickListener { handleMicClick() }
            setOnTouchListener { view, event ->
                if (!::mic.isInitialized || !mic.isEnabled) {
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        stageGesture.down(event.isInside(view))
                        mic.isPressed = stageGesture.pressed
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        stageGesture.move(event.isInside(view))
                        mic.isPressed = stageGesture.pressed
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val trigger = stageGesture.release(event.isInside(view))
                        mic.isPressed = false
                        if (trigger) view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stageGesture.cancel()
                        mic.isPressed = false
                        true
                    }
                    else -> true
                }
            }
        }

        status = label("", 12f, palette.muted, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        stage.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(5) }
        )

        val centerControlSize = dp(184)
        // The screenshot-proven optical exclusion zone is wider than the blue
        // fill alone. Combined with the real device edge (no artificial inset),
        // this moves both outer columns about 14 dp away from the microphone.
        val centerButtonDiameter = dp(128)
        val outerControlSize = dp(68)
        val outerControlGap = dp(12)
        val outerColumnHeight = outerControlSize * 2 + outerControlGap
        val controls = BalancedRecorderControlsLayout(
            context = this,
            centerButtonDiameterPx = centerButtonDiameter,
            edgeInsetPx = 0
        )
        abandon = actionButton("×", "Abandon recording", 29f) { abandon() }
        spaceKey = SpaceKeyView(this).apply {
            iconColor = palette.foreground
            background = roundedRipple(palette.surface, 32f, palette.ripple)
            contentDescription = "Insert a space, hold for punctuation"
            onSpace = { currentInputConnection?.commitText(" ", 1) }
            onMenuOpen = ::showPunctuationMenu
            onMenuMove = { screenX, screenY ->
                punctuationStrip?.updateHighlight(screenX, screenY)
            }
            onMenuClose = ::hidePunctuationMenu
        }
        mic = AmplitudeMicView(this).apply {
            accentColor = palette.accent
            iconColor = palette.accentForeground
            contentDescription = "Start voice input"
            isClickable = true
            isFocusable = true
            // Sized to the optical exclusion zone, not to the painted circle:
            // feedback that stops exactly at the blue edge reads as too small.
            background = circularRipple(palette.ripple, centerControlSize, centerButtonDiameter)
            setOnClickListener { handleMicClick() }
            layoutParams = ViewGroup.LayoutParams(centerControlSize, centerControlSize)
        }
        pause = actionButton("Ⅱ", "Pause recording", 24f) { togglePause() }
        deleteKey = RepeatingDeleteKeyView(this).apply {
            iconColor = palette.foreground
            background = roundedRipple(palette.surface, 32f, palette.ripple)
            contentDescription = "Delete previous character"
            onDelete = ::deleteCharacter
            visibility = View.VISIBLE
        }
        cleanupKey = actionButton("✦", "Edit the complete text by voice", 25f) {
            handleCleanupClick()
        }
        enterKey = EnterKeyView(this).apply {
            iconColor = palette.foreground
            background = roundedRipple(palette.surface, 32f, palette.ripple)
            contentDescription = "Insert a new line"
            setOnClickListener { performEnterAction() }
        }
        val leftTopSlot = FrameLayout(this).apply {
            addView(
                spaceKey,
                FrameLayout.LayoutParams(outerControlSize, outerControlSize, Gravity.CENTER)
            )
            addView(
                abandon,
                FrameLayout.LayoutParams(outerControlSize, outerControlSize, Gravity.CENTER)
            )
        }
        val leftBottomSlot = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            cleanupHalo = View(this@VoiceInputMethodService).apply {
                background = roundedFill(Color.argb(128, 255, 255, 255), 32f)
                visibility = View.GONE
            }
            addView(
                cleanupHalo,
                FrameLayout.LayoutParams(outerControlSize, outerControlSize, Gravity.CENTER)
            )
            addView(
                cleanupKey,
                FrameLayout.LayoutParams(outerControlSize, outerControlSize, Gravity.CENTER)
            )
        }
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(outerControlSize, outerColumnHeight)
            addView(
                leftTopSlot,
                LinearLayout.LayoutParams(outerControlSize, outerControlSize)
            )
            addView(
                leftBottomSlot,
                LinearLayout.LayoutParams(outerControlSize, outerControlSize).apply {
                    topMargin = outerControlGap
                }
            )
        }
        val rightTopSlot = FrameLayout(this).apply {
            addView(
                pause,
                FrameLayout.LayoutParams(outerControlSize, outerControlSize, Gravity.CENTER)
            )
            addView(
                deleteKey,
                FrameLayout.LayoutParams(outerControlSize, outerControlSize, Gravity.CENTER)
            )
        }
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(outerControlSize, outerColumnHeight)
            addView(
                rightTopSlot,
                LinearLayout.LayoutParams(outerControlSize, outerControlSize)
            )
            addView(
                enterKey,
                LinearLayout.LayoutParams(outerControlSize, outerControlSize).apply {
                    topMargin = outerControlGap
                }
            )
        }
        controls.setControls(leftColumn, mic, rightColumn)
        // One column so the undo pair travels with the controls instead of
        // being positioned against the keyboard edge Android owns.
        val recorderColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(
                controls,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            undoBar = buildUndoBar()
            addView(
                undoBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            )
        }
        stage.addView(
            recorderColumn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                bottomMargin = dp(10) + navigationBarHeightPx() / 2
            }
        )

        punctuationStrip = PunctuationStripView(this).apply {
            visibility = View.GONE
            surfaceColor = palette.surface
            edgeColor = palette.foreground
            foregroundColor = palette.foreground
            accentColor = palette.accent
            accentForegroundColor = palette.accentForeground
        }
        stage.addView(
            punctuationStrip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        return stage
    }

    /**
     * The flyout is placed by hand instead of by gravity. It unfolds to the
     * right from the space key's own column, but is lifted into the empty band
     * above the controls: the 184 dp microphone reaches far past its painted
     * circle, so a menu on the key's own row would sit on top of it.
     */
    private fun showPunctuationMenu(touchScreenX: Float, touchScreenY: Float) {
        val strip = punctuationStrip ?: return
        if (uiMode != UiMode.IDLE || openPanel != ImePanel.NONE) return
        val stageWidth = recorderStage.width
        val stageHeight = recorderStage.height
        if (stageWidth <= 0 || stageHeight <= 0) return

        strip.measure(
            View.MeasureSpec.makeMeasureSpec(stageWidth - dp(8), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val stripWidth = strip.measuredWidth
        val stripHeight = strip.measuredHeight

        val anchor = IntArray(2)
        val stagePosition = IntArray(2)
        spaceKey.getLocationOnScreen(anchor)
        recorderStage.getLocationOnScreen(stagePosition)
        val anchorLeft = anchor[0] - stagePosition[0]
        val anchorTop = anchor[1] - stagePosition[1]

        val params = strip.layoutParams as FrameLayout.LayoutParams
        params.width = stripWidth
        params.height = stripHeight
        params.leftMargin = anchorLeft
            .coerceAtMost(stageWidth - dp(4) - stripWidth)
            .coerceAtLeast(dp(4))
        params.topMargin = (anchorTop - stripHeight - dp(8))
            .coerceAtMost((stageHeight - dp(4) - stripHeight).coerceAtLeast(0))
            .coerceAtLeast(0)
        strip.layoutParams = params
        strip.clearHighlight()

        // Grow out of the space key's top corner rather than appearing at once.
        strip.animate().cancel()
        strip.pivotX = 0f
        strip.pivotY = stripHeight.toFloat()
        strip.alpha = 0f
        strip.scaleX = .88f
        strip.scaleY = .88f
        strip.visibility = View.VISIBLE
        strip.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .start()

        // Arm the cell straight above the finger, so the hold alone already
        // offers the period and a still release inserts it.
        strip.post { strip.updateHighlight(touchScreenX, touchScreenY) }
    }

    private fun hidePunctuationMenu(commit: Boolean) {
        val strip = punctuationStrip ?: return
        if (strip.visibility != View.VISIBLE) return
        val symbol = if (commit) strip.highlightedSymbol else null
        strip.animate().cancel()
        strip.visibility = View.GONE
        strip.clearHighlight()
        if (symbol != null) currentInputConnection?.commitText(symbol, 1)
    }

    /**
     * The keyboard keeps its own model, separate from the app's: dictation into
     * a text field wants Scribe v2, while the app's Notes default is tuned for
     * longer recordings. A stored pick only survives while its key does.
     */
    private fun resolveImeModel(): String {
        val stored = getSharedPreferences(IME_PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            .orEmpty()
        options.firstOrNull { it.id == stored && it.keyAvailable }?.let { return it.id }
        options.firstOrNull { it.id == IME_DEFAULT_MODEL && it.keyAvailable }?.let { return it.id }
        // Only an explicitly stored pick may be a live model. Every automatic
        // choice below stays on batch: a realtime session that nobody asked for
        // behaves differently enough to feel like a different keyboard.
        val batchOptions = options.filterNot { ProviderModels.isStreaming(it.id) }
        return ModelAvailabilityPolicy.preferenceOrder
            .firstNotNullOfOrNull { (_, id) -> batchOptions.firstOrNull { it.id == id && it.keyAvailable } }
            ?.id
            ?: batchOptions.firstOrNull(ModelOption::keyAvailable)?.id
            ?: batchOptions.firstOrNull()?.id
            ?: options.firstOrNull()?.id.orEmpty()
    }

    private fun deleteCharacter() {
        val connection = currentInputConnection ?: return
        val selected = runCatching { connection.getSelectedText(0) }.getOrNull()
        val extracted = runCatching {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        val hasSelection = !selected.isNullOrEmpty() ||
            (extracted != null && extracted.selectionStart != extracted.selectionEnd)

        if (hasSelection) {
            // deleteSurroundingText() intentionally ignores selected text.
            // DEL follows editor semantics and removes the complete range.
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }

        if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun updateEnterKey(info: EditorInfo?) {
        enterBehavior = ImeInteractionPolicy.enterBehavior(
            inputType = info?.inputType ?: 0,
            imeOptions = info?.imeOptions ?: 0,
            actionId = info?.actionId ?: 0,
            hasCustomActionLabel = !info?.actionLabel.isNullOrBlank()
        )
        if (!::enterKey.isInitialized) return
        enterKey.icon = enterBehavior.icon
        enterKey.contentDescription = enterBehavior.description
    }

    private fun performEnterAction() {
        val connection = currentInputConnection ?: return
        val action = enterBehavior.editorAction
        if (action != null) {
            if (!connection.performEditorAction(action)) sendKeyChar('\n')
        } else if (!connection.commitText("\n", 1)) {
            sendKeyChar('\n')
        }
    }

    private fun handleMicClick() {
        when (uiMode) {
            UiMode.IDLE -> startSelectedModel()
            UiMode.RECORDING, UiMode.PAUSED -> finishRecording()
            UiMode.PROCESSING, UiMode.CLEANUP_RECORDING, UiMode.CLEANUP_PROCESSING -> Unit
        }
    }

    private fun handleCleanupClick() {
        val cleanup = CleanupCoordinator.state.value
        if (cleanup.ownerId == CLEANUP_OWNER && cleanup.phase == CleanupPhase.RECORDING) {
            CleanupCoordinator.finish(CLEANUP_OWNER)
            return
        }
        if (cleanup.busy) {
            Toast.makeText(this, "Another cleanup is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        val original = currentInputConnection
            ?.getExtractedText(fullTextRequest(), 0)
            ?.text
            ?.toString()
            .orEmpty()
        if (original.isBlank()) {
            Toast.makeText(this, "There is no text to edit.", Toast.LENGTH_SHORT).show()
            return
        }
        val started = CleanupCoordinator.start(this, CLEANUP_OWNER, original) { replacement ->
            recordedEdit { replaceEntireField(replacement) }
        }
        if (!started) {
            Toast.makeText(
                this,
                CleanupCoordinator.state.value.message ?: "Cleanup could not start.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun currentFieldText(): String =
        currentInputConnection
            ?.getExtractedText(fullTextRequest(), 0)
            ?.text
            ?.toString()
            .orEmpty()

    /**
     * Wraps one of our own operations in the pair of snapshots undo needs. The
     * field is read twice per operation and never per keystroke.
     */
    private fun recordedEdit(apply: () -> Unit) {
        editHistory.recordBefore(currentFieldText())
        apply()
        editHistory.recordAfter(currentFieldText())
        updateUndoBar()
    }

    private fun stepHistory(forward: Boolean) {
        // Typing since the last sample is itself a step. Without this, undo
        // right after a few keystrokes would jump over them to a stale sample,
        // or — with nothing sampled yet — do nothing at all.
        if (!forward) editHistory.captureUncommitted(currentFieldText())
        val target = if (forward) editHistory.redo() else editHistory.undo()
        if (target == null) {
            updateUndoBar()
            return
        }
        replaceEntireField(target)
        updateUndoBar()
    }

    /**
     * A snapshot only means anything for the field it was taken in, so the stack
     * is dropped whenever the editor changes. The token deliberately ignores the
     * connection object, which Android recreates for the same field.
     *
     * The field is sampled once on arrival, because the first undo needs a state
     * that predates whatever the user is about to type.
     */
    private fun resetHistoryForEditor(info: EditorInfo?) {
        val token = "${info?.packageName.orEmpty()}#${info?.fieldId ?: 0}#${info?.inputType ?: 0}"
        if (token == historyFieldToken) return
        historyFieldToken = token
        editHistory.clear()
        cancelTypingSample()
        editHistory.recordSample(currentFieldText())
        updateUndoBar()
    }

    /**
     * Text changed in the field, whether we caused it or the user typed it.
     *
     * This is the cheap signal: it carries no text and costs nothing, so it only
     * arms a timer. The expensive part — one `getExtractedText` binder call — is
     * paid at most once per sampling interval no matter how fast someone types,
     * which is what keeps continuous typing off the undo hot path.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (typingSample != null || streaming != null) return
        val task = Runnable {
            typingSample = null
            if (uiMode != UiMode.IDLE) return@Runnable
            editHistory.recordSample(currentFieldText())
            updateUndoBar()
        }
        typingSample = task
        handler.postDelayed(task, TYPING_SAMPLE_MS)
    }

    private fun cancelTypingSample() {
        typingSample?.let(handler::removeCallbacks)
        typingSample = null
    }

    /**
     * The pair is hidden rather than dimmed while recording: the stage is busy
     * and two dead keys under the microphone are visual noise. It reappears with
     * the idle controls.
     */
    private fun updateUndoBar() {
        if (!::undoKey.isInitialized) return
        val idle = uiMode == UiMode.IDLE
        undoBar.visibility = if (idle) View.VISIBLE else View.INVISIBLE
        undoKey.isEnabled = idle && editHistory.canUndo
        redoKey.isEnabled = idle && editHistory.canRedo
        // Applied to the half, not to its glyph. Each half now paints its own
        // surface, so this fades the whole button — which is the only way one
        // dead side of a joined pair can look dead rather than merely faint.
        undoKey.alpha = if (undoKey.isEnabled) 1f else DISABLED_KEY_ALPHA
        redoKey.alpha = if (redoKey.isEnabled) 1f else DISABLED_KEY_ALPHA
    }

    private fun replaceEntireField(replacement: String) {
        val connection = currentInputConnection ?: return
        connection.finishComposingText()
        val extracted = connection.getExtractedText(fullTextRequest(), 0)
        val length = extracted?.text?.length ?: 0
        val selected = length > 0 && connection.setSelection(0, length)
        if (!selected) connection.performContextMenuAction(android.R.id.selectAll)
        connection.commitText(replacement, 1)
    }

    private fun fullTextRequest() = ExtractedTextRequest().apply {
        hintMaxChars = Int.MAX_VALUE
        hintMaxLines = Int.MAX_VALUE
    }

    private fun updateCleanupUi(cleanup: CleanupState) {
        if (!::cleanupKey.isInitialized) return
        if (cleanup.ownerId != CLEANUP_OWNER) {
            cleanupKey.isEnabled = !cleanup.busy
            cleanupKey.alpha = if (cleanup.busy) .35f else 1f
            return
        }
        when (cleanup.phase) {
            CleanupPhase.RECORDING -> {
                closeOverlay()
                uiMode = UiMode.CLEANUP_RECORDING
                modelControl.isEnabled = false
                modelControl.alpha = .62f
                historyButton.isEnabled = false
                historyButton.alpha = .62f
                mic.isEnabled = false
                mic.active = false
                status.visibility = View.VISIBLE
                setStatusText("EDIT  ·  ${clock(cleanup.elapsedMs)}")
                spaceKey.visibility = View.GONE
                abandon.visibility = View.VISIBLE
                pause.visibility = View.GONE
                deleteKey.visibility = View.VISIBLE
                cleanupKey.visibility = View.VISIBLE
                enterKey.visibility = View.INVISIBLE
                cleanupKey.isEnabled = true
                cleanupKey.alpha = 1f
                cleanupKey.text = "✓"
                cleanupKey.setTextColor(palette.background)
                cleanupKey.background = roundedRipple(palette.foreground, 32f, palette.ripple)
                // Linear in the normalized level, like every other meter.
                val pulse = cleanup.amplitude.coerceIn(0f, 1f)
                cleanupHalo.visibility = View.VISIBLE
                cleanupHalo.scaleX = 1f + pulse * .22f
                cleanupHalo.scaleY = 1f + pulse * .22f
                cleanupHalo.alpha = .16f + pulse * .34f
            }
            CleanupPhase.TRANSCRIBING, CleanupPhase.REWRITING -> {
                uiMode = UiMode.CLEANUP_PROCESSING
                modelControl.isEnabled = false
                modelControl.alpha = .62f
                historyButton.isEnabled = false
                historyButton.alpha = .62f
                status.visibility = View.VISIBLE
                setStatusText(cleanup.message ?: "Cleaning up…")
                spaceKey.visibility = View.GONE
                abandon.visibility = View.VISIBLE
                pause.visibility = View.GONE
                deleteKey.visibility = View.VISIBLE
                cleanupKey.visibility = View.VISIBLE
                enterKey.visibility = View.INVISIBLE
                cleanupKey.isEnabled = false
                cleanupKey.alpha = .62f
                cleanupKey.text = "…"
                cleanupHalo.visibility = View.GONE
            }
            CleanupPhase.ERROR -> {
                cleanup.message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                showIdle()
            }
            CleanupPhase.SUCCESS, CleanupPhase.IDLE -> showIdle()
        }
    }

    private fun startSelectedModel() {
        if (selectedModelId.isBlank()) {
            toggleModelBrowser(forceOpen = true)
            return
        }
        val selectedOption = options.firstOrNull { it.id == selectedModelId }
        if (selectedOption?.keyAvailable == false) {
            Toast.makeText(
                this,
                "Add the ${selectedOption.provider.label} API key to use this model.",
                Toast.LENGTH_LONG
            ).show()
            toggleModelBrowser(forceOpen = true)
            return
        }
        if (RecordingController.state.value.phase in ACTIVE_PHASES ||
            CleanupCoordinator.state.value.busy
        ) {
            Toast.makeText(this, "Another recording is already active.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Open Transcription once and grant microphone access.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        if (isLiveModel()) {
            startStreaming()
        } else {
            val requestId = UUID.randomUUID().toString()
            batchRequestId = requestId
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_MODEL_OVERRIDE, selectedModelId)
                    .putExtra(RecordingService.EXTRA_REQUEST_ID, requestId)
                    .putExtra(RecordingService.EXTRA_SUPPRESS_AUTO_COPY, true)
            )
            showRecording()
            watchBatchState()
        }
    }

    private fun startStreaming() {
        composingFinal = ""
        latestPartial = ""
        lastFinal = ""
        streamingStartedAt = SystemClock.elapsedRealtime()
        streamingModelId = selectedModelId
        streamingHistorySaved = false
        liveGatePaused = false
        lastLiveWidgetUpdateMs = 0L
        val archive = File(filesDir, "latest/live-recording.wav").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val session = StreamingVoiceSession(
            context = this,
            modelId = selectedModelId,
            secrets = AppContainer.secrets,
            profiles = AppContainer.micProfiles,
            archiveFile = archive,
            gateSilence = AppContainer.settings.settings.value.trimSilenceBeforeUpload,
            onPartial = { updateComposing(it) },
            onFinal = { value ->
                val clean = value.trim()
                if (clean.isNotBlank() && clean != lastFinal) {
                    lastFinal = clean
                    val separator = if (composingFinal.isBlank()) "" else " "
                    currentInputConnection?.commitText("$separator$clean", 1)
                    composingFinal = listOf(composingFinal, clean)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                    latestPartial = ""
                    renderLiveStatus(composingFinal)
                }
            },
            onAmplitude = {
                mic.level = it
                publishLiveLevel(it)
            },
            onSilenceGate = { paused ->
                liveGatePaused = paused
                if (streaming != null) renderLiveStatus()
            },
            onError = {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                abandon()
            }
        )
        streaming = session
        runCatching { session.start() }
            .onSuccess {
                RecordingController.set(
                    RecordingState(
                        phase = RecordingPhase.RECORDING,
                        audioPath = archive.absolutePath,
                        isLive = true,
                        modelId = selectedModelId
                    )
                )
                LiveRecordingCoordinator.attach(
                    onFinish = {
                        if (streaming != null && uiMode == UiMode.RECORDING) finishRecording()
                    },
                    onDiscard = {
                        if (streaming != null) abandon()
                    }
                )
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_LIVE_START)
                )
                showRecording()
            }
            .onFailure {
                Toast.makeText(
                    this,
                    it.message ?: "Streaming could not start.",
                    Toast.LENGTH_LONG
                ).show()
                session.abandon()
                LiveRecordingCoordinator.detach()
                streaming = null
                showIdle()
            }
    }

    private fun publishLiveLevel(level: Float) {
        val elapsed = (SystemClock.elapsedRealtime() - streamingStartedAt).coerceAtLeast(0L)
        RecordingController.update { state ->
            if (!state.isLive) state else state.copy(
                elapsedMs = elapsed,
                amplitude = level,
                waveform = (state.waveform + level).takeLast(48)
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveWidgetUpdateMs >= LIVE_WIDGET_UPDATE_MS) {
            lastLiveWidgetUpdateMs = now
            TranscriptionWidgetProvider.updateWaveforms(this)
        }
    }

    private fun updateComposing(partial: String) {
        latestPartial = partial.trim()
        val value = listOf(composingFinal, partial.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
        // Keep only the provider's unstable partial composing. Rewriting the
        // complete live transcript here made Delete appear non-functional,
        // because the next streaming update restored already deleted text.
        val editablePartial = if (composingFinal.isNotBlank() && latestPartial.isNotBlank()) {
            " $latestPartial"
        } else {
            latestPartial
        }
        currentInputConnection?.setComposingText(editablePartial, 1)
        renderLiveStatus(value)
    }

    /**
     * One place renders the live line, so the silence pause can appear without
     * every transcript update overwriting it a moment later.
     */
    private fun renderLiveStatus(
        transcript: String = listOf(composingFinal, latestPartial)
            .filter(String::isNotBlank)
            .joinToString(" ")
    ) {
        val head = if (liveGatePaused) "PAUSED" else "LIVE"
        setStatusText(if (transcript.isBlank()) head else "$head  ·  ${transcript.takeLast(28)}")
    }

    private fun togglePause() {
        if (isLiveModel() || streaming != null) return
        sendRecordingAction(RecordingService.ACTION_TOGGLE_PAUSE)
    }

    private fun finishRecording() {
        val activeStreaming = streaming
        if (activeStreaming != null) {
            activeStreaming.accept()
            val elapsed = (SystemClock.elapsedRealtime() - streamingStartedAt).coerceAtLeast(0L)
            RecordingController.update { state ->
                state.copy(
                    phase = RecordingPhase.PROCESSING,
                    elapsedMs = elapsed,
                    amplitude = 0f,
                    isLive = true,
                    modelId = streamingModelId
                )
            }
            sendLiveAction(RecordingService.ACTION_LIVE_REFRESH)
            showProcessing("Finishing live transcript…")
            val finalize = Runnable {
                currentInputConnection?.finishComposingText()
                val text = liveTranscriptText()
                val mode = BaseTextProcessor.effectiveMode(AppContainer.settings.settings.value)
                if (mode == BaseCleanupMode.OFF || text.isBlank()) {
                    completeLiveSession(activeStreaming, text)
                } else {
                    // Live text is already in the field, so the cleanup runs
                    // after the fact and swaps exactly the range we inserted.
                    showProcessing("Cleaning up · ${mode.label}…")
                    val settings = AppContainer.settings.settings.value
                    val cleanupModels = AppContainer.models.cleanupModels.value
                    Thread {
                        val cleaned = BaseTextProcessor.process(text, settings, cleanupModels)
                        handler.post {
                            val applied = if (cleaned != text) replaceLiveTranscript(text, cleaned) else false
                            completeLiveSession(activeStreaming, if (applied) cleaned else text)
                        }
                    }.start()
                }
            }
            liveFinalize = finalize
            handler.postDelayed(finalize, 1_100L)
        } else {
            sendRecordingAction(RecordingService.ACTION_FINISH)
            showProcessing("Transcribing…")
        }
    }

    private fun completeLiveSession(session: StreamingVoiceSession, text: String) {
        val historyId = saveLiveHistory(session, text)
        RecordingController.update { state ->
            state.copy(
                phase = RecordingPhase.SUCCESS,
                amplitude = 0f,
                resultText = text,
                statusLabel = null,
                historyId = historyId,
                audioPath = historyId?.let {
                    File(filesDir, "audio_history/$it.wav").absolutePath
                },
                isLive = true,
                modelId = streamingModelId
            )
        }
        LiveRecordingCoordinator.detach()
        streaming = null
        liveFinalize = null
        sendLiveAction(RecordingService.ACTION_LIVE_STOP)
        showIdle()
    }

    /**
     * Swaps the streamed text the session just inserted for its cleaned form.
     *
     * Only the tail is touched, and only when it still matches what we wrote:
     * a live session may have been typed into or moved away from while the
     * cleanup was in flight, and silently rewriting a field the user has since
     * edited would be worse than leaving the raw transcript alone.
     */
    private fun replaceLiveTranscript(original: String, cleaned: String): Boolean {
        val connection = currentInputConnection ?: return false
        if (original.isBlank()) return false
        val before = connection.getTextBeforeCursor(original.length, 0)?.toString() ?: return false
        if (before != original) return false
        editHistory.recordBefore(currentFieldText())
        connection.beginBatchEdit()
        connection.deleteSurroundingText(original.length, 0)
        connection.commitText(cleaned, 1)
        connection.endBatchEdit()
        editHistory.recordAfter(currentFieldText())
        updateUndoBar()
        return true
    }

    private fun abandon() {
        if (CleanupCoordinator.isOwnerActive(CLEANUP_OWNER)) {
            CleanupCoordinator.cancel(CLEANUP_OWNER)
            showIdle()
            return
        }
        stopWatchingBatchState()
        val ownedBatchRequest = batchRequestId
        batchRequestId = null
        liveFinalize?.let(handler::removeCallbacks)
        liveFinalize = null
        val wasLive = streaming != null || RecordingController.state.value.isLive
        streaming?.abandon()
        streaming = null
        if (wasLive) {
            LiveRecordingCoordinator.detach()
            RecordingController.set(RecordingState())
            sendLiveAction(RecordingService.ACTION_LIVE_STOP)
        } else if (ownedBatchRequest != null) {
            sendRecordingAction(
                RecordingService.ACTION_CANCEL_REQUEST,
                ownedBatchRequest
            )
        } else {
            when (RecordingController.state.value.phase) {
                RecordingPhase.RECORDING, RecordingPhase.PAUSED ->
                    sendRecordingAction(RecordingService.ACTION_DISCARD)
                RecordingPhase.PROCESSING ->
                    sendRecordingAction(RecordingService.ACTION_ABANDON)
                else -> Unit
            }
        }
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        composingFinal = ""
        latestPartial = ""
        showIdle()
    }

    private fun liveTranscriptText() = listOf(composingFinal, latestPartial)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .trim()

    private fun saveLiveHistory(session: StreamingVoiceSession, text: String): String? {
        if (streamingHistorySaved) return null
        val sourceAudio = session.archivedAudio()
        if (text.isBlank() && sourceAudio == null) return null
        streamingHistorySaved = true
        val id = UUID.randomUUID().toString()
        val archivedAudio = sourceAudio?.let { source ->
            File(filesDir, "audio_history/$id.wav").also { destination ->
                destination.parentFile?.mkdirs()
                if (!source.renameTo(destination)) {
                    source.copyTo(destination, overwrite = true)
                    source.delete()
                }
            }
        }
        AppContainer.history.add(
            TranscriptionEntry(
                id = id,
                text = text,
                createdAt = System.currentTimeMillis(),
                durationMs = (SystemClock.elapsedRealtime() - streamingStartedAt).coerceAtLeast(0L),
                modelId = streamingModelId,
                audioPath = archivedAudio?.absolutePath,
                audioFormat = "wav",
                inputBytes = archivedAudio?.length() ?: 0L
            )
        )
        return id
    }

    private fun applyBatchState(state: RecordingState) {
        if (!ImeInteractionPolicy.ownsBatchState(batchRequestId, state.requestId)) {
            return
        }
        when (state.phase) {
            RecordingPhase.RECORDING -> {
                uiMode = UiMode.RECORDING
                mic.level = state.amplitude
                setStatusText(clock(state.elapsedMs))
                pause.text = "Ⅱ"
                pause.contentDescription = "Pause recording"
            }
            RecordingPhase.PAUSED -> {
                uiMode = UiMode.PAUSED
                mic.level = 0f
                setStatusText("PAUSED  ·  ${clock(state.elapsedMs)}")
                pause.text = "▶"
                pause.contentDescription = "Resume recording"
            }
            RecordingPhase.PROCESSING ->
                showProcessing(state.statusLabel?.takeIf { it.isNotBlank() }?.plus("…") ?: "Transcribing…")
            RecordingPhase.SUCCESS -> {
                if (state.resultText.isNotBlank()) {
                    recordedEdit { currentInputConnection?.commitText(state.resultText, 1) }
                }
                batchRequestId = null
                showIdle()
            }
            RecordingPhase.ERROR -> {
                batchRequestId = null
                Toast.makeText(
                    this,
                    state.errorMessage ?: "Transcription failed.",
                    Toast.LENGTH_LONG
                ).show()
                showIdle()
            }
            else -> Unit
        }
    }

    /**
     * The clock ticks once a second but the state it is read from updates about
     * twenty times a second, and `setText` costs a measure and a redraw whether
     * or not the string changed.
     */
    private fun setStatusText(text: String) {
        if (lastStatusText == text) return
        lastStatusText = text
        status.text = text
    }

    private fun showRecording() {
        closeOverlay()
        uiMode = UiMode.RECORDING
        modelControl.isEnabled = false
        modelControl.alpha = .62f
        historyButton.isEnabled = false
        historyButton.alpha = .62f
        mic.isEnabled = true
        mic.active = true
        mic.contentDescription = "Finish voice input"
        status.visibility = View.VISIBLE
        setStatusText(if (isLiveModel()) "LIVE" else "00:00")
        spaceKey.visibility = View.GONE
        abandon.visibility = View.VISIBLE
        pause.visibility = if (isLiveModel()) View.GONE else View.VISIBLE
        deleteKey.visibility = if (isLiveModel()) View.VISIBLE else View.GONE
        cleanupKey.visibility = View.GONE
        enterKey.visibility = View.INVISIBLE
        deleteKey.isEnabled = true
        if (isLiveModel()) deleteKey.bringToFront()
        pause.text = "Ⅱ"
    }

    private fun showProcessing(message: String) {
        closeOverlay()
        uiMode = UiMode.PROCESSING
        mic.level = 0f
        mic.active = false
        mic.isEnabled = false
        modelControl.isEnabled = false
        modelControl.alpha = .62f
        historyButton.isEnabled = false
        historyButton.alpha = .62f
        status.visibility = View.VISIBLE
        setStatusText(message)
        spaceKey.visibility = View.GONE
        abandon.visibility = View.VISIBLE
        pause.visibility = View.GONE
        deleteKey.visibility = View.VISIBLE
        cleanupKey.visibility = View.GONE
        enterKey.visibility = View.INVISIBLE
    }

    private fun showIdle() {
        stopWatchingBatchState()
        closeOverlay()
        uiMode = UiMode.IDLE
        mic.level = 0f
        mic.active = false
        mic.isEnabled = true
        mic.contentDescription = "Start voice input"
        modelControl.isEnabled = true
        modelControl.alpha = 1f
        historyButton.isEnabled = true
        historyButton.alpha = 1f
        keyboardButton.isEnabled = true
        keyboardButton.alpha = 1f
        setStatusText("")
        status.visibility = View.GONE
        abandon.visibility = View.INVISIBLE
        spaceKey.visibility = View.VISIBLE
        pause.visibility = View.GONE
        deleteKey.visibility = View.VISIBLE
        cleanupKey.visibility = View.VISIBLE
        enterKey.visibility = View.VISIBLE
        cleanupKey.isEnabled = !CleanupCoordinator.state.value.busy
        cleanupKey.alpha = if (cleanupKey.isEnabled) 1f else .35f
        cleanupKey.text = "✦"
        cleanupKey.setTextColor(palette.foreground)
        cleanupKey.background = roundedRipple(palette.surface, 32f, palette.ripple)
        cleanupHalo.visibility = View.GONE
        pause.text = "Ⅱ"
        updateUndoBar()
    }

    private fun toggleModelBrowser(forceOpen: Boolean = false) {
        if (uiMode != UiMode.IDLE || options.isEmpty()) return
        setOpenPanel(
            if (forceOpen || openPanel != ImePanel.MODELS) ImePanel.MODELS else ImePanel.NONE
        )
    }

    private fun toggleHistoryBrowser() {
        if (uiMode != UiMode.IDLE) return
        setOpenPanel(
            if (openPanel == ImePanel.HISTORY) ImePanel.NONE else ImePanel.HISTORY
        )
    }

    private fun toggleClipboardBrowser() {
        if (uiMode != UiMode.IDLE) return
        setOpenPanel(
            if (openPanel == ImePanel.CLIPBOARD) ImePanel.NONE else ImePanel.CLIPBOARD
        )
    }

    private fun setOpenPanel(panel: ImePanel) {
        val previousPanel = openPanel
        if (panel != ImePanel.NONE && openPanel == ImePanel.NONE) {
            // Keep the editor connection untouched while browsing. Google Keep
            // can drop its visible cursor when composing is finalized here.
            currentInputConnection?.let { editorConnection = it }
        }
        openPanel = panel
        if (!::recorderStage.isInitialized) return
        hidePunctuationMenu(commit = false)

        toolbar.visibility = if (panel == ImePanel.NONE) View.VISIBLE else View.GONE
        recorderStage.visibility = if (panel == ImePanel.NONE) View.VISIBLE else View.GONE
        updateUndoBar()
        modelBrowser.visibility = if (panel == ImePanel.MODELS) View.VISIBLE else View.GONE
        historyPanel.visibility = if (panel == ImePanel.HISTORY) View.VISIBLE else View.GONE
        clipboardPanel.visibility = if (panel == ImePanel.CLIPBOARD) View.VISIBLE else View.GONE
        overlayClose.visibility = if (panel == ImePanel.NONE) View.GONE else View.VISIBLE
        historyButton.background = roundedRipple(
            if (panel == ImePanel.HISTORY) palette.selectedSurface else palette.surface,
            16f,
            palette.ripple
        )
        historyButton.contentDescription =
            if (panel == ImePanel.HISTORY) "Close Notes history" else "Open Notes history"
        clipboardButton.background = roundedRipple(
            if (panel == ImePanel.CLIPBOARD) palette.selectedSurface else palette.surface,
            16f,
            palette.ripple
        )
        clipboardButton.contentDescription =
            if (panel == ImePanel.CLIPBOARD) "Close clipboard" else "Open clipboard"
        updateModelControl()

        if (panel == ImePanel.NONE) {
            cancelPendingBrowserPageLoads()
            modelGroupExpansionOverrides.clear()
            modelRenderedCounts.clear()
            modelGroupRows.clear()
            if (previousPanel == ImePanel.MODELS) modelList.removeAllViews()
            if (previousPanel == ImePanel.HISTORY) {
                historyEntries = emptyList()
                historyRenderedCount = 0
                historyNeedsRebuild = true
                historyList.removeAllViews()
            }
            if (previousPanel == ImePanel.CLIPBOARD) clipboardList.removeAllViews()
            root.clearFocus()
            unregisterOverlayBackHandler()
            setBackDisposition(BACK_DISPOSITION_DEFAULT)
            return
        }

        setBackDisposition(BACK_DISPOSITION_WILL_NOT_DISMISS)
        registerOverlayBackHandler()
        when (panel) {
            ImePanel.MODELS -> {
                modelPageLoadArmed = true
                rebuildModelBrowser()
                modelBrowser.post { modelBrowser.scrollTo(0, 0) }
            }
            ImePanel.HISTORY -> {
                historyPageLoadArmed = true
                prepareHistoryBrowser(forceReset = true)
                historyScroll.post { historyScroll.scrollTo(0, 0) }
            }
            ImePanel.CLIPBOARD -> {
                rebuildClipboardPanel()
                clipboardScroll.post { clipboardScroll.scrollTo(0, 0) }
            }
            ImePanel.NONE -> Unit
        }
    }

    private fun closeOverlay() {
        setOpenPanel(ImePanel.NONE)
    }

    private fun registerOverlayBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || overlayBackCallback != null) return
        val dispatcher = window?.window?.onBackInvokedDispatcher ?: return
        val callback = OnBackInvokedCallback { closeOverlay() }
        dispatcher.registerOnBackInvokedCallback(
            // Browsers are overlays inside the IME. Overlay priority makes
            // the side-swipe close it before Android's own IME-dismiss callback.
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback
        )
        overlayBackDispatcher = dispatcher
        overlayBackCallback = callback
    }

    private fun unregisterOverlayBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        overlayBackCallback?.let { callback ->
            overlayBackDispatcher?.unregisterOnBackInvokedCallback(callback)
        }
        overlayBackCallback = null
        overlayBackDispatcher = null
    }

    private fun prepareHistoryBrowser(forceReset: Boolean = false) {
        if (!::historyList.isInitialized) return
        if (!forceReset && !historyNeedsRebuild) return
        historyEntries = AppContainer.history.entries.value
        historyRenderedCount = 0
        historyNeedsRebuild = false
        historyList.removeAllViews()

        if (historyEntries.isEmpty()) {
            historyList.addView(
                label("No notes yet", 14f, palette.muted, Typeface.NORMAL).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(64), dp(16), dp(24))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        appendHistoryPage()
    }

    /**
     * History is deliberately created on demand on the main thread. A page is
     * small enough to stay responsive, and this avoids both constructing
     * hundreds of rows during panel open and introducing background work.
     */
    private fun appendHistoryPage() {
        if (!::historyList.isInitialized || historyRenderedCount >= historyEntries.size) return
        val endExclusive = BrowserRenderingPolicy.nextBatchEnd(
            renderedCount = historyRenderedCount,
            totalCount = historyEntries.size
        )
        historyEntries.subList(historyRenderedCount, endExclusive).forEach { entry ->
            historyList.addView(historyRow(entry))
            historyList.addView(
                View(this).apply {
                    setBackgroundColor(withAlpha(palette.foreground, 28))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            )
        }
        historyRenderedCount = endExclusive
    }

    /**
     * Extending a ScrollView while its fling is still settling makes Android
     * spring against the old bottom and then reposition against the new one.
     * Wait for a short quiet period and allow only one page per approach to the
     * boundary. The next page is armed after the user moves through the newly
     * appended content.
     */
    private fun scheduleHistoryPageLoad(scrollY: Int) {
        if (historyRenderedCount >= historyEntries.size) {
            pendingHistoryPageLoad?.let(handler::removeCallbacks)
            pendingHistoryPageLoad = null
            return
        }
        val remaining = historyScroll.scrollRemainingPx(scrollY)
        if (remaining > dp(BROWSER_LOAD_REARM_DISTANCE_DP)) {
            historyPageLoadArmed = true
            pendingHistoryPageLoad?.let(handler::removeCallbacks)
            pendingHistoryPageLoad = null
            return
        }
        if (remaining > dp(BROWSER_LOAD_THRESHOLD_DP)) return
        if (!historyPageLoadArmed && pendingHistoryPageLoad == null) return
        historyPageLoadArmed = false
        val task = pendingHistoryPageLoad ?: Runnable {
            pendingHistoryPageLoad = null
            if (openPanel == ImePanel.HISTORY) appendHistoryPage()
        }.also { pendingHistoryPageLoad = it }
        handler.removeCallbacks(task)
        handler.postDelayed(task, BROWSER_SCROLL_SETTLE_MS)
    }

    private fun historyRow(entry: TranscriptionEntry): View {
        val titleValue = when {
            entry.processing -> entry.progressLabel ?: "Transcribing…"
            entry.transcriptionFailed && entry.text.isBlank() -> "Transcription failed"
            else -> (if (entry.pinned) "PINNED  ·  " else "") + entry.displayTitle
        }
        val previewValue = entry.text.ifBlank {
            entry.failureMessage ?: "No transcript available yet."
        }
        var expanded = false

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(88)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = pressHighlight(palette.selectedSurface, 8f)
            isClickable = true
            isFocusable = false
            isLongClickable = true
            contentDescription = "Insert note ${entry.displayTitle}. Long press to expand."

            addView(
                label(
                    titleValue,
                    14f,
                    if (entry.pinned) palette.accent else palette.foreground,
                    Typeface.BOLD
                ).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val preview = label(previewValue, 13f, palette.foreground, Typeface.NORMAL).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(4), 0, 0)
            }
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                label(
                    "${historyDateTimeFormat.format(Date(entry.createdAt))}  ·  ${clock(entry.durationMs)}",
                    10f,
                    palette.muted,
                    Typeface.NORMAL
                ).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            setOnLongClickListener {
                expanded = !expanded
                preview.maxLines = if (expanded) Int.MAX_VALUE else 2
                preview.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
                preview.requestLayout()
                true
            }
            setOnClickListener { insertHistoryEntry(entry) }
        }
    }

    private fun insertHistoryEntry(entry: TranscriptionEntry) {
        if (entry.text.isBlank()) {
            Toast.makeText(this, "This note has no text yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val connectionAtTap = currentInputConnection ?: editorConnection
        // Close first. Keep restores its editor/cursor as the browser leaves;
        // committing while the browser is still handling the tap can target a
        // connection that Keep has just retired.
        closeOverlay()
        handler.post {
            val connection = currentInputConnection ?: connectionAtTap ?: editorConnection
            if (connection == null) {
                Toast.makeText(this, "The note could not be inserted here.", Toast.LENGTH_SHORT).show()
                return@post
            }
            val extracted = runCatching {
                connection.getExtractedText(ExtractedTextRequest(), 0)
            }.getOrNull()
            val cursor = extracted?.selectionEnd?.takeIf { it >= 0 }
            if (cursor != null) {
                runCatching { connection.setSelection(cursor, cursor) }
            }
            runCatching {
                connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE)
            }
            val committed = runCatching {
                connection.beginBatchEdit()
                try {
                    connection.finishComposingText()
                    connection.commitText(entry.text, 1)
                } finally {
                    connection.endBatchEdit()
                }
            }.getOrDefault(false)
            if (!committed) {
                Toast.makeText(this, "The note could not be inserted here.", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE)
                }
            }
        }
    }

    private fun rebuildModelBrowser() {
        if (!::modelList.isInitialized) return
        modelList.removeAllViews()
        modelGroupRows.clear()
        val byGroup = options.groupBy(ModelOption::group)
        MODEL_GROUPS
            .filter { byGroup[it].orEmpty().isNotEmpty() }
            .sortedBy { groupName -> if (byGroup[groupName].orEmpty().any(ModelOption::keyAvailable)) 0 else 1 }
            .forEach { groupName ->
            val groupOptions = byGroup[groupName].orEmpty()
            val groupAvailable = groupOptions.any(ModelOption::keyAvailable)
            val expanded = modelGroupExpansionOverrides[groupName]
                ?: !BrowserRenderingPolicy.modelGroupStartsCollapsed(groupOptions.size)
            modelList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(10), 0)
                    background = pressHighlight(palette.selectedSurface, 10f)
                    isClickable = true
                    isFocusable = false
                    contentDescription = "${if (expanded) "Collapse" else "Expand"} $groupName models"
                    setOnClickListener {
                        if (!expanded && BrowserRenderingPolicy.modelGroupStartsCollapsed(groupOptions.size)) {
                            byGroup.forEach { (otherGroup, otherOptions) ->
                                if (otherGroup != groupName &&
                                    BrowserRenderingPolicy.modelGroupStartsCollapsed(otherOptions.size)
                                ) {
                                    modelGroupExpansionOverrides[otherGroup] = false
                                    modelRenderedCounts.remove(otherGroup)
                                }
                            }
                        }
                        modelGroupExpansionOverrides[groupName] = !expanded
                        if (expanded) modelRenderedCounts.remove(groupName)
                        rebuildModelBrowser()
                    }
                    addView(
                        label(
                            buildString {
                                append(groupName)
                                append("  ·  ")
                                append(groupOptions.size)
                                if (!groupAvailable) append("  ·  API key missing")
                            },
                            12f,
                            if (groupAvailable) palette.foreground else palette.muted,
                            Typeface.BOLD
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        label(if (expanded) "\u25BE" else "\u25B8", 16f, palette.muted, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(dp(28), dp(28))
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                ).apply { bottomMargin = dp(3) }
            )
            if (expanded) {
                val sortedOptions = groupOptions.sortedBy { if (it.keyAvailable) 0 else 1 }
                val renderedCount = modelRenderedCounts[groupName]
                    ?.coerceAtMost(sortedOptions.size)
                    ?: BrowserRenderingPolicy.nextBatchEnd(0, sortedOptions.size)
                modelRenderedCounts[groupName] = renderedCount
                val rows = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    sortedOptions.take(renderedCount).forEach { option ->
                        addView(modelRow(option))
                    }
                }
                modelGroupRows[groupName] = rows
                modelList.addView(
                    rows,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    private fun appendModelPage() {
        val groupName = modelGroupRows.keys.firstOrNull { name ->
            val total = options.count { it.group == name }
            modelRenderedCounts.getOrDefault(name, 0) < total
        } ?: return
        val rows = modelGroupRows[groupName] ?: return
        val groupOptions = options
            .filter { it.group == groupName }
            .sortedBy { if (it.keyAvailable) 0 else 1 }
        val start = modelRenderedCounts.getOrDefault(groupName, 0)
        val end = BrowserRenderingPolicy.nextBatchEnd(start, groupOptions.size)
        groupOptions.subList(start, end).forEach { rows.addView(modelRow(it)) }
        modelRenderedCounts[groupName] = end
    }

    private fun scheduleModelPageLoad(scrollY: Int) {
        val hasMore = modelGroupRows.keys.any { name ->
            modelRenderedCounts.getOrDefault(name, 0) < options.count { it.group == name }
        }
        if (!hasMore) {
            pendingModelPageLoad?.let(handler::removeCallbacks)
            pendingModelPageLoad = null
            return
        }
        val remaining = modelBrowser.scrollRemainingPx(scrollY)
        if (remaining > dp(BROWSER_LOAD_REARM_DISTANCE_DP)) {
            modelPageLoadArmed = true
            pendingModelPageLoad?.let(handler::removeCallbacks)
            pendingModelPageLoad = null
            return
        }
        if (remaining > dp(BROWSER_LOAD_THRESHOLD_DP)) return
        if (!modelPageLoadArmed && pendingModelPageLoad == null) return
        modelPageLoadArmed = false
        val task = pendingModelPageLoad ?: Runnable {
            pendingModelPageLoad = null
            if (openPanel == ImePanel.MODELS) appendModelPage()
        }.also { pendingModelPageLoad = it }
        handler.removeCallbacks(task)
        handler.postDelayed(task, BROWSER_SCROLL_SETTLE_MS)
    }

    private fun ScrollView.scrollRemainingPx(scrollY: Int): Int {
        val contentHeight = getChildAt(0)?.height ?: return Int.MAX_VALUE
        if (contentHeight <= 0 || height <= 0) return Int.MAX_VALUE
        return (contentHeight - height - scrollY).coerceAtLeast(0)
    }

    private fun cancelPendingBrowserPageLoads() {
        pendingHistoryPageLoad?.let(handler::removeCallbacks)
        pendingModelPageLoad?.let(handler::removeCallbacks)
        pendingHistoryPageLoad = null
        pendingModelPageLoad = null
    }

    private fun modelRow(option: ModelOption): View {
        val selected = option.id == selectedModelId
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            background = roundedRipple(
                if (selected) palette.selectedSurface else palette.surface,
                13f,
                palette.ripple
            )
            isClickable = option.keyAvailable
            isFocusable = false
            alpha = if (option.keyAvailable) 1f else .38f
            contentDescription = if (option.keyAvailable) {
                "Use ${option.label}"
            } else {
                "${option.label} requires a ${option.provider.label} API key"
            }
            if (option.keyAvailable) setOnClickListener { selectModel(option) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                bottomMargin = dp(3)
            }

            addView(
                View(this@VoiceInputMethodService).apply {
                    background = roundedFill(
                        if (selected) palette.accent else Color.TRANSPARENT,
                        2f
                    )
                },
                LinearLayout.LayoutParams(dp(3), dp(22)).apply {
                    marginEnd = dp(10)
                }
            )
            addView(
                label(option.label, 13f, palette.foreground, if (selected) Typeface.BOLD else Typeface.NORMAL).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (selected) {
                addView(
                    label("✓", 15f, palette.accent, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(dp(30), dp(30))
                )
            }
        }
    }

    private fun selectModel(option: ModelOption) {
        selectedModelId = option.id
        getSharedPreferences(IME_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, selectedModelId)
            .apply()
        updateModelControl()
        closeOverlay()
    }

    private fun refreshModelOptions() {
        val previous = selectedModelId
        options = buildModelOptions()
        selectedModelId = previous.takeIf { id -> options.any { it.id == id } }
            ?: options.firstOrNull(ModelOption::keyAvailable)?.id
            ?: options.firstOrNull()?.id.orEmpty()
        updateModelControl()
        rebuildModelBrowser()
    }

    private fun updateModelControl() {
        if (!::modelName.isInitialized) return
        val selected = options.firstOrNull { it.id == selectedModelId }
        val arrow = if (modelBrowserOpen) "▴" else "▾"
        modelName.text = "${selected?.label ?: "Choose model"}  $arrow"
        modelProvider.text = if (selected?.keyAvailable == false) {
            "API KEY MISSING  ·  ${selected.group.uppercase()}"
        } else {
            (selected?.group ?: "Transcription").uppercase()
        }
    }

    private fun sendRecordingAction(action: String, requestId: String? = null) {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        requestId?.let { intent.putExtra(RecordingService.EXTRA_REQUEST_ID, it) }
        ContextCompat.startForegroundService(
            this,
            intent
        )
    }

    private fun sendLiveAction(action: String) {
        startService(Intent(this, RecordingService::class.java).setAction(action))
    }

    private fun switchBackToKeyboard() {
        if (!switchToPreviousInputMethod()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
    }

    private fun buildModelOptions(): List<ModelOption> {
        val available = AppContainer.models.models.value
        val providerKeys = mapOf(
            ApiKeyProvider.OPENROUTER to AppContainer.secrets.readApiKey(ApiKeyProvider.OPENROUTER).isNotBlank(),
            ApiKeyProvider.ELEVENLABS to AppContainer.secrets.readApiKey(ApiKeyProvider.ELEVENLABS).isNotBlank(),
            ApiKeyProvider.ASSEMBLYAI to AppContainer.secrets.readApiKey(ApiKeyProvider.ASSEMBLYAI).isNotBlank()
        )
        val batch = available
            .filterNot { it.streaming || ProviderModels.isStreaming(it.id) }
            .sortedByDescending { it.createdAt }
            .map {
                ModelOption(
                    id = it.id,
                    label = it.name.substringAfter(": "),
                    group = when (it.provider) {
                        TranscriptionProvider.OPENROUTER_STT -> "OpenRouter STT"
                        TranscriptionProvider.OPENROUTER_MULTIMODAL -> "OpenRouter multimodal"
                        TranscriptionProvider.OPENROUTER_TEXT -> "OpenRouter text"
                        TranscriptionProvider.ELEVENLABS -> "ElevenLabs"
                        TranscriptionProvider.ASSEMBLYAI -> "AssemblyAI"
                    },
                    provider = it.provider,
                    keyAvailable = providerKeys[apiKeyProvider(it.provider)] == true
                )
            }
        val live = available
            .filter { it.streaming || ProviderModels.isStreaming(it.id) }
            .map {
                ModelOption(
                    id = it.id,
                    label = it.name.substringAfter(": "),
                    group = "Live",
                    provider = it.provider,
                    keyAvailable = providerKeys[apiKeyProvider(it.provider)] == true
                )
            }
        return (live + batch).distinctBy(ModelOption::id)
    }

    private fun apiKeyProvider(provider: TranscriptionProvider) = when (provider) {
        TranscriptionProvider.OPENROUTER_STT,
        TranscriptionProvider.OPENROUTER_MULTIMODAL,
        TranscriptionProvider.OPENROUTER_TEXT -> ApiKeyProvider.OPENROUTER
        TranscriptionProvider.ELEVENLABS -> ApiKeyProvider.ELEVENLABS
        TranscriptionProvider.ASSEMBLYAI -> ApiKeyProvider.ASSEMBLYAI
    }

    private fun isLiveModel() = ProviderModels.isStreaming(selectedModelId)

    private fun actionButton(
        text: String,
        description: String,
        textSize: Float,
        onClick: () -> Unit
    ) = label(text, textSize, palette.foreground, Typeface.NORMAL).apply {
        gravity = Gravity.CENTER
        background = roundedRipple(palette.surface, 32f, palette.ripple)
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
    }

    private fun label(value: String, size: Float, color: Int, style: Int) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            includeFontPadding = false
        }

    private fun roundedRipple(fill: Int, radiusDp: Float, ripple: Int) =
        RippleDrawable(
            ColorStateList.valueOf(ripple),
            roundedFill(fill, radiusDp),
            null
        )

    /**
     * A surface that lifts one step while the finger is on it or a pointer is
     * over it, with the standard ripple on top.
     *
     * `roundedRipple` alone only animates on contact; nothing changes on hover,
     * so a pointer resting on the key gives no sign the key is live. The state
     * list adds the steady part of that feedback and the ripple keeps the
     * moving part, which together match how the outer controls behave.
     */
    private fun litRipple(fill: Int, radiusDp: Float, ripple: Int) =
        RippleDrawable(
            ColorStateList.valueOf(ripple),
            StateListDrawable().apply {
                val lit = blend(fill, palette.foreground, .09f)
                addState(intArrayOf(android.R.attr.state_pressed), roundedFill(lit, radiusDp))
                addState(intArrayOf(android.R.attr.state_hovered), roundedFill(lit, radiusDp))
                addState(intArrayOf(), roundedFill(fill, radiusDp))
            },
            null
        )

    private fun outlinedRoundedRipple(
        fill: Int,
        radiusDp: Float,
        stroke: Int,
        ripple: Int
    ) = RippleDrawable(
        ColorStateList.valueOf(ripple),
        roundedFill(fill, radiusDp).apply {
            setStroke(dp(1), withAlpha(stroke, 86))
        },
        null
    )

    /**
     * The microphone paints a circle inside a much larger touch target. An
     * unmasked foreground ripple therefore lights up the whole square and reads
     * as a huge grey block, so pressed and hovered feedback is masked and
     * radius-limited to the visible button.
     */
    private fun circularRipple(ripple: Int, viewSizePx: Int, diameterPx: Int) =
        RippleDrawable(
            ColorStateList.valueOf(ripple),
            null,
            InsetDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                },
                ((viewSizePx - diameterPx) / 2).coerceAtLeast(0)
            )
        ).apply { radius = diameterPx / 2 }

    /**
     * A bounded, non-animated pressed state. An unmasked foreground ripple can
     * expand into a large grey circle across a history row.
     */
    private fun pressHighlight(fill: Int, radiusDp: Float) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedFill(fill, radiusDp))
            addState(intArrayOf(android.R.attr.state_hovered), roundedFill(fill, radiusDp))
            addState(intArrayOf(), roundedFill(Color.TRANSPARENT, radiusDp))
        }

    private fun roundedFill(fill: Int, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
        }

    /** Opaque mix of two colors, so a lifted surface stays opaque over the keyboard. */
    private fun blend(base: Int, over: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        fun channel(from: Int, to: Int) = (from + (to - from) * ratio).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(base), Color.red(over)),
            channel(Color.green(base), Color.green(over)),
            channel(Color.blue(base), Color.blue(over))
        )
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun MotionEvent.isInside(view: View) =
        x >= 0f && x < view.width.toFloat() && y >= 0f && y < view.height.toFloat()

    /**
     * Keep the complete IME footprint near 35% of the current display. Android
     * renders its globe/collapse navigation strip outside this input view, so
     * that system-owned height is removed from our share.
     */
    private fun keyboardContentHeightPx(): Int {
        val displayHeight = resources.displayMetrics.heightPixels
        val totalTarget = (displayHeight * KEYBOARD_SCREEN_FRACTION).roundToInt()
        return (totalTarget - navigationBarHeightPx()).coerceAtLeast(dp(276))
    }

    private fun navigationBarHeightPx(): Int {
        val navigationBarId = resources.getIdentifier(
            "navigation_bar_height",
            "dimen",
            "android"
        )
        return if (navigationBarId != 0) {
            resources.getDimensionPixelSize(navigationBarId)
        } else {
            0
        }
    }

    private fun browserSystemBarExclusionPx() =
        navigationBarHeightPx().coerceAtLeast(dp(64))

    private fun clock(ms: Long): String {
        val seconds = ms / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    /**
     * InputMethodService's host may replace the returned root's LayoutParams
     * with wrap-content. Force the same measured height for recorder, Notes and
     * Models so a large child can never expand the IME toward full screen.
     */
    private class FixedHeightLinearLayout(
        context: Context,
        private val fixedHeightPx: Int
    ) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(fixedHeightPx, View.MeasureSpec.EXACTLY)
            )
        }
    }

    private data class ModelOption(
        val id: String,
        val label: String,
        val group: String,
        val provider: TranscriptionProvider,
        val keyAvailable: Boolean
    )

    private enum class ImePanel { NONE, MODELS, HISTORY, CLIPBOARD }

    private enum class UiMode {
        IDLE,
        RECORDING,
        PAUSED,
        PROCESSING,
        CLEANUP_RECORDING,
        CLEANUP_PROCESSING
    }

    private data class ImePalette(
        val background: Int,
        val surface: Int,
        val selectedSurface: Int,
        val foreground: Int,
        val muted: Int,
        val accent: Int,
        val accentForeground: Int,
        val ripple: Int
    ) {
        companion object {
            fun dark() = ImePalette(
                background = Color.rgb(13, 13, 12),
                surface = Color.rgb(36, 36, 32),
                selectedSurface = Color.rgb(42, 48, 58),
                foreground = Color.rgb(233, 229, 219),
                muted = Color.rgb(189, 185, 175),
                accent = Color.rgb(138, 180, 248),
                accentForeground = Color.WHITE,
                ripple = Color.argb(42, 255, 255, 255)
            )

            fun light() = ImePalette(
                background = Color.rgb(248, 245, 237),
                surface = Color.rgb(255, 253, 248),
                selectedSurface = Color.rgb(225, 235, 252),
                foreground = Color.rgb(17, 17, 15),
                muted = Color.rgb(98, 96, 89),
                accent = Color.rgb(66, 133, 244),
                accentForeground = Color.WHITE,
                ripple = Color.argb(30, 17, 17, 15)
            )
        }
    }

    private companion object {
        const val IME_PREFERENCES = "voice_ime"
        const val KEY_MODEL = "model"
        const val CLEANUP_OWNER = "ime"
        const val IME_DEFAULT_MODEL = ProviderModels.ELEVENLABS_SCRIBE_V2
        // Raised from 0.40 with the undo row: the row is real content, and
        // taking its height out of the recorder would crowd the microphone.
        const val KEYBOARD_SCREEN_FRACTION = 0.43f
        const val LIVE_WIDGET_UPDATE_MS = 600L

        /**
         * How long typing must settle before it costs a snapshot. Long enough
         * that continuous writing takes one binder call per interval, short
         * enough that undo lands somewhere the user recognizes.
         */
        const val TYPING_SAMPLE_MS = 10_000L
        const val UNDO_BAR_RADIUS_DP = 19f

        /**
         * How far a key that cannot act fades. Deliberately below the .32 it
         * used to be: at that level a dead half still looked like a key someone
         * might reasonably press.
         */
        const val DISABLED_KEY_ALPHA = .22f
        const val BROWSER_LOAD_THRESHOLD_DP = 160
        const val BROWSER_LOAD_REARM_DISTANCE_DP = 320
        const val BROWSER_SCROLL_SETTLE_MS = 90L
        val MODEL_GROUPS = listOf(
            "Live",
            "OpenRouter STT",
            "ElevenLabs",
            "AssemblyAI",
            "OpenRouter multimodal"
        )
        val ACTIVE_PHASES = setOf(
            RecordingPhase.RECORDING,
            RecordingPhase.PAUSED,
            RecordingPhase.PROCESSING
        )
    }
}
