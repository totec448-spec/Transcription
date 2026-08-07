package com.example.transcription.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun AudioScrubber(path: String?, seed: Int, modifier: Modifier = Modifier) {
    val available = !path.isNullOrBlank() && File(path).exists()
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) {
        if (!available) null else runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
            }
        }.getOrNull()
    }
    val total = player?.duration?.coerceAtLeast(1) ?: 1

    DisposableEffect(player) {
        player?.setOnCompletionListener { completed ->
            completed.seekTo(0)
            position = 0
            playing = false
        }
        onDispose { player?.release() }
    }
    LaunchedEffect(playing, player) {
        while (playing && player != null) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(160)
        }
    }

    fun seek(fraction: Float) {
        position = (fraction.coerceIn(0f, 1f) * total).toInt()
        player?.seekTo(position)
    }

    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            val played = position.toFloat() / total
            val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .30f)
            val progress = MaterialTheme.colorScheme.primary
            Canvas(
                Modifier.fillMaxWidth().height(38.dp).pointerInput(total) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        seek(down.position.x / size.width)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            seek(change.position.x / size.width)
                            change.consume()
                        } while (event.changes.any { it.pressed })
                    }
                }
            ) {
                val count = 64
                repeat(count) { index ->
                    val value = .12f + abs(sin(seed * .0001f + index * .71f)) * .76f
                    val x = size.width / count * index + size.width / count / 2
                    val half = size.height * value * .44f
                    drawLine(
                        if (index.toFloat() / count <= played) progress else idle,
                        Offset(x, size.height / 2 - half),
                        Offset(x, size.height / 2 + half),
                        4f,
                        StrokeCap.Round
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                BouncyIconButton(
                    VoiceIcon.SKIP_BACK,
                    { position = (position - 10_000).coerceAtLeast(0); player?.seekTo(position) },
                    size = 48.dp,
                    enabled = player != null
                )
                Spacer(Modifier.width(7.dp))
                BouncyIconButton(
                    if (playing) VoiceIcon.PAUSE else VoiceIcon.PLAY,
                    {
                        if (player != null) {
                            if (playing) player.pause() else player.start()
                            playing = !playing
                        }
                    },
                    size = 48.dp,
                    enabled = player != null
                )
                Spacer(Modifier.width(7.dp))
                BouncyIconButton(
                    VoiceIcon.SKIP_FORWARD,
                    { position = (position + 10_000).coerceAtMost(total); player?.seekTo(position) },
                    size = 48.dp,
                    enabled = player != null
                )
                Spacer(Modifier.width(14.dp))
                Text("${clock(position)} / ${clock(total)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun clock(ms: Int): String {
    val seconds = ms / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
