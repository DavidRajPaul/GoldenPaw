package com.goldenpaw.ui.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** Central motion specs. Every animated component reads [LocalReduceMotion]. */
object MotionSpecs {
    const val SHORT = 180
    const val MEDIUM = 320
    const val LONG = 900

    fun <T> gentleSpring(): AnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    fun <T> settleSpring(): AnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = 120f)

    fun <T> emphasized(duration: Int = MEDIUM): AnimationSpec<T> = tween(duration, easing = FastOutSlowInEasing)
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
