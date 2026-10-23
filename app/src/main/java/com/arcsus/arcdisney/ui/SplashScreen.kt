package com.arcsus.arcdisney.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.arcsus.arcdisney.R
import com.arcsus.arcdisney.ui.theme.Night
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The Disney wordmark on night blue, with an arc of light drawing itself over
 * it. [animate] false shows the finished frame (for screenshots).
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier, animate: Boolean = true) {
    val logo = remember { Animatable(if (animate) 0f else 1f) }
    val arc = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        launch { logo.animateTo(1f, tween(900, easing = Ease)) }
        arc.animateTo(1f, tween(1300, delayMillis = 250, easing = Ease))
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Night.Sky, 0.55f to Night.Deep, 1f to Color(0xFF03050F))),
        contentAlignment = Alignment.Center,
    ) {
        // Soft light behind the logo, rising with it.
        Box(
            Modifier
                .size(420.dp)
                .alpha(0.55f * logo.value)
                .background(Brush.radialGradient(listOf(Night.Glow.copy(alpha = 0.45f), Color.Transparent))),
        )
        // Centred on the logo (220 x 92 dp): the arc's crown sits 30 dp above
        // the wordmark and its ends come down level with its middle.
        Canvas(Modifier.size(width = 300.dp, height = 200.dp)) {
            val stroke = 2.2.dp.toPx()
            val oval = Size(size.width, 216.dp.toPx())
            val topLeft = Offset(0f, 24.dp.toPx())
            val start = 200f
            val sweep = 140f * arc.value
            drawArc(
                brush = Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.85f), Color.White),
                ),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = oval,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // The arc's bright head.
            if (arc.value > 0f) {
                val angle = Math.toRadians((start + sweep).toDouble())
                val cx = topLeft.x + oval.width / 2 + (oval.width / 2) * cos(angle).toFloat()
                val cy = topLeft.y + oval.height / 2 + (oval.height / 2) * sin(angle).toFloat()
                val fade = 1f - ((arc.value - 0.85f) / 0.15f).coerceIn(0f, 1f) * 0.6f
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.9f * fade), Night.Glow.copy(alpha = 0.35f * fade), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = 18.dp.toPx(),
                    ),
                    radius = 18.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
        }
        Image(
            painter = painterResource(R.drawable.logo_disney),
            contentDescription = "Disney",
            modifier = Modifier
                .width(220.dp)
                .alpha(logo.value)
                .scale(0.92f + 0.08f * logo.value),
        )
    }
}
