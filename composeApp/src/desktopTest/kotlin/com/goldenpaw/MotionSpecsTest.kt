package com.goldenpaw

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.MotionSpecs
import com.goldenpaw.ui.designsystem.MotionSpecs.AxisPreset
import com.goldenpaw.ui.designsystem.MotionSpecs.ExpandPreset
import com.goldenpaw.ui.designsystem.MotionSpecs.FadePreset
import com.goldenpaw.ui.designsystem.MotionSpecs.RollPreset
import com.goldenpaw.ui.designsystem.MotionSpecs.SuccessPreset
import com.goldenpaw.ui.designsystem.ReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks the motion tokens to the values the screens used before they were centralised, and checks
 * every token's reduced-motion variant. Slide/expand specs hold lambdas and can't be compared by
 * value, so offsets are checked through the presets' own offset functions.
 */
class MotionSpecsTest {

    private val fade = fadeIn(tween(ReducedMotion.REDUCED_FADE_MS))
    private val fadeOutReduced = fadeOut(tween(ReducedMotion.REDUCED_FADE_MS))
    private val w = 1080

    private fun assertReduced(t: ContentTransform) {
        assertEquals(fade, t.targetContentEnter)
        assertEquals(fadeOutReduced, t.initialContentExit)
        assertTrue(t.sizeTransform!!.clip) // size snaps (no size animation)
    }

    // ---- shared axis X: offsets equal the old inline formulas

    @Test
    fun navPushAndPopOffsets() {
        // Old: push enter it/4, exit -it/10; pop enter -it/10, exit it/4.
        assertEquals(w / 4, AxisPreset.NavPush.enterOffset(w, forward = true))
        assertEquals(-w / 10, AxisPreset.NavPush.exitOffset(w, forward = true))
        assertEquals(-w / 10, AxisPreset.NavPop.enterOffset(w, forward = false))
        assertEquals(w / 4, AxisPreset.NavPop.exitOffset(w, forward = false))
    }

    @Test
    fun tabAndStepOffsetsInBothDirections() {
        for (dir in listOf(1, -1)) {
            val forward = dir > 0
            assertEquals(dir * w / 6, AxisPreset.NavTab.enterOffset(w, forward))
            assertEquals(-dir * w / 8, AxisPreset.NavTab.exitOffset(w, forward))
            assertEquals(w / 4 * dir, AxisPreset.Step.enterOffset(w, forward))
            assertEquals(-w / 4 * dir, AxisPreset.Step.exitOffset(w, forward))
            assertEquals(w / 8 * dir, AxisPreset.InScreenTab.enterOffset(w, forward))
            assertEquals(-w / 8 * dir, AxisPreset.InScreenTab.exitOffset(w, forward))
            assertEquals(if (forward) w / 5 else -w / 5, AxisPreset.ScanStep.enterOffset(w, forward))
            assertEquals(if (forward) -w / 8 else w / 8, AxisPreset.ScanStep.exitOffset(w, forward))
        }
        // Odd widths: integer division rounds the same way as before in both directions.
        assertEquals(-(1081 / 6), AxisPreset.NavTab.enterOffset(1081, forward = false))
        assertEquals(-1 * 1081 / 6, AxisPreset.NavTab.enterOffset(1081, forward = false))
    }

    @Test
    fun axisTimingsMatchThePreviousSpecs() {
        with(AxisPreset.NavPush) {
            assertEquals(listOf(Motion.MEDIUM4, Motion.MEDIUM2, 50, Motion.MEDIUM2, Motion.SHORT4), listOf(enterMs, enterFadeMs, enterFadeDelayMs, exitMs, exitFadeMs))
            assertEquals(Motion.EmphasizedDecelerate, enterEasing)
            assertEquals(Motion.EmphasizedAccelerate, exitEasing)
        }
        with(AxisPreset.NavPop) {
            assertEquals(listOf(Motion.MEDIUM4, Motion.MEDIUM2, 0, Motion.MEDIUM2, Motion.SHORT4), listOf(enterMs, enterFadeMs, enterFadeDelayMs, exitMs, exitFadeMs))
        }
        with(AxisPreset.NavTab) {
            assertEquals(listOf(Motion.MEDIUM2, Motion.MEDIUM1, 60, Motion.MEDIUM1, Motion.SHORT2), listOf(enterMs, enterFadeMs, enterFadeDelayMs, exitMs, exitFadeMs))
            assertEquals(Motion.EmphasizedDecelerate, enterFadeEasing)
        }
        with(AxisPreset.Step) {
            assertEquals(listOf(Motion.MEDIUM2, Motion.MEDIUM2, Motion.SHORT4, Motion.SHORT2), listOf(enterMs, enterFadeMs, exitMs, exitFadeMs))
            assertEquals(FastOutSlowInEasing, enterFadeEasing)
        }
        assertEquals(AxisPreset.Step.copy(enterDivisor = 8, exitDivisor = 8), AxisPreset.InScreenTab)
        with(AxisPreset.ScanStep) {
            assertEquals(listOf(Motion.MEDIUM2, Motion.MEDIUM1, Motion.MEDIUM1, Motion.SHORT2), listOf(enterMs, enterFadeMs, exitMs, exitFadeMs))
            assertEquals(FastOutSlowInEasing, exitEasing) // tween()'s default, as before
        }
    }

