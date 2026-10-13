package com.goldenpaw.ui.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * # GoldenPaw motion specifications: the single source of truth
 *
 * Every enter/exit transition and content swap used by a screen comes from here. Screens pick a
 * token (and a preset where the token has variants); they never build `slideIn…`, `scaleIn`,
 * `expand…` or `fadeIn(tween(…))` themselves. Durations, easings and springs come from [Motion].
 *
 * | Token | Use it for | Normal motion | Reduced motion |
 * |---|---|---|---|
 * | [pressScale] (Motion.kt), [tabIconPop] | quick interaction feedback | spring scale | no scale (fade only for the icon swap) |
 * | [fadeThrough] | content that replaces content in place | fade out, fade in | 120 ms fade |
 * | [axisX] | spatial navigation: screens, tabs, wizard steps | shared-axis X slide + fade | 120 ms fade |
 * | [roll] | values that count up/down, button labels | vertical roll + fade | 120 ms fade |
 * | [expand] / [collapse] | expand/collapse sections and inline details | size + fade | 120 ms fade, no size change |
 * | [overlayEnter] / [overlayExit], [scrimEnter] / [scrimExit], [popoverEnter], [splashExit], [barEnter] / [barExit] | full-screen overlays, coach-mark scrim and bubble, splash, bottom chrome | fade / scale / slide | 120 ms fade |
 * | [successPop], [contentDefault] | success feedback (dose given, item logged) and small state swaps | spring pop | 120 ms fade, size snaps |
 * | [errorShake] | error feedback on a field or card | short horizontal shake | no movement |
 *
 * Material components (ModalBottomSheet, AlertDialog, DatePickerDialog, snackbars) keep Material's
 * built-in motion: this Compose Multiplatform version exposes no entrance spec to override for
 * them. Bespoke choreography (splash sequence, charts, care rings, particles, pager parallax) is
 * described in the component that owns it and follows the same reduced-motion rules.
 *
 * Every function takes `reduced` explicitly so it can be unit-tested without composition; the
 * `@Composable` shorthands read [LocalReduceMotion].
 */
object MotionSpecs {

    // ------------------------------------------------------------------ fade through

    /** Fade-through timings. */
    @Immutable
    data class FadePreset(val enterMs: Int, val exitMs: Int, val enterDelayMs: Int = 0) {
        companion object {
            /** Small in-place swaps (button labels). */
            val Quick = FadePreset(enterMs = 150, exitMs = 150)
            /** A card changing stage (care-team sharing). */
            val Stage = FadePreset(enterMs = 220, exitMs = 120)
            /** Whole-screen replacement (onboarding → app, delete-all). */
            val Screen = FadePreset(enterMs = Motion.MEDIUM4, exitMs = Motion.SHORT4)
        }
    }

    fun fadeThrough(reduced: Boolean, preset: FadePreset): ContentTransform =
        if (reduced) ReducedMotion.content(true, ContentTransform(ReducedMotion.fadeEnter(), ReducedMotion.fadeExit()))
        else fadeIn(tween(preset.enterMs, delayMillis = preset.enterDelayMs)) togetherWith fadeOut(tween(preset.exitMs))

    // ------------------------------------------------------------------ shared axis X

