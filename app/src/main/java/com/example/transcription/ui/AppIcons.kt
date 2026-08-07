package com.example.transcription.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class VoiceIcon { MIC, HISTORY, SETTINGS, PAUSE, PLAY, CLOSE, CHECK, COPY, CLEANUP, TRASH, SEARCH, REFRESH, CHEVRON, DOWNLOAD, UPLOAD, SKIP_BACK, SKIP_FORWARD }

@Composable
fun LineIcon(type: VoiceIcon, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val resolved = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = (minOf(w, h) * 0.085f).coerceAtLeast(2f)
        val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun segment(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(resolved, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke, StrokeCap.Round)
        when (type) {
            VoiceIcon.MIC -> {
                drawRoundRect(resolved, Offset(w * .34f, h * .10f), Size(w * .32f, h * .52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .16f), style = line)
                val path = Path().apply { moveTo(w * .18f, h * .48f); cubicTo(w * .18f, h * .72f, w * .33f, h * .80f, w * .50f, h * .80f); cubicTo(w * .67f, h * .80f, w * .82f, h * .72f, w * .82f, h * .48f) }
                drawPath(path, resolved, style = line)
                segment(.50f, .80f, .50f, .94f); segment(.34f, .94f, .66f, .94f)
            }
            VoiceIcon.HISTORY -> {
                segment(.18f, .24f, .82f, .24f); segment(.18f, .50f, .70f, .50f); segment(.18f, .76f, .55f, .76f)
                drawCircle(resolved, stroke * .55f, Offset(w * .86f, h * .76f))
            }
            VoiceIcon.SETTINGS -> {
                drawCircle(resolved, w * .29f, Offset(w / 2, h / 2), style = line)
                drawCircle(resolved, w * .07f, Offset(w / 2, h / 2))
                repeat(8) { index ->
                    val a = Math.PI * 2 * index / 8
                    val x1 = .5f + kotlin.math.cos(a).toFloat() * .34f
                    val y1 = .5f + kotlin.math.sin(a).toFloat() * .34f
                    val x2 = .5f + kotlin.math.cos(a).toFloat() * .43f
                    val y2 = .5f + kotlin.math.sin(a).toFloat() * .43f
                    segment(x1, y1, x2, y2)
                }
            }
            VoiceIcon.PAUSE -> { segment(.36f, .25f, .36f, .75f); segment(.64f, .25f, .64f, .75f) }
            VoiceIcon.PLAY -> drawPath(Path().apply { moveTo(w * .34f, h * .22f); lineTo(w * .76f, h * .50f); lineTo(w * .34f, h * .78f); close() }, resolved)
            VoiceIcon.CLOSE -> { segment(.25f, .25f, .75f, .75f); segment(.75f, .25f, .25f, .75f) }
            VoiceIcon.CHECK -> { segment(.18f, .53f, .40f, .75f); segment(.40f, .75f, .82f, .28f) }
            VoiceIcon.COPY -> {
                drawRoundRect(resolved, Offset(w * .16f, h * .26f), Size(w * .52f, h * .58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = line)
                drawRoundRect(resolved, Offset(w * .34f, h * .10f), Size(w * .50f, h * .56f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = line)
            }
            VoiceIcon.CLEANUP -> {
                segment(.22f, .78f, .72f, .28f)
                segment(.18f, .84f, .28f, .74f)
                segment(.68f, .32f, .78f, .22f)
                segment(.23f, .20f, .23f, .36f)
                segment(.15f, .28f, .31f, .28f)
                segment(.76f, .61f, .76f, .77f)
                segment(.68f, .69f, .84f, .69f)
            }
            VoiceIcon.TRASH -> {
                segment(.25f, .28f, .75f, .28f); segment(.39f, .17f, .61f, .17f)
                drawRoundRect(resolved, Offset(w * .31f, h * .28f), Size(w * .38f, h * .57f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .05f), style = line)
            }
            VoiceIcon.SEARCH -> {
                drawCircle(
                    resolved,
                    radius = w * .27f,
                    center = Offset(w * .43f, h * .42f),
                    style = line
                )
                segment(.62f, .62f, .84f, .84f)
            }
            VoiceIcon.REFRESH -> {
                drawArc(resolved, -55f, 275f, false, topLeft = Offset(w * .15f, h * .15f), size = Size(w * .70f, h * .70f), style = line)
                segment(.72f, .12f, .85f, .18f); segment(.85f, .18f, .80f, .33f)
            }
            VoiceIcon.CHEVRON -> { segment(.34f, .24f, .62f, .50f); segment(.62f, .50f, .34f, .76f) }
            VoiceIcon.DOWNLOAD -> {
                segment(.50f, .14f, .50f, .66f)
                segment(.29f, .47f, .50f, .68f)
                segment(.50f, .68f, .71f, .47f)
                segment(.20f, .84f, .80f, .84f)
            }
            VoiceIcon.UPLOAD -> {
                segment(.50f, .18f, .50f, .70f)
                segment(.29f, .39f, .50f, .18f)
                segment(.50f, .18f, .71f, .39f)
                segment(.20f, .84f, .80f, .84f)
            }
            VoiceIcon.SKIP_BACK, VoiceIcon.SKIP_FORWARD -> {
                val reverse = type == VoiceIcon.SKIP_BACK
                val left = if (reverse) .66f else .34f
                val right = if (reverse) .30f else .70f
                drawPath(Path().apply {
                    moveTo(w * left, h * .24f)
                    lineTo(w * right, h * .50f)
                    lineTo(w * left, h * .76f)
                    close()
                }, resolved)
                val bar = if (reverse) .23f else .77f
                segment(bar, .24f, bar, .76f)
            }
        }
    }
}

@Composable
fun BouncyIconButton(
    icon: VoiceIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    container: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    content: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .88f else 1f, spring(dampingRatio = .55f, stiffness = 650f), label = "buttonScale")
    Surface(
        modifier = modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = if (enabled) container else container.copy(alpha = .35f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            LineIcon(icon, Modifier.size(size * .42f), if (enabled) content else content.copy(alpha = .35f))
        }
    }
}
