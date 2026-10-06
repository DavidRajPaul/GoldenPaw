package com.goldenpaw.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.goldenpaw.platform.PlatformBackHandler
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Motion
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Routes

sealed interface Route {
    data object Onboarding : Route
    data object Today : Route
    data object Journal : Route
    data object Insights : Route
    data object Pets : Route
    data object Settings : Route
    data object Reminders : Route
    data object CareTeam : Route
    data object Achievements : Route
    data class PetDetail(val petId: String) : Route
    data class PetEdit(val petId: String? = null) : Route
    data class Memorial(val petId: String) : Route
    data class Meds(val petId: String) : Route
    data class MedEdit(val petId: String, val medId: String? = null) : Route
    data class CheckIn(val petId: String) : Route
    data class SymptomEdit(val petId: String, val entryId: String? = null) : Route
    data class VetVisitEdit(val petId: String, val visitId: String? = null) : Route
}

enum class TopLevel(val route: Route, val label: String, val icon: ImageVector) {
    TODAY(Route.Today, "Today", Icons.Outlined.WbSunny),
    JOURNAL(Route.Journal, "Journal", Icons.Outlined.Book),
    INSIGHTS(Route.Insights, "Insights", Icons.AutoMirrored.Outlined.ShowChart),
    PETS(Route.Pets, "Pets", Icons.Outlined.Pets),
    SETTINGS(Route.Settings, "Settings", Icons.Outlined.Settings),
}

// ------------------------------------------------------------------ Back stack

/** One screen on the back stack. Owns its ViewModels, cleared when the screen leaves for good. */
@Stable
class NavEntry internal constructor(val route: Route, val id: Long) : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /** Predictive back gesture progress (0..1) while this entry is being swiped away. */
    var backProgress by mutableFloatStateOf(0f)
        internal set
    var backFromLeft by mutableStateOf(true)
        internal set
}

enum class NavAction { PUSH, POP, TAB, REPLACE }

/**
 * A small, multiplatform back stack (Navigation 3 style: the stack is plain observable state).
 * Works identically on Android, iOS and Desktop and gives full control over transitions.
 */
@Stable
class Navigator(start: Route) {
    private var nextId = 0L
    private val entries = mutableStateListOf(NavEntry(start, nextId++))

    val backStack: List<NavEntry> get() = entries
    val current: NavEntry get() = entries.last()
    var lastAction by mutableStateOf(NavAction.REPLACE)
        private set

    val canGoBack: Boolean get() = entries.size > 1
    val isTopLevel: Boolean get() = TopLevel.entries.any { it.route == current.route }

    fun navigate(route: Route) {
        if (current.route == route) return
        lastAction = NavAction.PUSH
        entries.add(NavEntry(route, nextId++))
    }