    /**
     * Shared-axis X timings. Offsets are `full / divisor` (integer division, matching the values the
     * app used before this file existed). With `forward`, the new content comes from the end side.
     */
    @Immutable
    data class AxisPreset(
        val enterDivisor: Int,
        val exitDivisor: Int,
        val enterMs: Int,
        val enterEasing: Easing,
        val enterFadeMs: Int,
        val exitMs: Int,
        val exitEasing: Easing,
        val exitFadeMs: Int,
        val enterFadeDelayMs: Int = 0,
        val enterFadeEasing: Easing = FastOutSlowInEasing,
    ) {
        fun enterOffset(full: Int, forward: Boolean): Int = if (forward) full / enterDivisor else -(full / enterDivisor)
        fun exitOffset(full: Int, forward: Boolean): Int = if (forward) -(full / exitDivisor) else full / exitDivisor

        companion object {
            /** Pushing a screen. */
            val NavPush = AxisPreset(
                enterDivisor = 4, exitDivisor = 10,
                enterMs = Motion.MEDIUM4, enterEasing = Motion.EmphasizedDecelerate, enterFadeMs = Motion.MEDIUM2, enterFadeDelayMs = 50,
                exitMs = Motion.MEDIUM2, exitEasing = Motion.EmphasizedAccelerate, exitFadeMs = Motion.SHORT4,
            )
            /** Popping a screen (use with `forward = false`). */
            val NavPop = AxisPreset(
                enterDivisor = 10, exitDivisor = 4,
                enterMs = Motion.MEDIUM4, enterEasing = Motion.EmphasizedDecelerate, enterFadeMs = Motion.MEDIUM2,
                exitMs = Motion.MEDIUM2, exitEasing = Motion.EmphasizedAccelerate, exitFadeMs = Motion.SHORT4,
            )
            /** Bottom-bar tab switch: the new tab slides in from its side of the bar. */
            val NavTab = AxisPreset(
                enterDivisor = 6, exitDivisor = 8,
                enterMs = Motion.MEDIUM2, enterEasing = Motion.EmphasizedDecelerate,
                enterFadeMs = Motion.MEDIUM1, enterFadeDelayMs = 60, enterFadeEasing = Motion.EmphasizedDecelerate,
                exitMs = Motion.MEDIUM1, exitEasing = Motion.EmphasizedAccelerate, exitFadeMs = Motion.SHORT2,
            )
            /** Steps of a wizard (medication and pet editors). */
            val Step = AxisPreset(
                enterDivisor = 4, exitDivisor = 4,
                enterMs = Motion.MEDIUM2, enterEasing = Motion.EmphasizedDecelerate, enterFadeMs = Motion.MEDIUM2,
                exitMs = Motion.SHORT4, exitEasing = Motion.EmphasizedAccelerate, exitFadeMs = Motion.SHORT2,
            )
            /** In-screen tabs (Insights): same rhythm as [Step], half the distance. */
            val InScreenTab = Step.copy(enterDivisor = 8, exitDivisor = 8)
            /** Record scanning steps. */
            val ScanStep = AxisPreset(
                enterDivisor = 5, exitDivisor = 8,
                enterMs = Motion.MEDIUM2, enterEasing = Motion.EmphasizedDecelerate, enterFadeMs = Motion.MEDIUM1,
                exitMs = Motion.MEDIUM1, exitEasing = FastOutSlowInEasing, exitFadeMs = Motion.SHORT2,
            )
        }
    }

    fun axisX(reduced: Boolean, preset: AxisPreset, forward: Boolean): ContentTransform {
        if (reduced) return reducedContent()
        val enter = slideInHorizontally(tween(preset.enterMs, easing = preset.enterEasing)) { preset.enterOffset(it, forward) } +
            fadeIn(tween(preset.enterFadeMs, delayMillis = preset.enterFadeDelayMs, easing = preset.enterFadeEasing))
        val exit = slideOutHorizontally(tween(preset.exitMs, easing = preset.exitEasing)) { preset.exitOffset(it, forward) } +
            fadeOut(tween(preset.exitFadeMs))
        return enter togetherWith exit
    }

    /** Popping with Android predictive back: the screen already moved with the finger, so just settle. */
    fun predictivePop(reduced: Boolean): ContentTransform =
        if (reduced) reducedContent()
        else fadeIn(tween(Motion.MEDIUM2)) togetherWith (fadeOut(tween(Motion.SHORT4)) + scaleOut(tween(Motion.MEDIUM2), targetScale = 0.85f))

    // ------------------------------------------------------------------ vertical roll

