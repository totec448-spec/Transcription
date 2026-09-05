package com.example.transcription.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.transcription.AppContainer
import com.example.transcription.R
import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.ThemeMode
import com.example.transcription.recording.RecordingActionReceiver
import com.example.transcription.recording.RecordingController
import com.example.transcription.recording.RecordingService
import kotlin.math.abs
import kotlin.math.sin

class TranscriptionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AppContainer.initialize(context)
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        AppContainer.initialize(context)
        update(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAll(context)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val layout = layoutFor(manager, id)
        val views = RemoteViews(context.packageName, layout)
        val state = RecordingController.state.value
        val activeImport = AppContainer.history.entries.value.firstOrNull { it.processing }
        val active = state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.PAUSED
        val busy = state.phase == RecordingPhase.PROCESSING || activeImport != null
        val dark = isDark(context)
        val foreground = if (dark) Color.rgb(248, 246, 239) else Color.rgb(17, 17, 15)
        val controlBackground = if (dark) R.drawable.widget_control_touch_dark else R.drawable.widget_primary_touch
        val controlForeground = if (dark) Color.rgb(248, 246, 239) else Color.WHITE
        val recordBackground = if (dark) R.drawable.widget_button_touch else R.drawable.widget_primary_touch
        val recordForeground = if (dark) Color.rgb(17, 17, 15) else Color.WHITE
        // Accept intentionally remains the high-contrast white terminal action
        // in both themes.
        val acceptBackground = R.drawable.widget_primary_touch_dark
        val acceptForeground = Color.rgb(17, 17, 15)
        val background = when {
            layout == R.layout.widget_large && dark -> R.drawable.widget_background_large_dark
            layout == R.layout.widget_large -> R.drawable.widget_background_large
            layout == R.layout.widget_medium && dark -> R.drawable.widget_background_medium_dark
            layout == R.layout.widget_medium -> R.drawable.widget_background_medium
            dark -> R.drawable.widget_background_small_dark
            else -> R.drawable.widget_background_small
        }
        views.setInt(R.id.widget_root, "setBackgroundResource", background)
        views.setTextViewText(R.id.widget_status, status(state, activeImport))
        views.setTextColor(R.id.widget_status, foreground)
        views.setTextColor(R.id.widget_separator, foreground)
        views.setOnClickPendingIntent(R.id.widget_record, serviceAction(context, RecordingService.ACTION_START, 101))
        views.setOnClickPendingIntent(
            R.id.widget_root,
            when {
                active -> serviceAction(context, RecordingService.ACTION_FINISH, 105)
                busy -> null
                else -> serviceAction(context, RecordingService.ACTION_START, 106)
            }
        )
        views.setInt(R.id.widget_record, "setBackgroundResource", recordBackground)
        views.setInt(R.id.widget_record, "setColorFilter", recordForeground)
        views.setViewVisibility(R.id.widget_record, when {
            busy || active -> View.GONE
            else -> View.VISIBLE
        })
        val emphasizedWaveform = active || busy
        views.setImageViewBitmap(
            R.id.widget_waveform,
            renderWaveform(context, state.waveform, emphasizedWaveform, layout == R.layout.widget_large, dark)
        )
        views.setOnClickPendingIntent(
            R.id.widget_discard,
            serviceAction(
                context,
                if (busy && !state.isLive) RecordingService.ACTION_ABANDON else RecordingService.ACTION_DISCARD,
                104
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_done,
            serviceAction(
                context,
                when {
                    busy -> RecordingService.ACTION_USE_FALLBACK
                    else -> RecordingService.ACTION_FINISH
                },
                103
            )
        )
        views.setInt(R.id.widget_discard, "setBackgroundResource", controlBackground)
        views.setInt(R.id.widget_discard, "setColorFilter", controlForeground)
        views.setInt(R.id.widget_done, "setBackgroundResource", acceptBackground)
        views.setInt(R.id.widget_done, "setColorFilter", acceptForeground)
        if (busy && !state.isLive) {
            if (layout != R.layout.widget_small) views.setImageViewResource(R.id.widget_pause, R.drawable.ic_fallback)
            else views.setImageViewResource(R.id.widget_done, R.drawable.ic_fallback)
        } else if (layout != R.layout.widget_small) {
            views.setImageViewResource(R.id.widget_pause, R.drawable.ic_pause)
            views.setImageViewResource(R.id.widget_done, R.drawable.ic_done)
        } else if (active) {
            views.setImageViewResource(R.id.widget_done, R.drawable.ic_done)
        }
        views.setViewVisibility(R.id.widget_discard, if (active || busy) View.VISIBLE else View.GONE)
        views.setViewVisibility(
            R.id.widget_done,
            if (active || (busy && !state.isLive && layout == R.layout.widget_small)) View.VISIBLE else View.GONE
        )
        if (layout != R.layout.widget_small) {
            views.setOnClickPendingIntent(
                R.id.widget_pause,
                serviceAction(
                    context,
                    if (busy) RecordingService.ACTION_USE_FALLBACK else RecordingService.ACTION_TOGGLE_PAUSE,
                    102
                )
            )
            views.setViewVisibility(
                R.id.widget_pause,
                if ((active || busy) && !state.isLive) View.VISIBLE else View.GONE
            )
            views.setInt(R.id.widget_pause, "setBackgroundResource", controlBackground)
            views.setInt(R.id.widget_pause, "setColorFilter", controlForeground)
        }
        if (layout == R.layout.widget_large) {
            val latest = state.resultText.ifBlank {
                AppContainer.history.entries.value.firstOrNull { !it.transcriptionFailed && it.text.isNotBlank() }?.text.orEmpty()
            }
            views.setTextViewText(R.id.widget_result, latest.ifBlank { "Your latest transcript appears here." })
            views.setTextColor(R.id.widget_result, foreground)
            views.setInt(R.id.widget_result, "setBackgroundResource", if (dark) R.drawable.widget_result_background_dark else R.drawable.widget_result_background)
            views.setOnClickPendingIntent(R.id.widget_result, null)
        }
        manager.updateAppWidget(id, views)
    }

    private fun status(state: com.example.transcription.data.RecordingState, activeImport: com.example.transcription.data.TranscriptionEntry? = null) = when {
        activeImport != null -> if (activeImport.queuePosition > 0) "QUEUED ${activeImport.queuePosition}" else if (activeImport.chunkCount > 1) "PART ${activeImport.completedChunks + 1}/${activeImport.chunkCount}" else "IMPORTING"
        else -> when (state.phase) {
        RecordingPhase.RECORDING ->
            "${if (state.isLive) "LIVE" else "REC"}  •  ${duration(state.elapsedMs)}"
        RecordingPhase.PAUSED -> "PAUSED  •  ${duration(state.elapsedMs)}"
        RecordingPhase.PROCESSING ->
            state.statusLabel?.takeIf { it.isNotBlank() }?.uppercase()
                ?: "PROCESSING  ·  ${duration(state.elapsedMs)}"
        RecordingPhase.ERROR -> "Retry in app"
        else -> "Ready"
        }
    }

    private fun duration(ms: Long): String {
        val seconds = ms / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun renderWaveform(
        context: Context,
        levels: List<Float>,
        emphasized: Boolean,
        large: Boolean,
        dark: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        // These are dp dimensions converted to physical pixels. Rendering the old
        // values as raw pixels made launchers upscale the bitmap 2-3x and blur it.
        val width = ((if (large) 360f else 210f) * density).toInt()
        val height = ((if (large) 44f else 42f) * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Geometry is deliberately state-independent. The ImageView center-crops
        // this same waveform when controls occupy more space, rather than squeezing
        // it and changing bar width/resolution between idle, recording and processing.
        val count = if (large) 58 else 44
        val gap = width.toFloat() / count
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                emphasized && dark -> Color.rgb(248, 246, 239)
                emphasized -> Color.rgb(17, 17, 15)
                dark -> Color.rgb(115, 115, 108)
                else -> Color.rgb(185, 182, 173)
            }
            strokeWidth = (gap * 0.44f).coerceAtLeast(1.8f * density)
            strokeCap = Paint.Cap.ROUND
        }
        val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.argb(48, 248, 246, 239) else Color.argb(42, 17, 17, 15)
            strokeWidth = 1.25f * density
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, baseline)
        val recent = levels.takeLast(count)
        val values = if (recent.isEmpty()) {
            List(count) { index ->
                val slow = abs(sin(index * 0.31f)).toFloat()
                val fast = abs(sin(index * 0.83f + 1.2f)).toFloat()
                0.025f + slow * fast * 0.26f
            }
        } else {
            // RemoteViews center-crops the same high-resolution waveform in every
            // widget state. Left-padding a new recording with silence therefore
            // placed its first real samples outside the visible center crop for
            // several seconds. Stretch the available history across the fixed
            // canvas until the buffer is full so speech is visible immediately;
            // once [count] samples exist this becomes a 1:1 copy.
            resampleWaveform(recent, count)
        }
        values.forEachIndexed { index, raw ->
            val value = raw.coerceIn(0f, 1f)
            val half = if (emphasized) {
                // Linear, like every other surface: the level arriving here is
                // already normalized against this microphone.
                1.0f * density + value * (height * 0.32f)
            } else {
                1.2f * density + value * (height * 0.30f)
            }
            val x = gap * index + gap / 2f
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
        return bitmap
    }

    private fun resampleWaveform(samples: List<Float>, count: Int): List<Float> {
        if (samples.size == 1) return List(count) { samples.first() }
        return List(count) { index ->
            val position = index * samples.lastIndex.toFloat() / (count - 1).coerceAtLeast(1)
            val left = position.toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = position - left
            samples[left] + (samples[right] - samples[left]) * fraction
        }
    }

    private fun serviceAction(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, RecordingActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val ACTION_REFRESH = "com.example.transcription.WIDGET_REFRESH"

        /**
         * Which layout a widget of this cell size gets. Shared with
         * [updateWaveforms], which redraws the same widgets several times a
         * second and has to pick exactly the same layout the full update did —
         * a second copy of these thresholds could disagree and swap the layout
         * under a running recording.
         */
        private fun layoutFor(manager: AppWidgetManager, id: Int): Int {
            val options = manager.getAppWidgetOptions(id)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return when {
                minWidth >= 250 && minHeight >= 130 -> R.layout.widget_large
                minWidth >= 250 -> R.layout.widget_medium
                else -> R.layout.widget_small
            }
        }

        /** The theme the widget draws in: the app's setting, or the system's. */
        private fun isDark(context: Context): Boolean {
            val systemDark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return when (AppContainer.settings.settings.value.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TranscriptionWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) TranscriptionWidgetProvider().onUpdate(context, manager, ids)
        }

        fun updateWaveforms(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TranscriptionWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val provider = TranscriptionWidgetProvider()
            val state = RecordingController.state.value
            val activeImport = AppContainer.history.entries.value.firstOrNull { it.processing }
            val active = state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.PAUSED
            val dark = isDark(context)
            val busy = state.phase == RecordingPhase.PROCESSING || activeImport != null
            val label = provider.status(state, activeImport)
            // Only the large layout draws a different waveform, so two widgets
            // of the same size share one bitmap instead of rasterizing an
            // identical several-hundred-kilobyte canvas twice per tick.
            val waveforms = HashMap<Boolean, Bitmap>(2)
            ids.forEach { id ->
                val layout = layoutFor(manager, id)
                val large = layout == R.layout.widget_large
                val views = RemoteViews(context.packageName, layout)
                views.setImageViewBitmap(
                    R.id.widget_waveform,
                    waveforms.getOrPut(large) {
                        provider.renderWaveform(context, state.waveform, active || busy, large, dark)
                    }
                )
                views.setTextViewText(R.id.widget_status, label)
                manager.partiallyUpdateAppWidget(id, views)
            }
        }
    }
}
