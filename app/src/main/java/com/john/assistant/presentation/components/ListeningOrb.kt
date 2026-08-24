package com.john.assistant.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.john.assistant.core.assistant.AssistantState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The orb.
 *
 * It is the whole interface for a user who is not looking closely: shape and
 * motion say what John is doing before any text is read. Each state gets a
 * distinct *motion*, not just a distinct colour, because colour alone is
 * useless at a glance and to anyone who cannot distinguish these hues:
 *
 *  - idle: a slow, barely-there breath;
 *  - listening: the rim reacts to the microphone level, so the user can see
 *    that they are being heard — the single most reassuring thing a voice UI
 *    can show;
 *  - thinking: a rotating sweep;
 *  - executing: a tighter, faster pulse;
 *  - speaking: a wide, slow swell in time with speech;
 *  - error: still, and red.
 *
 * Drawn with a Canvas rather than nested composables so the whole thing is one
 * draw pass per frame.
 */
@Composable
fun ListeningOrb(
    state: AssistantState,
    micLevel: Float,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    primary: Color,
    secondary: Color,
    errorColor: Color,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = state.cycleMillis(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Microphone level is noisy and in dB; smoothing it stops the rim from
    // jittering on every syllable.
    val normalisedLevel = ((micLevel + 2f) / 12f).coerceIn(0f, 1f)
    val level by animateFloatAsState(
        targetValue = if (state == AssistantState.LISTENING) normalisedLevel else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "level",
    )

    val tint by animateColorAsState(
        targetValue = when (state) {
            AssistantState.ERROR -> errorColor
            AssistantState.EXECUTING, AssistantState.SPEAKING -> secondary
            else -> primary
        },
        animationSpec = tween(durationMillis = 400),
        label = "tint",
    )

    Canvas(modifier = modifier.size(size)) {
        drawOrb(state = state, phase = phase, level = level, tint = tint)
    }
}

private fun DrawScope.drawOrb(
    state: AssistantState,
    phase: Float,
    level: Float,
    tint: Color,
) {
    val centre = Offset(this.size.width / 2f, this.size.height / 2f)
    val baseRadius = this.size.minDimension / 2f

    val breath = when (state) {
        AssistantState.IDLE -> 0.02f * sin(phase * TWO_PI)
        AssistantState.LISTENING -> 0.04f * sin(phase * TWO_PI) + level * 0.14f
        AssistantState.THINKING -> 0.03f * sin(phase * TWO_PI * 2)
        AssistantState.EXECUTING -> 0.05f * sin(phase * TWO_PI * 3)
        AssistantState.SPEAKING -> 0.09f * sin(phase * TWO_PI)
        AssistantState.AWAITING_INPUT -> 0.03f * sin(phase * TWO_PI)
        AssistantState.ERROR -> 0f
    }

    val radius = baseRadius * (0.62f + breath)

    // Outer halo. Radial gradients are cheap here and give the orb the sense of
    // emitting light rather than being a filled circle.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.28f), Color.Transparent),
            center = centre,
            radius = baseRadius,
        ),
        radius = baseRadius,
        center = centre,
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.9f), tint.copy(alpha = 0.35f)),
            center = centre.copy(y = centre.y - radius * 0.3f),
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )

    // A rotating arc reads as "working" in a way a pulse does not.
    if (state == AssistantState.THINKING) {
        val sweepRadius = radius * 1.25f
        drawArc(
            color = tint.copy(alpha = 0.85f),
            startAngle = phase * 360f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(centre.x - sweepRadius, centre.y - sweepRadius),
            size = Size(sweepRadius * 2, sweepRadius * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.06f),
        )
    }

    // While listening, ticks around the rim rise with the level — visible proof
    // that the microphone is picking the user up.
    if (state == AssistantState.LISTENING) {
        val ringRadius = radius * 1.2f
        repeat(TICK_COUNT) { index ->
            val angle = (index.toFloat() / TICK_COUNT) * TWO_PI
            val wobble = 0.5f + 0.5f * sin(phase * TWO_PI * 2 + index)
            val length = radius * (0.06f + level * 0.24f * wobble)

            val inner = Offset(
                centre.x + cos(angle) * ringRadius,
                centre.y + sin(angle) * ringRadius,
            )
            val outer = Offset(
                centre.x + cos(angle) * (ringRadius + length),
                centre.y + sin(angle) * (ringRadius + length),
            )

            drawLine(
                color = tint.copy(alpha = 0.5f + level * 0.4f),
                start = inner,
                end = outer,
                strokeWidth = radius * 0.03f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/** How long one animation cycle lasts, per state. Faster reads as busier. */
private fun AssistantState.cycleMillis(): Int = when (this) {
    AssistantState.IDLE -> 4_000
    AssistantState.LISTENING -> 2_200
    AssistantState.THINKING -> 1_400
    AssistantState.EXECUTING -> 900
    AssistantState.SPEAKING -> 1_800
    AssistantState.AWAITING_INPUT -> 3_000
    AssistantState.ERROR -> 6_000
}

private const val TWO_PI = (2 * Math.PI).toFloat()
private const val TICK_COUNT = 48
