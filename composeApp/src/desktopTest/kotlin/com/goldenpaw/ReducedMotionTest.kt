package com.goldenpaw

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.ReducedMotion
import com.goldenpaw.ui.designsystem.rememberAmbientTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReducedMotionSpecTest {

    private val fade = fadeIn(tween(ReducedMotion.REDUCED_FADE_MS))
    private val fadeOutReduced = fadeOut(tween(ReducedMotion.REDUCED_FADE_MS))

    @Test
    fun enterAndExitAreUnchangedWhenMotionIsAllowed() {
        val enter = slideInVertically { it } + fadeIn()
        val exit = fadeOut() + shrinkVertically()
        assertSame(enter, ReducedMotion.enter(reduced = false, normal = enter))
        assertSame(exit, ReducedMotion.exit(reduced = false, normal = exit))
    }

    @Test
    fun reducedEnterAndExitAreAShortFadeOnly() {
        assertEquals(fade, ReducedMotion.enter(reduced = true, normal = slideInVertically { it } + fadeIn()))
        assertEquals(fade, ReducedMotion.enter(reduced = true, normal = fadeIn() + expandVertically()))
        assertEquals(fadeOutReduced, ReducedMotion.exit(reduced = true, normal = fadeOut() + shrinkVertically()))
        // A fade is not a slide: the reduced spec really differs from the normal one.
        assertNotEquals(fadeIn() + expandVertically(), ReducedMotion.enter(true, fadeIn() + expandVertically()))
    }

    @Test
    fun contentSwapCrossfadesAndSnapsSizeWhenReduced() {
        val normal = (fadeIn(tween(200)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120))
        assertSame(normal, ReducedMotion.content(reduced = false, normal = normal))

        val reduced = ReducedMotion.content(reduced = true, normal = normal)
        assertEquals(fade, reduced.targetContentEnter)
        assertEquals(fadeOutReduced, reduced.initialContentExit)
        assertNotNull(reduced.sizeTransform)
        assertTrue(reduced.sizeTransform!!.clip)
    }

    @Test
    fun ambientMotionPolicy() {
        assertTrue(ReducedMotion.allowsAmbient(reduced = false))
        assertFalse(ReducedMotion.allowsAmbient(reduced = true))
    }
}

/**
 * Proves infinite transitions are never created under reduced motion, by running a real (headless)
 * composition. Uses only compose-runtime, which the app already depends on.
 */
class AmbientTransitionGatingTest {

    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private fun <T> composeOnce(reduced: Boolean, content: @Composable () -> T): T {
        val job = Job()
        val recomposer = Recomposer(BroadcastFrameClock() + job + Dispatchers.Unconfined)
        val composition = Composition(NoNodes, recomposer)
        var result: Result<T>? = null
        try {
            composition.setContent {
                CompositionLocalProvider(LocalReduceMotion provides reduced) {
                    result = Result.success(content())
                }
            }
            return result!!.getOrThrow()
        } finally {
            composition.dispose()
            recomposer.cancel()
            job.cancel()
        }
    }

    @Test
    fun noInfiniteTransitionUnderReducedMotion() {
        val transition: InfiniteTransition? = composeOnce(reduced = true) { rememberAmbientTransition("test") }
        assertNull(transition)
    }

    @Test
    fun infiniteTransitionWhenMotionIsAllowed() {
        val transition: InfiniteTransition? = composeOnce(reduced = false) { rememberAmbientTransition("test") }
        assertNotNull(transition)
        assertEquals("test", transition.label)
    }
}

class AmbientMotionTest {
    private fun total(legs: List<Pair<Float, Int>>) = legs.sumOf { it.second }

    @Test
    fun everyAmbientLoopEndsAtRestWithinFiveSeconds() {
        // The app's three ambient swells: wellness ring, empty-state icon, memorial candle.
        val cases = listOf(
            com.goldenpaw.ui.designsystem.AmbientMotion.legs(0.985f, 1.015f, 1f, 2600),
            com.goldenpaw.ui.designsystem.AmbientMotion.legs(-4f, 4f, 0f, 2200),
            com.goldenpaw.ui.designsystem.AmbientMotion.legs(0.35f, 0.65f, 0.5f, 3200),
        )
        cases.forEach { legs ->
            assertTrue(legs.isNotEmpty(), "plays at least one swell")
            assertTrue(total(legs) <= com.goldenpaw.ui.designsystem.AmbientMotion.MAX_MS, "stops within 5 s: ${total(legs)}")
        }
        assertEquals(1f, cases[0].last().first)
        assertEquals(0f, cases[1].last().first)
        assertEquals(0.5f, cases[2].last().first)
    }

    @Test
    fun emptyStateFloatSwingsBothWaysBeforeResting() {
        val legs = com.goldenpaw.ui.designsystem.AmbientMotion.legs(-4f, 4f, 0f, 2200)
        assertEquals(listOf(4f to 1100, -4f to 2200, 0f to 1100), legs)
    }

    @Test
    fun degenerateInputsDoNothing() {
        assertTrue(com.goldenpaw.ui.designsystem.AmbientMotion.legs(1f, 1f, 1f, 1000).isEmpty())
        // A swing longer than the budget: no legs, so the value simply stays at rest.
        assertTrue(com.goldenpaw.ui.designsystem.AmbientMotion.legs(0f, 1f, 0.5f, 20_000).isEmpty())
    }
}