    @Test
    fun everyAxisPresetIsAFadeWhenReduced() {
        listOf(AxisPreset.NavPush, AxisPreset.NavPop, AxisPreset.NavTab, AxisPreset.Step, AxisPreset.InScreenTab, AxisPreset.ScanStep)
            .forEach { p -> listOf(true, false).forEach { assertReduced(MotionSpecs.axisX(true, p, it)) } }
        assertReduced(MotionSpecs.predictivePop(true))
        // ...and movement when allowed.
        assertNotEquals(fade, MotionSpecs.axisX(false, AxisPreset.Step, true).targetContentEnter)
    }

    // ---- fade through

    @Test
    fun fadeThroughKeepsItsTimings() {
        val stage = MotionSpecs.fadeThrough(false, FadePreset.Stage)
        assertEquals(fadeIn(tween(220)), stage.targetContentEnter)
        assertEquals(fadeOut(tween(120)), stage.initialContentExit)
        val quick = MotionSpecs.fadeThrough(false, FadePreset.Quick)
        assertEquals(fadeIn(tween(150)), quick.targetContentEnter)
        val screen = MotionSpecs.fadeThrough(false, FadePreset.Screen)
        assertEquals(fadeIn(tween(Motion.MEDIUM4)), screen.targetContentEnter)
        assertEquals(fadeOut(tween(Motion.SHORT4)), screen.initialContentExit)
        FadePreset.run { listOf(Quick, Stage, Screen) }.forEach { assertReduced(MotionSpecs.fadeThrough(true, it)) }
    }

    // ---- roll, success, feedback, content default

    @Test
    fun rollPresets() {
        assertFalse(MotionSpecs.roll(false, RollPreset.Counter, up = true).sizeTransform!!.clip) // digits may overflow
        assertTrue(MotionSpecs.roll(false, RollPreset.Label, up = true).sizeTransform!!.clip)
        listOf(RollPreset.Counter, RollPreset.Label).forEach { assertReduced(MotionSpecs.roll(true, it, up = false)) }
    }

    @Test
    fun successAndFeedbackTokens() {
        val chip = MotionSpecs.successPop(false, SuccessPreset.Chip)
        assertEquals(fadeIn(tween(200)) + scaleIn(initialScale = 0.8f), chip.targetContentEnter)
        assertEquals(fadeOut(tween(120)), chip.initialContentExit)
        SuccessPreset.entries.forEach { assertReduced(MotionSpecs.successPop(true, it)) }
        assertReduced(MotionSpecs.tabIconPop(true))
        assertReduced(MotionSpecs.contentDefault(true))
        val d = MotionSpecs.contentDefault(false)
        assertEquals(
            fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)),
            d.targetContentEnter,
        )
        assertEquals(fadeOut(tween(90)), d.initialContentExit)
    }

    // ---- expand / collapse, overlays

    @Test
    fun enterExitTokensAreFadesWhenReduced() {
        ExpandPreset.entries.forEach {
            assertEquals(fade, MotionSpecs.expand(true, it))
            assertEquals(fadeOutReduced, MotionSpecs.collapse(true, it))
            assertNotEquals(fade, MotionSpecs.expand(false, it))
        }
        listOf(MotionSpecs.overlayEnter(true), MotionSpecs.scrimEnter(true), MotionSpecs.popoverEnter(true), MotionSpecs.barEnter(true))
            .forEach { assertEquals(fade, it) }
        listOf(MotionSpecs.overlayExit(true), MotionSpecs.scrimExit(true), MotionSpecs.splashExit(true), MotionSpecs.barExit(true))
            .forEach { assertEquals(fadeOutReduced, it) }
    }

    @Test
    fun overlayTimingsMatchThePreviousSpecs() {
        assertEquals(fadeIn(tween(Motion.MEDIUM2)), MotionSpecs.overlayEnter(false))
        assertEquals(fadeOut(tween(Motion.MEDIUM2)), MotionSpecs.overlayExit(false))
        assertEquals(fadeIn(tween(220)), MotionSpecs.scrimEnter(false))
        assertEquals(fadeOut(tween(160)), MotionSpecs.scrimExit(false))
        assertEquals(scaleIn(initialScale = 0.9f) + fadeIn(), MotionSpecs.popoverEnter(false))
    }

    // ---- error shake

    @Test
    fun errorShakeStartsAndEndsAtRestAndInterpolates() {
        assertEquals(0f, MotionSpecs.errorShakeOffsetAt(0f))
        assertEquals(0f, MotionSpecs.errorShakeOffsetAt(MotionSpecs.ERROR_SHAKE_MS.toFloat()))
        assertEquals(0f, MotionSpecs.errorShakeOffsetAt(10_000f))
        assertEquals(-8f, MotionSpecs.errorShakeOffsetAt(50f))
        assertEquals(0f, MotionSpecs.errorShakeOffsetAt(85f), 0.001f) // halfway between -8 and 8
        assertTrue(MotionSpecs.errorShakeKeyframes.all { kotlin.math.abs(it.second) <= 8f })
    }
}
