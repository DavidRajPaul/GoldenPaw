package com.goldenpaw.ui.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Reduced-motion variants of the transitions used across the app.
 *
 * Rule: with reduced motion on there is no translation, scaling or size animation. A short
 * opacity change ([REDUCED_FADE_MS]) is kept where it helps the eye follow a change. With reduced
 * motion off, every function returns exactly the spec the call site used before.
 *
 * The functions take `reduced` explicitly so they can be unit-tested without composition.
 * Ready-made transitions for screens live in [MotionSpecs]; this file is the policy they share.
 */
object ReducedMotion {
    /** The fade used in place of movement (the value the app already used in most places). */
    const val REDUCED_FADE_MS = 120

    fun fadeEnter(): EnterTransition = fadeIn(tween(REDUCED_FADE_MS))
    fun fadeExit(): ExitTransition = fadeOut(tween(REDUCED_FADE_MS))

    fun enter(reduced: Boolean, normal: EnterTransition): EnterTransition = if (reduced) fadeEnter() else normal
    fun exit(reduced: Boolean, normal: ExitTransition): ExitTransition = if (reduced) fadeExit() else normal

    /** Content swap: crossfade, and the container jumps to its new size instead of animating it. */
    fun content(reduced: Boolean, normal: ContentTransform): ContentTransform =
        if (reduced) ContentTransform(fadeEnter(), fadeExit(), sizeTransform = snapSize()) else normal

    fun snapSize(): SizeTransform = SizeTransform(clip = true) { _, _ -> snap() }

    /** Whether decorative infinite animations may be created at all. */
    fun allowsAmbient(reduced: Boolean): Boolean = !reduced
}

@Composable
fun reducedOr(normal: EnterTransition): EnterTransition = ReducedMotion.enter(LocalReduceMotion.current, normal)

@Composable
fun reducedOr(normal: ExitTransition): ExitTransition = ReducedMotion.exit(LocalReduceMotion.current, normal)

/**
 * `Modifier.animateItem()` for lazy lists that honours reduced motion: items still fade in and out
 * briefly, but they don't slide when the list reorders.
 */
@Composable
fun LazyItemScope.motionAwareAnimateItem(): Modifier =
    if (LocalReduceMotion.current) {
        Modifier.animateItem(
            fadeInSpec = tween(ReducedMotion.REDUCED_FADE_MS),
            placementSpec = null,
            fadeOutSpec = tween(ReducedMotion.REDUCED_FADE_MS),
        )
    } else {
        Modifier.animateItem()
    }

/** Grid variant of [motionAwareAnimateItem]. */
@Composable
fun LazyGridItemScope.motionAwareAnimateItem(): Modifier =
    if (LocalReduceMotion.current) {
        Modifier.animateItem(
            fadeInSpec = tween(ReducedMotion.REDUCED_FADE_MS),
            placementSpec = null,
            fadeOutSpec = tween(ReducedMotion.REDUCED_FADE_MS),
        )
    } else {
        Modifier.animateItem()
    }

/**
 * An infinite transition for decorative ("ambient") motion, or null when reduced motion is on.
 * The transition is never created in that case, so nothing ticks frames in the background.
 * Callers read `transition?.animateFloat(...)` and fall back to a static value.
 */
@Composable
fun rememberAmbientTransition(label: String): InfiniteTransition? =
    if (ReducedMotion.allowsAmbient(LocalReduceMotion.current)) rememberInfiniteTransition(label = label) else null

/**
 * Ambient ("breathing") motion that plays briefly and then rests, instead of looping forever.
 * WCAG 2.2.2 asks that motion running alongside other content stops within five seconds unless the
 * user can pause it; this keeps every decorative loop under [MAX_MS].
 */
object AmbientMotion {
    const val MAX_MS = 5_000

    /**
     * The legs (target value, duration ms) of a swell between [low] and [high] that starts and ends
     * at [rest]. A full low↔high swing takes [halfMs]; shorter swings take proportionally less.
     * Whole legs are added while there is still time to settle back to [rest] within [maxMs].
     */
    fun legs(low: Float, high: Float, rest: Float, halfMs: Int, maxMs: Int = MAX_MS): List<Pair<Float, Int>> {
        val span = kotlin.math.abs(high - low).takeIf { it > 0f } ?: return emptyList()
        fun duration(from: Float, to: Float) = kotlin.math.round(halfMs * kotlin.math.abs(to - from) / span).toInt()
        val out = mutableListOf<Pair<Float, Int>>()
        var elapsed = 0
        var current = rest
        var next = high
        while (true) {
            val leg = duration(current, next)
            if (elapsed + leg + duration(next, rest) > maxMs) break
            out += next to leg
            elapsed += leg
            current = next
            next = if (next == high) low else high
        }
        if (current != rest) out += rest to duration(current, rest)
        return out
    }
}

/**
 * A decorative value that swells between [low] and [high] for at most [AmbientMotion.MAX_MS], then
 * settles at [rest]. Null under reduced motion (nothing is started); callers use [rest] then.
 * Read the returned state in a graphics layer or draw lambda so the swell never recomposes.
 */
@Composable
fun rememberAmbientSwell(
    low: Float,
    high: Float,
    rest: Float,
    halfMs: Int,
    easing: Easing = Motion.Standard,
): State<Float>? {
    if (!ReducedMotion.allowsAmbient(LocalReduceMotion.current)) return null
    val value = remember { Animatable(rest) }
    LaunchedEffect(Unit) {
        for ((target, ms) in AmbientMotion.legs(low, high, rest, halfMs)) {
            value.animateTo(target, tween(ms, easing = easing))
        }
    }
    return value.asState()
}