    fun back(): Boolean {
        if (entries.size <= 1) return false
        lastAction = NavAction.POP
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** Bottom-bar navigation: Today is always the root; other tabs sit on top of it. */
    fun switchTab(route: Route) {
        if (current.route == route && entries.size <= 2) return
        lastAction = NavAction.TAB
        val keepCurrent = current
        val removed = entries.filter { it !== keepCurrent && it.route != Route.Today }
        val root = entries.firstOrNull { it.route == Route.Today } ?: NavEntry(Route.Today, nextId++)
        entries.clear()
        entries.add(root)
        if (route != Route.Today) entries.add(if (keepCurrent.route == route) keepCurrent else NavEntry(route, nextId++))
        // Entries that aren't on screen are never composed again: release their ViewModels now.
        removed.filter { it !in entries }.forEach { it.viewModelStore.clear() }
    }

    /** Replaces the whole stack (onboarding → today, delete-all → onboarding). */
    fun resetTo(route: Route) {
        lastAction = NavAction.REPLACE
        val old = entries.toList()
        entries.clear()
        entries.add(NavEntry(route, nextId++))
        old.dropLast(1).forEach { it.viewModelStore.clear() }
    }

    internal fun isOnStack(entry: NavEntry) = entries.any { it === entry }
}

// ------------------------------------------------------------------ App-wide actions

/** App-wide actions screens can trigger without threading callbacks through every layer. */
data class AppActions(
    val navigate: (Route) -> Unit,
    val back: () -> Unit,
    val goHome: () -> Unit,
    val showPaywall: (reason: String) -> Unit,
    val selectPet: (String) -> Unit,
    val canAddPet: () -> Boolean,
    val openQuickLog: () -> Unit,
)

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element hook for the pet photo (list -> detail). No-op with reduce motion. */
@Composable
fun Modifier.sharedPetPhoto(petId: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (LocalReduceMotion.current) return this
    val base = this
    return with(shared) {
        base.sharedElement(
            sharedContentState = rememberSharedContentState(key = "pet-photo-$petId"),
            animatedVisibilityScope = animated,
        )
    }
}

// ------------------------------------------------------------------ Host

/**
 * Renders the top of the back stack with Material motion:
 *  - push/pop: shared axis X (slide 10–30% + fade, emphasized easing)
 *  - tab switch: fade through (fade + 92% scale-in)
 *  - predictive back (Android 14+): the screen scales to 90% and shifts toward the swipe edge with
 *    rounded corners while dragging; committing continues smoothly from where the finger left it.
 * Every entry gets its own ViewModelStore and saveable state, released when it's popped.
 */
@Composable
fun NavHost(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable (Route) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val saveableStateHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val cancelAnim = remember { Animatable(0f) }

    PlatformBackHandler(
        enabled = navigator.canGoBack,
        onProgress = { progress, fromLeft ->
            navigator.current.backFromLeft = fromLeft
            navigator.current.backProgress = progress
        },
        onCancel = {
            val entry = navigator.current
            scope.launch {
                cancelAnim.snapTo(entry.backProgress)
                cancelAnim.animateTo(0f, Motion.spatialFast()) { entry.backProgress = value }
            }
        },
        onBack = { navigator.back() },
    )

    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = navigator.current,
            contentKey = { it.id },
            transitionSpec = { transitionFor(navigator.lastAction, reduceMotion) },
            label = "nav",
        ) { entry ->
            DisposableEffect(entry) {
                onDispose {
                    if (!navigator.isOnStack(entry)) {
                        entry.viewModelStore.clear()
                        saveableStateHolder.removeState(entry.id)
                    }
                }
            }
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides entry,
                LocalSharedScope provides this@SharedTransitionLayout,
                LocalNavAnimatedScope provides this@AnimatedContent,
            ) {
                saveableStateHolder.SaveableStateProvider(entry.id) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = entry.backProgress
                                if (p > 0f && !reduceMotion) {
                                    val scale = 1f - 0.1f * p
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = (if (entry.backFromLeft) 1f else -1f) * size.width * 0.06f * p
                                    shape = RoundedCornerShape((28 * p).dp)
                                    clip = true
                                } else if (p > 0f) {
                                    alpha = 1f - 0.5f * p
                                }
                            },
                    ) {
                        content(entry.route)
                    }
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavEntry>.transitionFor(action: NavAction, reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    val predictive = initialState.backProgress > 0f
    return when (action) {
        NavAction.PUSH ->
            (slideInHorizontally(tween(Motion.MEDIUM4, easing = Motion.EmphasizedDecelerate)) { it / 4 } +
                fadeIn(tween(Motion.MEDIUM2, delayMillis = 50))) togetherWith
                (slideOutHorizontally(tween(Motion.MEDIUM2, easing = Motion.EmphasizedAccelerate)) { -it / 10 } +
                    fadeOut(tween(Motion.SHORT4)))
        NavAction.POP ->
            if (predictive) {
                fadeIn(tween(Motion.MEDIUM2)) togetherWith
                    (fadeOut(tween(Motion.SHORT4)) + scaleOut(tween(Motion.MEDIUM2), targetScale = 0.85f))
            } else {
                (slideInHorizontally(tween(Motion.MEDIUM4, easing = Motion.EmphasizedDecelerate)) { -it / 10 } +
                    fadeIn(tween(Motion.MEDIUM2))) togetherWith
                    (slideOutHorizontally(tween(Motion.MEDIUM2, easing = Motion.EmphasizedAccelerate)) { it / 4 } +
                        fadeOut(tween(Motion.SHORT4)))
            }.apply { targetContentZIndex = -1f }
        NavAction.TAB ->
            (fadeIn(tween(Motion.MEDIUM1, delayMillis = 90, easing = Motion.EmphasizedDecelerate)) +
                scaleIn(tween(Motion.MEDIUM1, delayMillis = 90, easing = Motion.EmphasizedDecelerate), initialScale = 0.92f)) togetherWith
                fadeOut(tween(90))
        NavAction.REPLACE -> fadeIn(tween(Motion.MEDIUM4)) togetherWith fadeOut(tween(Motion.SHORT4))
    }
}
