package com.goldenpaw.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.platform.ReminderHealth
import com.goldenpaw.platform.rememberHaptics
import com.goldenpaw.platform.rememberNotificationPermissionRequest
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.PawMark
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val reminders: ReminderGateway,
    private val careTeam: CareTeamService,
) : ViewModel() {
    fun finish(ownerName: String, onDone: () -> Unit) = viewModelScope.launch {
        settings.update { it.copy(onboardingDone = true, ownerName = ownerName.trim()) }
        runCatching {
            careTeam.ensureSetup()
            careTeam.syncOwnerName(ownerName)
        }
        runCatching { reminders.rescheduleAll() }
        onDone()
    }
}

private enum class Tint { PRIMARY, SECONDARY, TERTIARY }

private data class Page(
    val hero: ImageVector,
    val accents: List<ImageVector>,
    val tint: Tint,
    val title: String,
    val body: String,
)

private val pages = listOf(
    Page(
        Icons.Rounded.Pets,
        listOf(Icons.Rounded.Favorite, Icons.Rounded.WbSunny, Icons.Rounded.Spa),
        Tint.PRIMARY,
        "Every good day, remembered.\nEvery hard day, easier.",
        "GoldenPaw is a calm companion for caring for an older or chronically ill dog or cat.",
    ),
    Page(
        Icons.Rounded.Medication,
        listOf(Icons.Rounded.MonitorHeart, Icons.Rounded.EditNote, Icons.Rounded.Description),
        Tint.SECONDARY,
        "Everything in one gentle place",
        "Medication reminders with one-tap \"given\", a 30-second daily check-in, a quality-of-life score, " +
            "weight and symptom tracking, scanned vet records and a vet-ready PDF report.",
    ),
    Page(
        Icons.Rounded.Groups,
        listOf(Icons.Rounded.Handshake, Icons.Rounded.Shield, Icons.Rounded.Favorite),
        Tint.TERTIARY,
        "Care together",
        "Invite family or a sitter. Everyone sees what was given and by whom, and GoldenPaw warns before a dose is given twice.",
    ),
    Page(
        Icons.Rounded.NotificationsActive,
        listOf(Icons.Rounded.Alarm, Icons.Rounded.Schedule, Icons.Rounded.CheckCircle),
        Tint.PRIMARY,
        "Reminders you can rely on",
        "Allow notifications so you never miss a dose. You can fine-tune everything later in Settings.",
    ),
)

@Composable
private fun Tint.container(): Color = when (this) {
    Tint.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    Tint.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer
    Tint.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
private fun Tint.accent(): Color = when (this) {
    Tint.PRIMARY -> MaterialTheme.colorScheme.primary
    Tint.SECONDARY -> MaterialTheme.colorScheme.secondary
    Tint.TERTIARY -> MaterialTheme.colorScheme.tertiary
}

/** Pager position as a float: 1.4 = 40% of the way from page 1 to page 2. */
private val PagerState.position: Float get() = currentPage + currentPageOffsetFraction

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val vm: OnboardingViewModel = koinViewModel()
    val pageCount = pages.size + 1
    val pagerState = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf("") }
    var health by remember { mutableStateOf(platform.reminderHealth()) }

    val requestNotifications = rememberNotificationPermissionRequest {
        scope.launch { health = platform.loadReminderHealth() }
    }
    // Live permission status: re-read whenever the user returns (e.g. from the Settings app).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { health = platform.loadReminderHealth() }
    }

    // Background wash tinted per page, blended continuously while swiping.
    val washColors = pages.map { it.tint.container() } + MaterialTheme.colorScheme.surfaceContainer
    val background = MaterialTheme.colorScheme.background

    Column(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val pos = pagerState.position.coerceIn(0f, (pageCount - 1).toFloat())
                val from = floor(pos).toInt()
                val to = (from + 1).coerceAtMost(pageCount - 1)
                val wash = lerp(washColors[from], washColors[to], pos - from)
                drawRect(Brush.verticalGradient(listOf(wash.copy(alpha = 0.75f), background), endY = size.height * 0.7f))
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PawMark(Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("GoldenPaw", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (pagerState.currentPage < pages.size) {
                TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pages.size) } }) { Text("Skip") }
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
            if (index < pages.size) {
                val page = pages[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OrbitIllustration(
                        page = page,
                        settled = pagerState.currentPage == index,
                        offset = { (pagerState.position - index) },
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            val o = (pagerState.position - index).coerceIn(-1f, 1f)
                            translationX = -o * size.width * 0.12f
                            alpha = 1f - o.absoluteValue * 0.8f
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        page.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (index == pages.lastIndex) {
                        Spacer(Modifier.height(20.dp))
                        PermissionCard(health = health, onAllow = requestNotifications)
                    }
                }
            } else {
                NamePage(name = name, onName = { name = it.take(40) })
            }
        }

        PagerIndicator(pagerState, pageCount, Modifier.fillMaxWidth().padding(vertical = 16.dp))

        val last = pagerState.currentPage == pages.size
        Button(
            onClick = {
                haptics.tick()
                if (last) vm.finish(name, onFinished)
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            val label = when {
                last -> "Add my pet"
                pagerState.currentPage == pages.lastIndex && !health.notifications -> "Maybe later"
                else -> "Continue"
            }
            val reduce = LocalReduceMotion.current
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    if (reduce) {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    } else {
                        (slideInVertically(Motion.spatialDefault()) { it / 2 } + fadeIn(tween(Motion.SHORT4))) togetherWith
                            (slideOutVertically(Motion.spatialFast()) { -it / 2 } + fadeOut(tween(Motion.SHORT2)))
                    }
                },
                label = "cta",
            ) { Text(it) }
        }
    }
}

