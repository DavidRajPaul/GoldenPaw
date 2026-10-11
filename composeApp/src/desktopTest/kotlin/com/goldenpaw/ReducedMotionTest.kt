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
    fun scopedDefaultsMatchComposeDefaults() {
        // AnimatedVisibility's documented defaults; call sites pass these so normal motion is unchanged.
        assertEquals(fadeIn() + expandVertically(), ReducedMotion.columnEnter)
        assertEquals(fadeOut() + shrinkVertically(), ReducedMotion.columnExit)
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
    fun defaultContentIsComposesDefault() {
        val d = ReducedMotion.defaultContent()
        assertEquals(
            fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)),
            d.targetContentEnter,
        )
        assertEquals(fadeOut(tween(90)), d.initialContentExit)
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
