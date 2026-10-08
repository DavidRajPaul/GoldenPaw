package com.goldenpaw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.domain.model.GamificationState
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.ThemeMode
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.platform.PlatformServices
import com.goldenpaw.platform.systemPrefersReducedMotion
import com.goldenpaw.ui.AppViewModel
import com.goldenpaw.ui.GamificationViewModel
import com.goldenpaw.ui.LaunchRequest
import com.goldenpaw.ui.achievements.AchievementsScreen
import com.goldenpaw.ui.achievements.BadgeCelebrationDialog
import com.goldenpaw.ui.care.CareTeamScreen
import com.goldenpaw.ui.checkin.CheckInScreen
import com.goldenpaw.ui.designsystem.GoldenPawTheme
import com.goldenpaw.ui.designsystem.LocalAppFiles
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.PawMark
import com.goldenpaw.ui.insights.InsightsScreen
import com.goldenpaw.ui.journal.JournalScreen
import com.goldenpaw.ui.journal.SymptomEditorScreen
import com.goldenpaw.ui.meds.MedicationEditorScreen
import com.goldenpaw.ui.meds.MedsScreen
import com.goldenpaw.ui.navigation.AppActions
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.NavHost
import com.goldenpaw.ui.navigation.Navigator
import com.goldenpaw.ui.navigation.Route
import com.goldenpaw.ui.navigation.TopLevel
import com.goldenpaw.ui.onboarding.OnboardingScreen
import com.goldenpaw.ui.paywall.PaywallSheet
import com.goldenpaw.ui.pets.MemorialScreen
import com.goldenpaw.ui.pets.PetDetailScreen
import com.goldenpaw.ui.pets.PetEditorScreen
import com.goldenpaw.ui.pets.PetsScreen
import com.goldenpaw.ui.pets.VetVisitEditorScreen
import com.goldenpaw.ui.quicklog.QuickLogSheet
import com.goldenpaw.ui.settings.RemindersHealthScreen
import com.goldenpaw.ui.settings.SettingsScreen
import com.goldenpaw.ui.today.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.animation.scaleOut
import com.goldenpaw.ui.designsystem.ChoreographedSplash
import com.goldenpaw.ui.navigation.GpBottomBar
import com.goldenpaw.ui.records.RecordDetailScreen
import com.goldenpaw.ui.records.RecordScanScreen

/** App-scoped gamification state for any screen that wants it (null in gentle mode). */
val LocalGamification = staticCompositionLocalOf<GamificationState?> { null }

/**
 * Root of the app on every platform (Android Activity, iOS UIViewController, Desktop window).
 */