/**
 * Hero illustration with three parallax layers: a slowly breathing blob (slowest), the hero icon
 * that pops in when its page settles (middle) and accent icons orbiting it (fastest).
 */
@Composable
private fun OrbitIllustration(page: Page, settled: Boolean, offset: () -> Float) {
    val reduce = LocalReduceMotion.current
    val container = page.tint.container()
    val accent = page.tint.accent()

    val pop = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(settled, reduce) {
        if (reduce) pop.snapTo(1f)
        else if (settled) pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
        else pop.snapTo(0.6f)
    }

    val transition = rememberInfiniteTransition(label = "orbit")
    val phase = if (reduce) null else transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "orbitPhase",
    )
    val breath = if (reduce) null else transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = Motion.Standard), RepeatMode.Reverse),
        label = "blobBreath",
    )

    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        // Layer 1: blob
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                if (!reduce) translationX = offset() * size.width * 0.15f
            },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val base = size.minDimension * 0.34f
            val b = breath?.value ?: 0.5f
            val t = (phase?.value ?: 0f) * 2f * PI.toFloat()
            val path = Path()
            val steps = 64
            for (i in 0..steps) {
                val a = i / steps.toFloat() * 2f * PI.toFloat()
                val wobble = 1f + 0.06f * sin(3 * a + t) + 0.04f * cos(2 * a - t * 2) + 0.03f * (b - 0.5f)
                val r = base * wobble * (0.96f + 0.08f * b)
                val x = c.x + r * cos(a)
                val y = c.y + r * sin(a)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, container)
        }
        // Layer 3: accents orbiting
        page.accents.forEachIndexed { i, icon ->
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        val t = (phase?.value ?: 0f) * 2f * PI.toFloat()
                        val a = t + i * (2f * PI.toFloat() / page.accents.size) - PI.toFloat() / 2
                        val radius = 96.dp.toPx()
                        translationX = cos(a) * radius + if (!reduce) offset() * 240.dp.toPx() * 0.55f else 0f
                        translationY = sin(a) * radius * 0.82f
                        val p = pop.value.coerceIn(0f, 1.2f)
                        scaleX = p
                        scaleY = p
                        alpha = (1f - offset().absoluteValue).coerceIn(0f, 1f)
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
        }
        // Layer 2: hero icon
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer {
                    if (!reduce) translationX = offset() * size.width * 0.35f
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(page.hero, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(56.dp))
        }
    }
}

/** Live notification status on the reminders page. Refreshes on resume, so it updates after Settings. */
@Composable
private fun PermissionCard(health: ReminderHealth, onAllow: () -> Unit) {
    val wellness = LocalWellnessColors.current
    GpCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (health.notifications) Icons.Rounded.CheckCircle else Icons.Rounded.NotificationsOff,
                contentDescription = null,
                tint = if (health.notifications) wellness.good else wellness.hard,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (health.notifications) "Notifications are on" else "Notifications are off",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (health.notifications) "Dose reminders will reach you." else "Reminders can't appear until you allow them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!health.notifications) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

/** Dots that stretch into a pill as you swipe towards them (driven by the pager offset). */
@Composable
private fun PagerIndicator(state: PagerState, count: Int, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val closeness = (1f - (state.position - i).absoluteValue).coerceIn(0f, 1f)
            val width: Dp = 8.dp + 18.dp * closeness
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(lerp(inactive, active, closeness)),
            )
        }
    }
}

@Composable
private fun NamePage(name: String, onName: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("What should we call you?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Used for greetings and to note who gave each dose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("Your first name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        GpCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
            Text("Your data stays on this phone", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "GoldenPaw works fully offline. Sign in only if you want to share care with others. " +
                    "GoldenPaw is an informational tool, not a substitute for your veterinarian.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
