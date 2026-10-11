package com.goldenpaw.ui.tour

import com.goldenpaw.ui.designsystem.MotionSpecs

import com.goldenpaw.ui.designsystem.rememberAmbientTransition

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goldenpaw.ui.designsystem.GpIcons
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** One page of the welcome tour. */
internal data class TourPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    /** Small supporting icons that orbit the main one. */
    val satellites: List<ImageVector> = emptyList(),
)

internal fun tourPages(petName: String?): List<TourPage> {
    val pet = petName?.takeIf { it.isNotBlank() } ?: "your pet"
    return listOf(
        TourPage(
            GpIcons.Pet,
            "Welcome to GoldenPaw",
            "A calm home for $pet's care. Here's a 30-second look at what's inside. You can replay this any time from Settings.",
            listOf(GpIcons.Medication, GpIcons.CheckIn, GpIcons.Insights),
        ),
        TourPage(
            GpIcons.Medication,
            "Never miss a dose",
            "Today shows every dose that's due. Tap to log it, or answer right from the reminder. Refill alerts arrive before you run out.",
            listOf(GpIcons.Vet, GpIcons.Streak),
        ),
        TourPage(
            GpIcons.CheckIn,
            "A 30-second daily check-in",
            "Appetite, mobility, mood and more on a simple scale. Over weeks it becomes a clear picture of $pet's quality of life.",
            listOf(GpIcons.Growth, GpIcons.Weight),
        ),
        TourPage(
            GpIcons.Journal,
            "Quick log anything",
            "A limp, a missed meal, a new weight: the Quick log button captures it in seconds, with a photo if you like.",
            listOf(GpIcons.Camera, GpIcons.Weight),
        ),
        TourPage(
            GpIcons.Scan,
            "Vet and vaccine cards, scanned",
            "Photograph a vaccine card or invoice and GoldenPaw turns it into a tidy PDF with the details filled in.",
            listOf(GpIcons.Vaccine, GpIcons.Document),
        ),
        TourPage(
            GpIcons.Insights,
            "See the trends, bring the questions",
            "Insights charts the weeks, and a weekly summary suggests questions worth asking your vet. It's never veterinary advice.",
            listOf(GpIcons.Sparkle, GpIcons.Vet),
        ),
        TourPage(
            Icons.Rounded.Groups,
            "Share the care",
            "Invite family or a sitter. Everyone sees who gave which dose, so nothing is doubled or missed.",
            listOf(GpIcons.Cloud, GpIcons.Medication),
        ),
    )
}

/**
 * First-run feature tour, shown once after the first pet is added. Full-screen, swipeable, with a
 * slow orbiting illustration per page and a parallax hand-off between pages (both off under reduce
 * motion). The last page asks, plainly, whether the owner wants offer notifications (opt-in).
 */
@Composable
fun AppTour(
    petName: String?,
    onFinish: (offerNotifications: Boolean) -> Unit,
) {
    val pages = remember(petName) { tourPages(petName) }
    val total = pages.size + 1 // + the "stay in the loop" page
    val pager = rememberPagerState { total }
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    var offers by remember { mutableStateOf(false) }
    val last = pager.currentPage == total - 1
    val scheme = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.background, scheme.background)))
            // Swallow taps so nothing underneath reacts while the tour is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = { onFinish(false) }) { Text("Skip") }
                else Spacer(Modifier.height(48.dp))
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val offset = (pager.currentPage - index) + pager.currentPageOffsetFraction
                val parallax = Modifier.graphicsLayer {
                    if (!reduceMotion) {
                        translationX = offset * size.width * 0.35f
                        alpha = 1f - 0.6f * offset.absoluteValue.coerceIn(0f, 1f)
                    }
                }
                if (index < pages.size) {
                    TourPageContent(pages[index], illustrationModifier = parallax)
                } else {
                    StayInTheLoop(offers = offers, onOffers = { offers = it }, illustrationModifier = parallax)
                }
            }

            PageDots(count = total, current = pager.currentPage, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (last) onFinish(offers) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(52.dp),
            ) {
                AnimatedContent(
                    targetState = last,
                    transitionSpec = { MotionSpecs.fadeThrough(reduceMotion, MotionSpecs.FadePreset.Quick) },
                    label = "tourCta",
                ) { isLast -> Text(if (isLast) "Let's go" else "Next") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TourPageContent(page: TourPage, illustrationModifier: Modifier) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OrbitIllustration(page.icon, page.satellites, illustrationModifier.semantics { contentDescription = page.title })
        Spacer(Modifier.height(36.dp))
        Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StayInTheLoop(offers: Boolean, onOffers: (Boolean) -> Unit, illustrationModifier: Modifier) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OrbitIllustration(Icons.Rounded.NotificationsActive, listOf(GpIcons.Sparkle, Icons.Rounded.Groups), illustrationModifier)
        Spacer(Modifier.height(36.dp))
        Text("Stay in the loop", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "We'll let you know about your care team and genuinely useful new features. You can change this any time in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = { onOffers(!offers) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tell me about offers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Occasional discounts on GoldenPaw Plus. Never more than a few a year.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = offers, onCheckedChange = onOffers)
            }
        }
    }
}

/**
 * The hero icon in a soft disc, with two slowly breathing rings and small satellite icons orbiting
 * it. Static (no orbit, no breathing) under reduce motion.
 */
@Composable
internal fun OrbitIllustration(icon: ImageVector, satellites: List<ImageVector>, modifier: Modifier = Modifier) {
    // Not created at all under reduced motion: the illustration is then static.
    val transition = rememberAmbientTransition(label = "orbit")
    val angle = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "angle",
    )
    val breath = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val a = angle?.value ?: 30f
    val b = breath?.value ?: 0.5f
    val ring = MaterialTheme.colorScheme.primary
    val disc = MaterialTheme.colorScheme.primaryContainer
    val onDisc = MaterialTheme.colorScheme.onPrimaryContainer

    Box(modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(ring.copy(alpha = 0.10f + 0.06f * b), radius = r * (0.86f + 0.05f * b), style = Stroke(width = 2.dp.toPx()))
            drawCircle(ring.copy(alpha = 0.18f), radius = r * 0.66f, style = Stroke(width = 1.5.dp.toPx()))
        }
        Surface(shape = CircleShape, color = disc, shadowElevation = 6.dp, modifier = Modifier.size(104.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = onDisc, modifier = Modifier.size(52.dp))
            }
        }
        satellites.forEachIndexed { i, satellite ->
            val theta = (a + i * (360f / satellites.size.coerceAtLeast(1))) * (kotlin.math.PI.toFloat() / 180f)
            val radius = 92.dp
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = radius.toPx() * kotlin.math.cos(theta)
                        translationY = radius.toPx() * kotlin.math.sin(theta)
                    }
                    .size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(satellite, contentDescription = null, tint = ring, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val selected = i == current
            // Size change: snaps under reduced motion (the colour change still shows the page).
            val width by animateDpAsState(
                if (selected) 22.dp else 8.dp,
                animationSpec = if (reduceMotion) snap() else spring(visibilityThreshold = Dp.VisibilityThreshold),
                label = "dotWidth",
            )
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}
