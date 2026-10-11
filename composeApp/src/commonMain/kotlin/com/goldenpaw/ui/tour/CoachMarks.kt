package com.goldenpaw.ui.tour

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import kotlinx.coroutines.delay

/** A one-time tip anchored to a UI element. Lower [priority] shows first when several are on screen. */
data class FeatureTip(val id: String, val title: String, val body: String, val priority: Int)

/** Every coach mark in the app. Ids are persisted: never reuse one for a different tip. */
object FeatureTips {
    val QuickLog = FeatureTip(
        "quick_log", "Log anything in seconds",
        "A symptom, a weight or a note, with a photo if you like. It's always one tap away here.", 10,
    )
    val CheckIn = FeatureTip(
        "check_in", "Your daily check-in",
        "Thirty seconds a day on appetite, mobility, mood and more. It builds the quality-of-life trend.", 20,
    )
    val Meds = FeatureTip(
        "meds", "Today's doses",
        "Tap a dose to log it. Manage schedules, tapers and refill alerts from here.", 30,
    )
    val JournalTab = FeatureTip(
        "tab_journal", "Journal",
        "Every symptom, weight and note in one timeline, newest first.", 40,
    )
    val InsightsTab = FeatureTip(
        "tab_insights", "Insights",
        "Trends over weeks and a weekly summary with questions worth asking your vet.", 50,
    )
    val PetsTab = FeatureTip(
        "tab_pets", "Pets & records",
        "Profiles, vet visits and scanned vaccine cards for each of your pets.", 60,
    )
    val RecordsScan = FeatureTip(
        "records_scan", "Scan a vet or vaccine card",
        "Take a photo and GoldenPaw makes a tidy PDF and fills in the details for you.", 15,
    )
    val CareTeam = FeatureTip(
        "care_team", "Share the care",
        "Invite family or a sitter so everyone sees who gave each dose.", 15,
    )

    val all: List<FeatureTip> = listOf(QuickLog, CheckIn, Meds, JournalTab, InsightsTab, PetsTab, RecordsScan, CareTeam)
}

/**
 * Collects where each tip's anchor currently sits on screen and decides which tip (if any) to show.
 * Anchors register themselves through [coachMark]; the [CoachMarkHost] at the app root draws it.
 */
@Stable
class CoachMarkController {
    internal val anchors = mutableStateMapOf<String, Pair<FeatureTip, Rect>>()

    /** Ids already shown. Null until loaded, so nothing flashes before we know. */
    var seen by mutableStateOf<Set<String>?>(null)

    /** False while the tour, a sheet or a dialog is up. */
    var enabled by mutableStateOf(false)

    internal fun update(tip: FeatureTip, bounds: Rect) {
        if (anchors[tip.id]?.second != bounds) anchors[tip.id] = tip to bounds
    }

    internal fun remove(id: String) {
        anchors.remove(id)
    }

    /** The tip to show inside a window of [window] size, or null. */
    fun candidate(window: Size): FeatureTip? {
        val done = seen ?: return null
        if (!enabled || window.width <= 0f) return null
        return anchors.values
            .filter { (tip, rect) -> tip.id !in done && rect.isOnScreen(window) }
            .minByOrNull { it.first.priority }
            ?.first
    }

    fun boundsOf(id: String): Rect? = anchors[id]?.second

    companion object {
        /** Fully visible and not degenerate (items scrolled half off-screen don't count). */
        internal fun Rect.isOnScreen(window: Size): Boolean =
            width > 1f && height > 1f && left >= 0f && top >= 0f && right <= window.width && bottom <= window.height
    }
}

val LocalCoachMarks = staticCompositionLocalOf<CoachMarkController?> { null }

/** Anchors a one-time tip to this element. No-op outside a [CoachMarkHost]. */
fun Modifier.coachMark(tip: FeatureTip): Modifier = composed {
    val controller = LocalCoachMarks.current
    if (controller == null) {
        Modifier
    } else {
        DisposableEffect(controller, tip.id) { onDispose { controller.remove(tip.id) } }
        Modifier.onGloballyPositioned { controller.update(tip, it.boundsInRoot()) }
    }
}

/**
 * Draws the current tip: a dimmed scrim with a soft spotlight cut around the anchor, a gently
 * pulsing ring, and a card with the tip placed above or below the anchor, whichever has room. A tip
 * waits a moment after its anchor appears (so screen transitions settle) and is shown once.
 */
