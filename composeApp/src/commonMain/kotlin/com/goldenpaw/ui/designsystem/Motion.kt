package com.goldenpaw.ui.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * GoldenPaw motion system, aligned with Material 3 motion tokens.
 *
 * Performance rules every animated component follows:
 *  - Animated values are read inside `graphicsLayer {}` / draw lambdas, never in composition,
 *    so a running animation only re-draws (no recomposition, no relayout).
 *  - Springs for anything spatial or interruptible (presses, sheets, rings); tweens for fades.
 *  - Everything honours [LocalReduceMotion] (user setting or OS "reduce motion"): fades, no movement.
 */
object Motion {
    // Easing tokens (M3)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    // Duration tokens (ms)
    const val SHORT2 = 100
    const val SHORT4 = 200
    const val MEDIUM1 = 250
    const val MEDIUM2 = 300
    const val MEDIUM4 = 400
    const val LONG2 = 500
    const val LONG4 = 600
    const val EXTRA_LONG2 = 900

    // Legacy names used across the app
    const val SHORT = SHORT4
    const val MEDIUM = MEDIUM2
    const val LONG = EXTRA_LONG2

    // Spring tokens (M3 Expressive "spatial" / "effects")
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> spatialDefault(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 300f)
    fun <T> effectsFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> effectsDefault(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> gentleSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    fun <T> settleSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 120f)

    fun <T> emphasized(duration: Int = MEDIUM4): FiniteAnimationSpec<T> = tween(duration, easing = Emphasized)
    fun <T> enter(duration: Int = MEDIUM4): FiniteAnimationSpec<T> = tween(duration, easing = EmphasizedDecelerate)
    fun <T> exit(duration: Int = SHORT4): FiniteAnimationSpec<T> = tween(duration, easing = EmphasizedAccelerate)
}

/** Picks [normal] unless reduce-motion is on, in which case a short fade-length tween is used. */
@Composable
@ReadOnlyComposable
fun <T> motionOr(normal: AnimationSpec<T>): AnimationSpec<T> =
    if (LocalReduceMotion.current) tween(120) else normal

@Composable
@ReadOnlyComposable
fun <T> motionOrSnap(normal: AnimationSpec<T>): AnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else normal

/**
 * Subtle press feedback: scales to [pressedScale] with a fast spatial spring. The animated value is
 * read in the graphics layer, so pressing never recomposes the content.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = Motion.spatialFast(),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Remembers which list items already played their entrance, so scrolling back doesn't replay it. */
@Stable
class StaggerState {
    internal val seen = mutableSetOf<Any>()
}

@Composable
fun rememberStaggerState(): StaggerState = remember { StaggerState() }

/**
 * Staggered entrance (fade + 24dp rise) for the first appearance of a list item. Delay is capped so
 * long lists never feel slow; reduce-motion skips it entirely.
 */
fun Modifier.staggerIn(state: StaggerState, key: Any, index: Int): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val progress = remember(key) { Animatable(if (reduce || key in state.seen) 1f else 0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay(index.coerceIn(0, 8) * 45L)
            progress.animateTo(1f, tween(Motion.MEDIUM4, easing = Motion.EmphasizedDecelerate))
        }
        state.seen += key
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

/** Skeleton shimmer for loading placeholders (drawn behind, animated in the draw phase only). */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerLowest
    if (LocalReduceMotion.current) {
        return@composed drawWithCache { onDrawBehind { drawRect(base) } }
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x = transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX",
    )
    drawWithCache {
        onDrawBehind {
            val w = size.width
            val brush = Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(w * x.value - w / 2f, 0f),
                end = Offset(w * x.value + w / 2f, size.height),
            )
            drawRect(brush)
        }
    }
}

/** Number that rolls up/down when it changes (points, counts). */
@Composable
fun AnimatedCounter(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    prefix: String = "",
    suffix: String = "",
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(120)) togetherWith fadeOut(tween(120))
            } else if (targetState > initialState) {
                (slideInVertically(Motion.spatialDefault()) { it } + fadeIn(tween(Motion.SHORT4))) togetherWith
                    (slideOutVertically(Motion.spatialDefault()) { -it } + fadeOut(tween(Motion.SHORT2)))
            } else {
                (slideInVertically(Motion.spatialDefault()) { -it } + fadeIn(tween(Motion.SHORT4))) togetherWith
                    (slideOutVertically(Motion.spatialDefault()) { it } + fadeOut(tween(Motion.SHORT2)))
            }.using(SizeTransform(clip = false))
        },
        label = "counter",
        modifier = modifier,
    ) { v ->
        Text("$prefix$v$suffix", style = style)
    }
}
