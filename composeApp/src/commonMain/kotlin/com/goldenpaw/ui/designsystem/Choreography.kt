package com.goldenpaw.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Paw geometry in a 60-unit box centred on the paw: the main pad and the four toe beans (left to
 * right). Shared by [drawPaw]-style drawing and the splash choreography so the two always match.
 */
private object PawGeometry {
    /** (left, top, width, height) relative to the centre, in 1/60ths of the paw size. */
    val pad = floatArrayOf(-12.5f, 1f, 25f, 21f)
    val toes = listOf(
        floatArrayOf(-20.7f, -19.6f, 10.4f, 13.2f),
        floatArrayOf(-11.7f, -28.8f, 10.4f, 13.6f),
        floatArrayOf(1.3f, -28.8f, 10.4f, 13.6f),
        floatArrayOf(10.3f, -19.6f, 10.4f, 13.2f),
    )
}

private fun DrawScope.drawBean(color: Color, center: Offset, size: Float, part: FloatArray, progress: Float, drop: Float) {
    if (progress <= 0f) return
    val s = size / 60f
    val topLeft = Offset(center.x + part[0] * s, center.y + part[1] * s)
    val beanSize = Size(part[2] * s, part[3] * s)
    val pivot = Offset(topLeft.x + beanSize.width / 2, topLeft.y + beanSize.height / 2)
    // Springs overshoot above 1: that's the bounce. Alpha is clamped, scale and drop are not.
    translate(top = (1f - progress) * -drop) {
        scale(progress.coerceAtLeast(0f), pivot = pivot) {
            drawOval(color.copy(alpha = color.alpha * progress.coerceIn(0f, 1f)), topLeft = topLeft, size = beanSize)
        }
    }
}

/**
 * Launch choreography (~1.7 s):
 *  1. a warm glow blooms behind the mark,
 *  2. the four toe beans drop in one after another with a bounce,
 *  3. the main pad pops in and sends out a soft ripple ring,
 *  4. "GoldenPaw" rises letter by letter, then the tagline fades in.
 * Every value is read in draw / graphics-layer lambdas, so the sequence never recomposes.
 * With Reduce motion it's a single short fade.
 */
@Composable
fun ChoreographedSplash(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val start = if (reduceMotion) 1f else 0f
    val glow = remember { Animatable(start) }
    val toes = remember { List(4) { Animatable(start) } }
    val pad = remember { Animatable(start) }
    val ripple = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val word = remember { Animatable(start) }
    val tagline = remember { Animatable(start) }
    val fade = remember { Animatable(if (reduceMotion) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            fade.animateTo(1f, tween(180))
            delay(320)
            onFinished()
            return@LaunchedEffect
        }
        launch { glow.animateTo(1f, tween(520, easing = Motion.EmphasizedDecelerate)) }
        delay(140)
        toes.forEachIndexed { i, toe ->
            launch {
                delay(i * 85L)
                toe.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
            }
        }
        delay(3 * 85L + 160L)
        launch { pad.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
        launch {
            delay(60)
            ripple.animateTo(1f, tween(720, easing = Motion.EmphasizedDecelerate))
        }
        delay(200)
        word.animateTo(1f, tween(480, easing = LinearEasing))
        tagline.animateTo(1f, tween(240, easing = Motion.EmphasizedDecelerate))
        delay(160)
        onFinished()
    }

    val primary = MaterialTheme.colorScheme.primary
    val glowColor = MaterialTheme.colorScheme.primaryContainer
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.semantics { contentDescription = "GoldenPaw" },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val g = glow.value
                    if (g > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(glowColor.copy(alpha = 0.9f * g), glowColor.copy(alpha = 0f)),
                                center = c,
                                radius = size.minDimension * (0.25f + 0.25f * g),
                            ),
                            radius = size.minDimension * (0.25f + 0.25f * g),
                            center = c,
                        )
                    }
                    val pawSize = size.minDimension * 0.6f
                    val pawCenter = c.copy(y = c.y + pawSize * 0.08f)
                    val drop = pawSize * 0.45f
                    toes.forEachIndexed { i, toe -> drawBean(primary, pawCenter, pawSize, PawGeometry.toes[i], toe.value, drop) }
                    drawBean(primary, pawCenter, pawSize, PawGeometry.pad, pad.value, 0f)
                    val r = ripple.value
                    if (r > 0f && r < 1f) {
                        val s = pawSize / 60f
                        val padCenter = Offset(pawCenter.x, pawCenter.y + 11.5f * s)
                        drawCircle(
                            color = primary.copy(alpha = 0.45f * (1f - r)),
                            radius = 12f * s + r * pawSize * 0.55f,
                            center = padCenter,
                            style = Stroke(width = 3.dp.toPx() * (1f - r) + 1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val letters = "GoldenPaw"
            Row {
                letters.forEachIndexed { i, ch ->
                    Text(
                        ch.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.graphicsLayer {
                            // Letter i starts once the sweep reaches it and takes ~3 letters' time to settle.
                            val p = ((word.value * (letters.length + 3)) - i).div(3f).coerceIn(0f, 1f)
                            alpha = p
                            translationY = (1f - p) * 14.dp.toPx()
                        },
                    )
                }
            }
            Text(
                "Every good day, remembered.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    alpha = tagline.value
                    translationY = (1f - tagline.value) * 6.dp.toPx()
                },
            )
        }
    }
}
