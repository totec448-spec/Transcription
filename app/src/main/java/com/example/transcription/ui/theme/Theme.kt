package com.example.transcription.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    background = Night,
    onBackground = Paper,
    surface = NightSurface,
    onSurface = Paper,
    surfaceVariant = Color(0xFF242420),
    onSurfaceVariant = Color(0xFFBDB9AF),
    error = RecordRed,
    errorContainer = Color(0xFF4B1717),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF666660)
)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFDF8),
    onSurface = Ink,
    surfaceVariant = Bone,
    onSurfaceVariant = Color(0xFF626059),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF777770)
)

@Composable
fun TranscriptionTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = Color.Transparent.value.toInt()
        window.navigationBarColor = Color.Transparent.value.toInt()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
}
