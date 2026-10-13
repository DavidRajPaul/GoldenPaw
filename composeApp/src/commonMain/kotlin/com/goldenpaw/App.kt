package com.goldenpaw

import com.goldenpaw.ui.designsystem.MotionSpecs


import androidx.compose.animation.AnimatedVisibility
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
import com.goldenpaw.ui.designsystem.ChoreographedSplash
import com.goldenpaw.ui.navigation.GpBottomBar
import com.goldenpaw.ui.records.RecordDetailScreen
import com.goldenpaw.ui.records.RecordScanScreen
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import com.goldenpaw.data.settings.FeatureTipsStore
import com.goldenpaw.ui.tour.AppTour
import com.goldenpaw.ui.tour.CoachMarkController
import com.goldenpaw.ui.tour.CoachMarkHost
import com.goldenpaw.ui.tour.FeatureTipPolicy
import com.goldenpaw.ui.tour.FeatureTips
import com.goldenpaw.ui.tour.LocalCoachMarks
import kotlinx.datetime.Clock

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
                        enter = MotionSpecs.overlayEnter(reduceMotion), // never plays: the splash starts visible
                        exit = MotionSpecs.splashExit(reduceMotion),
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
    val reduce = LocalReduceMotion.current
    val navigator = remember { Navigator(if (state.settings.onboardingDone) Route.Today else Route.Onboarding) }
    var paywallReason by remember { mutableStateOf<String?>(null) }
    var paywallOfferId by remember { mutableStateOf<String?>(null) }
    var quickLogOpen by rememberSaveable { mutableStateOf(false) }
    val pricing by appVm.pricingCatalog.collectAsStateWithLifecycle()

    // First-run tour and one-time feature tips.
    val tipsStore: FeatureTipsStore = koinInject()
    val coachMarks = remember { CoachMarkController() }
    val seenTips: Set<String>? by tipsStore.seen.collectAsStateWithLifecycle(initialValue = null)
    val tipScope = rememberCoroutineScope()
    val showTour = state.settings.onboardingDone && !state.settings.tourDone && state.pets.isNotEmpty() && navigator.isTopLevel
    // Feature tips are a one-time walkthrough on Today right after the tour. Leaving Today retires
    // every remaining tip, so they never reappear when switching tabs.
    val onHome = navigator.current.route == Route.Today
    val tipsAllowed = FeatureTipPolicy.allowed(
        tourDone = state.settings.tourDone,
        tourShowing = showTour,
        onHome = onHome,
        overlayOpen = paywallReason != null || quickLogOpen || celebrations.isNotEmpty(),
    )
    LaunchedEffect(onHome, state.settings.tourDone, seenTips) {
        if (FeatureTipPolicy.shouldRetire(state.settings.tourDone, onHome, seenTips, FeatureTips.all.map { it.id })) {
            tipsStore.markAllSeen(FeatureTips.all.map { it.id })
        }
    }
    SideEffect {
        coachMarks.seen = seenTips
        coachMarks.enabled = tipsAllowed
    }

    // One router for deep links from notifications, the widget, shortcuts and in-app promotions.
    val route = rememberUpdatedState { r: LaunchRequest ->
        r.petId?.let { appVm.selectPet(it) }
        val petId = r.petId ?: state.selectedPet?.id
        when (r.open) {
            LaunchRequest.OPEN_CHECKIN -> if (petId != null) navigator.navigate(Route.CheckIn(petId))
            LaunchRequest.OPEN_MEDS -> if (petId != null) navigator.navigate(Route.Meds(petId))
            LaunchRequest.OPEN_SYMPTOM -> if (petId != null) navigator.navigate(Route.SymptomEdit(petId))
            LaunchRequest.OPEN_QUICK_LOG -> quickLogOpen = true
            LaunchRequest.OPEN_WEEKLY -> navigator.switchTab(Route.Insights)
            LaunchRequest.OPEN_CARE_TEAM -> navigator.navigate(Route.CareTeam)
            LaunchRequest.OPEN_SETTINGS -> navigator.switchTab(Route.Settings)
            LaunchRequest.OPEN_PAYWALL -> {
                paywallOfferId = r.offerId
                paywallReason = "Everything in GoldenPaw Plus, for every pet you care for."
            }
            else -> navigator.switchTab(Route.Today)
        }
        Unit
    }
    val actions = remember(navigator) {
        AppActions(
            navigate = { destination -> navigator.navigate(destination) },
            back = { if (!navigator.back()) navigator.switchTab(Route.Today) },
            goHome = { navigator.switchTab(Route.Today) },
            showPaywall = { reason -> paywallReason = reason },
            selectPet = { id -> appVm.selectPet(id) },
            canAddPet = { appVm.canAddPet() },
            openQuickLog = { quickLogOpen = true },
            openLink = { r -> route.value(r) },
        )
    }

    val request by launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request, state.settings.onboardingDone) {
        val r = request ?: return@LaunchedEffect
        if (!state.settings.onboardingDone) return@LaunchedEffect
        route.value(r)
        onLaunchRequestHandled()
    }

    CompositionLocalProvider(LocalAppActions provides actions, LocalCoachMarks provides coachMarks) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = navigator.isTopLevel,
                    enter = MotionSpecs.barEnter(reduce),
                    exit = MotionSpecs.barExit(reduce),
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
                catalog = pricing,
                now = Clock.System.now(),
                offerId = paywallOfferId,
                onStartTrial = { appVm.startTrial() },
                onDismiss = {
                    paywallReason = null
                    paywallOfferId = null
                },
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
        CoachMarkHost(coachMarks, onSeen = { id -> tipScope.launch { tipsStore.markSeen(id) } })
        AnimatedVisibility(visible = showTour, enter = MotionSpecs.overlayEnter(reduce), exit = MotionSpecs.overlayExit(reduce)) {
            AppTour(petName = state.selectedPet?.name, onFinish = { offers -> appVm.finishTour(offers) })
        }
    }
}
