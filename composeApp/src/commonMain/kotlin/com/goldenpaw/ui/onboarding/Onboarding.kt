package com.goldenpaw.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.platform.rememberNotificationPermissionRequest
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.PawMark
import com.goldenpaw.ui.designsystem.motionOr
import kotlin.math.absoluteValue
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

private data class Page(val emoji: String, val title: String, val body: String)

private val pages = listOf(
    Page(
        "🐾",
        "Every good day, remembered.\nEvery hard day, easier.",
        "GoldenPaw is a calm companion for caring for an older or chronically ill dog or cat.",
    ),
    Page(
        "💊",
        "Everything in one gentle place",
        "Medication reminders with one-tap \"given\", a 30-second daily check-in, a quality-of-life score, " +
            "weight and symptom tracking, and a vet-ready PDF report.",
    ),
    Page(
        "🤝",
        "Care together",
        "Invite family or a sitter. Everyone sees what was given and by whom, and GoldenPaw warns before a dose is given twice.",
    ),
    Page(
        "🔔",
        "Reminders you can rely on",
        "Allow notifications so you never miss a dose. You can fine-tune everything later in Settings.",
    ),
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val vm: OnboardingViewModel = koinViewModel()
    val pagerState = rememberPagerState { pages.size + 1 }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }

    val requestNotifications = rememberNotificationPermissionRequest { notificationsAsked = true }
    val reduceMotion = LocalReduceMotion.current

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp),
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
                // Parallax: the illustration drifts slower than the text and fades with distance.
                val offset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                if (!reduceMotion) {
                                    translationX = offset * size.width * 0.35f
                                    val s = 1f - 0.15f * offset.absoluteValue.coerceAtMost(1f)
                                    scaleX = s
                                    scaleY = s
                                    alpha = 1f - 0.6f * offset.absoluteValue.coerceAtMost(1f)
                                }
                            }
                            .size(140.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Text(page.emoji, fontSize = 64.sp) }
                    Spacer(Modifier.height(32.dp))
                    Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        page.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (index == pages.lastIndex) {
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = requestNotifications,
                            enabled = !notificationsAsked,
                        ) { Text(if (notificationsAsked) "Thanks!" else "Allow notifications") }
                    }
                }
            } else {
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
                        onValueChange = { name = it.take(40) },
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
        }

        // Page dots
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size + 1) { i ->
                val selected = pagerState.currentPage == i
                val width by animateDpAsState(if (selected) 22.dp else 8.dp, motionOr(androidx.compose.animation.core.spring()), label = "dot")
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    label = "dotColor",
                )
                Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(width).clip(CircleShape).background(color))
            }
        }

        val last = pagerState.currentPage == pages.size
        Button(
            onClick = {
                if (last) vm.finish(name, onFinished)
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text(if (last) "Add my pet" else "Continue") }
    }
}