    @Immutable
    data class RollPreset(val divisor: Int, val fastExit: Boolean, val clipSize: Boolean) {
        companion object {
            /** Counters: a full-height roll; digits may overflow while rolling. */
            val Counter = RollPreset(divisor = 1, fastExit = false, clipSize = false)
            /** Button labels: half-height roll. */
            val Label = RollPreset(divisor = 2, fastExit = true, clipSize = true)
        }
    }

    fun roll(reduced: Boolean, preset: RollPreset, up: Boolean): ContentTransform {
        if (reduced) return reducedContent()
        val sign = if (up) 1 else -1
        val enter = slideInVertically(Motion.spatialDefault()) { sign * it / preset.divisor } + fadeIn(tween(Motion.SHORT4))
        val exitSpring: FiniteAnimationSpec<IntOffset> = if (preset.fastExit) Motion.spatialFast() else Motion.spatialDefault()
        val exit = slideOutVertically(exitSpring) { -sign * it / preset.divisor } + fadeOut(tween(Motion.SHORT2))
        return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = preset.clipSize))
    }

    // ------------------------------------------------------------------ expand / collapse

    enum class ExpandPreset {
        /** Details growing inside a card or column (default spring, vertical). */
        Inline,
        /** A whole section opening (spatial springs). */
        Section,
        /** A field appearing in a form (expands from the corner, Compose's un-scoped default). */
        Field,
    }

    fun expand(reduced: Boolean, preset: ExpandPreset): EnterTransition = when {
        reduced -> ReducedMotion.fadeEnter()
        preset == ExpandPreset.Inline -> fadeIn() + expandVertically()
        preset == ExpandPreset.Section -> expandVertically(Motion.spatialDefault()) + fadeIn()
        else -> fadeIn() + expandIn()
    }

    fun collapse(reduced: Boolean, preset: ExpandPreset): ExitTransition = when {
        reduced -> ReducedMotion.fadeExit()
        preset == ExpandPreset.Inline -> fadeOut() + shrinkVertically()
        preset == ExpandPreset.Section -> shrinkVertically(Motion.spatialFast()) + fadeOut()
        else -> shrinkOut() + fadeOut()
    }

    // ------------------------------------------------------------------ overlays, chrome, splash

    /** Full-screen overlays drawn by the app (the welcome tour). */
    fun overlayEnter(reduced: Boolean): EnterTransition = if (reduced) ReducedMotion.fadeEnter() else fadeIn(tween(Motion.MEDIUM2))
    fun overlayExit(reduced: Boolean): ExitTransition = if (reduced) ReducedMotion.fadeExit() else fadeOut(tween(Motion.MEDIUM2))

    /** The dimmed scrim behind a coach mark. */
    fun scrimEnter(reduced: Boolean): EnterTransition = if (reduced) ReducedMotion.fadeEnter() else fadeIn(tween(220))
    fun scrimExit(reduced: Boolean): ExitTransition = if (reduced) ReducedMotion.fadeExit() else fadeOut(tween(160))

    /** A small surface popping out of an anchor (coach-mark bubble). */
    fun popoverEnter(reduced: Boolean): EnterTransition = if (reduced) ReducedMotion.fadeEnter() else scaleIn(initialScale = 0.9f) + fadeIn()

    /** The splash leaving: fade with a slight push towards the viewer. */
    fun splashExit(reduced: Boolean): ExitTransition =
        if (reduced) ReducedMotion.fadeExit()
        else fadeOut(tween(Motion.MEDIUM2, easing = Motion.EmphasizedAccelerate)) +
            scaleOut(tween(Motion.MEDIUM4, easing = Motion.EmphasizedDecelerate), targetScale = 1.08f)

    /** Bottom-anchored chrome (the floating nav bar) sliding in from / out to the bottom edge. */
    fun barEnter(reduced: Boolean): EnterTransition =
        if (reduced) ReducedMotion.fadeEnter() else slideInVertically(Motion.spatialDefault()) { it } + fadeIn()
    fun barExit(reduced: Boolean): ExitTransition =
        if (reduced) ReducedMotion.fadeExit() else slideOutVertically(Motion.spatialFast()) { it } + fadeOut()

    // ------------------------------------------------------------------ feedback

    /** Bottom-bar icon swapping outlined → filled with a small springy pop. */
    fun tabIconPop(reduced: Boolean): ContentTransform =
        if (reduced) reducedContent()
        else (scaleIn(spring(dampingRatio = 0.45f, stiffness = 700f), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
            (scaleOut(tween(100), targetScale = 0.8f) + fadeOut(tween(100)))

    enum class SuccessPreset {
        /** A control morphing into a check mark (dose given). Bouncy. */
        Check,
        /** A small confirmation chip appearing ("Logged"). */
        Chip,
    }

    fun successPop(reduced: Boolean, preset: SuccessPreset): ContentTransform = when {
        reduced -> reducedContent()
        preset == SuccessPreset.Check ->
            (scaleIn(Motion.bouncy(), initialScale = 0.3f) + fadeIn(tween(Motion.SHORT4))) togetherWith
                (scaleOut(tween(Motion.SHORT4), targetScale = 0.4f) + fadeOut(tween(Motion.SHORT2)))
        else -> (fadeIn(tween(200)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120))
    }

    /** `AnimatedContent`'s own default, for small state swaps that should keep it. */
    fun contentDefault(reduced: Boolean): ContentTransform =
        if (reduced) reducedContent()
        else (fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90))) togetherWith
            fadeOut(tween(90))

    /** Error nudge: horizontal offsets (dp) at times (ms), linearly interpolated over [ERROR_SHAKE_MS]. */
    const val ERROR_SHAKE_MS = 400
    val errorShakeKeyframes: List<Pair<Int, Float>> = listOf(0 to 0f, 50 to -8f, 120 to 8f, 190 to -6f, 260 to 6f, 330 to -3f, 400 to 0f)

    /** Offset in dp at [timeMs] into the shake. Pure, so it is unit-tested. */
    fun errorShakeOffsetAt(timeMs: Float): Float {
        val frames = errorShakeKeyframes
        if (timeMs <= frames.first().first) return frames.first().second
        for (i in 1 until frames.size) {
            val (t1, v1) = frames[i]
            if (timeMs <= t1) {
                val (t0, v0) = frames[i - 1]
                return v0 + (v1 - v0) * (timeMs - t0) / (t1 - t0)
            }
        }
        return frames.last().second
    }

    private fun reducedContent(): ContentTransform = ReducedMotion.content(true, ContentTransform(ReducedMotion.fadeEnter(), ReducedMotion.fadeExit()))
}

// ------------------------------------------------------------------ composable shorthands

@Composable
fun expandTransition(preset: MotionSpecs.ExpandPreset): EnterTransition = MotionSpecs.expand(LocalReduceMotion.current, preset)

@Composable
fun collapseTransition(preset: MotionSpecs.ExpandPreset): ExitTransition = MotionSpecs.collapse(LocalReduceMotion.current, preset)

/**
 * Shakes this element horizontally whenever [trigger] changes to a new non-null value (e.g. an
 * error counter or message). No movement under reduced motion. Read in the graphics layer only.
 */
fun Modifier.errorShake(trigger: Any?): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val time = remember { Animatable(MotionSpecs.ERROR_SHAKE_MS.toFloat()) }
    LaunchedEffect(trigger, reduce) {
        if (trigger == null || reduce) {
            time.snapTo(MotionSpecs.ERROR_SHAKE_MS.toFloat())
            return@LaunchedEffect
        }
        time.snapTo(0f)
        time.animateTo(MotionSpecs.ERROR_SHAKE_MS.toFloat(), tween(MotionSpecs.ERROR_SHAKE_MS, easing = LinearEasing))
    }
    graphicsLayer { translationX = MotionSpecs.errorShakeOffsetAt(time.value) * density }
}