@Composable
fun CoachMarkHost(controller: CoachMarkController, onSeen: (String) -> Unit) {
    var windowSize by remember { mutableStateOf(Size.Zero) }
    val candidate = controller.candidate(windowSize)
    var shown by remember { mutableStateOf<FeatureTip?>(null) }

    LaunchedEffect(candidate?.id) {
        if (candidate == null) {
            shown = null
            return@LaunchedEffect
        }
        delay(SETTLE_MS)
        shown = candidate
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { windowSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) },
    ) {
        val tip = shown
        val bounds = tip?.let { controller.boundsOf(it.id) }
        if (tip != null && bounds != null) {
            key(tip.id) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visibleState = appear, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
                    Spotlight(
                        tip = tip,
                        anchor = bounds,
                        onDismiss = {
                            shown = null
                            onSeen(tip.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Spotlight(tip: FeatureTip, anchor: Rect, onDismiss: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val pulse = rememberInfiniteTransition(label = "coachPulse")
    val p by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val ring = MaterialTheme.colorScheme.primary
    val scrim = Color.Black.copy(alpha = 0.58f)

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val pad = 8.dp.toPx()
            val hole = Rect(anchor.left - pad, anchor.top - pad, anchor.right + pad, anchor.bottom + pad)
            val corner = CornerRadius(minOf(hole.height, hole.width) / 2f, minOf(hole.height, hole.width) / 2f)
            drawRect(scrim)
            drawRoundRect(Color.Black, topLeft = hole.topLeft, size = hole.size, cornerRadius = corner, blendMode = BlendMode.Clear)
            if (!reduceMotion) {
                val grow = 14.dp.toPx() * p
                drawRoundRect(
                    color = ring.copy(alpha = 0.8f * (1f - p)),
                    topLeft = Offset(hole.left - grow, hole.top - grow),
                    size = Size(hole.width + 2 * grow, hole.height + 2 * grow),
                    cornerRadius = CornerRadius(corner.x + grow, corner.y + grow),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            drawRoundRect(ring, topLeft = hole.topLeft, size = hole.size, cornerRadius = corner, style = Stroke(width = 2.dp.toPx()))
        }

        TipBubbleLayout(anchor = anchor) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                val pop = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visibleState = pop, enter = if (reduceMotion) fadeIn() else scaleIn(initialScale = 0.9f) + fadeIn()) {
                    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
                        Text(tip.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            tip.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                        )
                        Row(Modifier.align(Alignment.End).padding(top = 8.dp)) {
                            FilledTonalButton(onClick = onDismiss) { Text("Got it") }
                        }
                    }
                }
            }
        }
    }
}

/** Places the bubble below the anchor when there's room, otherwise above; clamped to the window. */
@Composable
private fun TipBubbleLayout(anchor: Rect, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val gap = 20.dp.roundToPx()
        val maxWidth = (constraints.maxWidth - 2 * margin).coerceIn(0, 360.dp.roundToPx())
        val placeable = measurables.first().measure(Constraints(maxWidth = maxWidth))
        val position = bubblePosition(
            anchor = anchor,
            bubbleWidth = placeable.width,
            bubbleHeight = placeable.height,
            windowWidth = constraints.maxWidth,
            windowHeight = constraints.maxHeight,
            margin = margin,
            gap = gap,
        )
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(position.first, position.second) }
    }
}

/** Top-left of the tip bubble, in window pixels. Pure, so it's unit-tested. */
internal fun bubblePosition(
    anchor: Rect,
    bubbleWidth: Int,
    bubbleHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    margin: Int,
    gap: Int,
): Pair<Int, Int> {
    val x = (anchor.center.x - bubbleWidth / 2f).toInt()
        .coerceIn(margin, (windowWidth - margin - bubbleWidth).coerceAtLeast(margin))
    val below = anchor.bottom.toInt() + gap
    val above = anchor.top.toInt() - gap - bubbleHeight
    val y = when {
        below + bubbleHeight <= windowHeight - margin -> below
        above >= margin -> above
        else -> (windowHeight - margin - bubbleHeight).coerceAtLeast(margin)
    }
    return x to y
}

private const val SETTLE_MS = 700L
