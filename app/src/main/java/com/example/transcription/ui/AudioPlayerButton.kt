package com.example.transcription.ui

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun AudioPlayerButton(
    path: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    content: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    val available = !path.isNullOrBlank() && File(path).exists()

    DisposableEffect(path) {
        onDispose { player?.release(); player = null }
    }

    BouncyIconButton(
        icon = if (playing) VoiceIcon.PAUSE else VoiceIcon.PLAY,
        onClick = {
            if (!available) return@BouncyIconButton
            val current = player
            if (current == null) {
                player = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener { playing = false; seekTo(0) }
                    start()
                }
                playing = true
            } else if (playing) {
                current.pause(); playing = false
            } else {
                current.start(); playing = true
            }
        },
        modifier = modifier,
        size = size,
        container = container,
        content = content,
        enabled = available
    )
}