@Composable
fun GoldenPawApp(
    launchRequests: StateFlow<LaunchRequest?>,
    onLaunchRequestHandled: () -> Unit,
) {
    val appVm: AppViewModel = koinViewModel()
    val gamificationVm: GamificationViewModel = koinViewModel()
    val platform: PlatformServices = koinInject()
    val files: AppFiles = koinInject()
    val state by appVm.state.collectAsStateWithLifecycle()
    val gamification by gamificationVm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val dark = when (s.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val reduceMotion = s.reduceMotion || systemPrefersReducedMotion()

    CompositionLocalProvider(
        LocalPlatform provides platform,
        LocalAppFiles provides files,
        LocalGamification provides gamification,
    ) {
        GoldenPawTheme(darkTheme = dark, dynamicColor = s.dynamicColor, reduceMotion = reduceMotion) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                var splashDone by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    if (state.loaded) {
                        AppContent(appVm, gamificationVm, launchRequests, onLaunchRequestHandled)
                    }
                    AnimatedVisibility(
                        visible = !splashDone || !state.loaded,
                        enter = fadeIn(),
                        exit = if (reduceMotion) {
                            fadeOut(tween(Motion.SHORT4))
                        } else {
                            fadeOut(tween(Motion.MEDIUM2, easing = Motion.EmphasizedAccelerate)) +
                                scaleOut(tween(Motion.MEDIUM4, easing = Motion.EmphasizedDecelerate), targetScale = 1.08f)
                        },
                    ) {
                        ChoreographedSplash(onFinished = { splashDone = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppContent(
    appVm: AppViewModel,
    gamificationVm: GamificationViewModel,
    launchRequests: StateFlow<LaunchRequest?>,
    onLaunchRequestHandled: () -> Unit,
) {
    val state by appVm.state.collectAsStateWithLifecycle()
    val celebrations by gamificationVm.celebrations.collectAsStateWithLifecycle()
    val navigator = remember { Navigator(if (state.settings.onboardingDone) Route.Today else Route.Onboarding) }
    var paywallReason by remember { mutableStateOf<String?>(null) }
    var quickLogOpen by rememberSaveable { mutableStateOf(false) }

    val actions = remember(navigator) {
        AppActions(
            navigate = { route -> navigator.navigate(route) },
            back = { if (!navigator.back()) navigator.switchTab(Route.Today) },
            goHome = { navigator.switchTab(Route.Today) },
            showPaywall = { reason -> paywallReason = reason },
            selectPet = { id -> appVm.selectPet(id) },
            canAddPet = { appVm.canAddPet() },
            openQuickLog = { quickLogOpen = true },
        )
    }

    // Deep links from notifications, the home-screen widget and app shortcuts.
    val request by launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request, state.settings.onboardingDone) {
        val r = request ?: return@LaunchedEffect
        if (!state.settings.onboardingDone) return@LaunchedEffect
        r.petId?.let { appVm.selectPet(it) }
        val petId = r.petId ?: state.selectedPet?.id
        when (r.open) {
            LaunchRequest.OPEN_CHECKIN -> if (petId != null) navigator.navigate(Route.CheckIn(petId))
            LaunchRequest.OPEN_MEDS -> if (petId != null) navigator.navigate(Route.Meds(petId))
            LaunchRequest.OPEN_SYMPTOM -> if (petId != null) navigator.navigate(Route.SymptomEdit(petId))
            LaunchRequest.OPEN_QUICK_LOG -> quickLogOpen = true
            LaunchRequest.OPEN_WEEKLY -> navigator.switchTab(Route.Insights)
            LaunchRequest.OPEN_CARE_TEAM -> navigator.navigate(Route.CareTeam)
            else -> navigator.switchTab(Route.Today)
        }
        onLaunchRequestHandled()
    }

    CompositionLocalProvider(LocalAppActions provides actions) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = navigator.isTopLevel,
                    enter = slideInVertically(Motion.spatialDefault()) { it } + fadeIn(),
                    exit = slideOutVertically(Motion.spatialFast()) { it } + fadeOut(),
                ) {
                    GpBottomBar(current = navigator.current.route, onSelect = { navigator.switchTab(it) })
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            NavHost(
                navigator = navigator,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
            ) { route ->
                when (route) {
                    Route.Onboarding -> OnboardingScreen(onFinished = { navigator.resetTo(Route.PetEdit()) })
                    Route.Today -> TodayScreen()
                    Route.Journal -> JournalScreen()
                    Route.Insights -> InsightsScreen()
                    Route.Pets -> PetsScreen()
                    Route.Settings -> SettingsScreen()
                    Route.Reminders -> RemindersHealthScreen()
                    Route.CareTeam -> CareTeamScreen()
                    Route.Achievements -> AchievementsScreen()
                    is Route.PetDetail -> PetDetailScreen(petId = route.petId)
                    is Route.Memorial -> MemorialScreen(petId = route.petId)
                    is Route.PetEdit -> PetEditorScreen(
                        petId = route.petId,
                        onDone = { savedId ->
                            appVm.selectPet(savedId)
                            if (navigator.canGoBack) navigator.back() else navigator.resetTo(Route.Today)
                        },
                    )
                    is Route.Meds -> MedsScreen(petId = route.petId)
                    is Route.MedEdit -> MedicationEditorScreen(petId = route.petId, medId = route.medId)
                    is Route.CheckIn -> CheckInScreen(petId = route.petId)
                    is Route.SymptomEdit -> SymptomEditorScreen(petId = route.petId, entryId = route.entryId)
                    is Route.VetVisitEdit -> VetVisitEditorScreen(petId = route.petId, visitId = route.visitId)
                    is Route.RecordScan -> RecordScanScreen(petId = route.petId, recordId = route.recordId)
                    is Route.RecordDetail -> RecordDetailScreen(petId = route.petId, recordId = route.recordId)
                }
            }
        }

        val reason = paywallReason
        if (reason != null) {
            LaunchedEffect(reason) { appVm.recordPaywallView() }
            PaywallSheet(
                reason = reason,
                isPlus = state.settings.isPlus,
                onStartTrial = { appVm.startTrial() },
                onDismiss = { paywallReason = null },
            )
        }
        if (quickLogOpen) {
            QuickLogSheet(onDismiss = { quickLogOpen = false })
        }
        if (celebrations.isNotEmpty()) {
            BadgeCelebrationDialog(
                badges = celebrations,
                onDismiss = gamificationVm::dismissCelebrations,
                onSeeAll = {
                    gamificationVm.dismissCelebrations()
                    navigator.navigate(Route.Achievements)
                },
            )
        }
    }
}
