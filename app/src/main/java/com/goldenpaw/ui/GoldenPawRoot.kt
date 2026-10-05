package com.goldenpaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goldenpaw.LaunchRequest
import com.goldenpaw.domain.repository.ThemeMode
import com.goldenpaw.platform.reminders.NotificationHelper
import com.goldenpaw.ui.checkin.CheckInScreen
import com.goldenpaw.ui.designsystem.GoldenPawTheme
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.PawMark
import com.goldenpaw.ui.insights.InsightsScreen
import com.goldenpaw.ui.journal.JournalScreen
import com.goldenpaw.ui.journal.SymptomEditorScreen
import com.goldenpaw.ui.meds.MedicationEditorScreen
import com.goldenpaw.ui.meds.MedsScreen
import com.goldenpaw.ui.navigation.AppActions
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.LocalNavAnimatedScope
import com.goldenpaw.ui.navigation.LocalSharedScope
import com.goldenpaw.ui.navigation.Routes
import com.goldenpaw.ui.navigation.TopLevel
import com.goldenpaw.ui.onboarding.OnboardingScreen
import com.goldenpaw.ui.paywall.PaywallSheet
import com.goldenpaw.ui.pets.MemorialScreen
import com.goldenpaw.ui.pets.PetDetailScreen
import com.goldenpaw.ui.pets.PetEditorScreen
import com.goldenpaw.ui.pets.PetsScreen
import com.goldenpaw.ui.settings.RemindersHealthScreen
import com.goldenpaw.ui.settings.SettingsScreen
import com.goldenpaw.ui.today.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun GoldenPawRoot(
    launchRequests: StateFlow<LaunchRequest?>,
    onLaunchRequestHandled: () -> Unit,
) {
    val appVm: AppViewModel = koinVm()
    val state by appVm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val dark = when (s.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    GoldenPawTheme(darkTheme = dark, dynamicColor = s.dynamicColor, reduceMotion = s.reduceMotion) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            var splashDone by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                if (state.loaded) {
                    AppNavigation(appVm, state, launchRequests, onLaunchRequestHandled)
                }
                AnimatedVisibility(visible = !splashDone || !state.loaded, enter = fadeIn(), exit = fadeOut(tween(400))) {
                    PawSplash(onFinished = { splashDone = true })
                }
            }
        }
    }
}

/** Paw print "settles" into the logo with a soft spring, then the wordmark fades in. */
@Composable
private fun PawSplash(onFinished: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(if (reduceMotion) 1f else 1.5f) }
    val alpha = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) {
            alpha.animateTo(1f, tween(150)); word.snapTo(1f); delay(250)
        } else {
            launch { alpha.animateTo(1f, tween(350)) }
            scale.animateTo(1f, com.goldenpaw.ui.designsystem.MotionSpecs.settleSpring())
            word.animateTo(1f, tween(350))
            delay(350)
        }
        onFinished()
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PawMark(
                Modifier.size(110.dp).graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value
                    rotationZ = (scale.value - 1f) * -24f
                },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "GoldenPaw",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.graphicsLayer { this.alpha = word.value; translationY = (1f - word.value) * 18f },
            )
            Text(
                "Every good day, remembered.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { this.alpha = word.value },
            )
        }
    }
}

@Composable
private fun AppNavigation(
    appVm: AppViewModel,
    state: AppState,
    launchRequests: StateFlow<LaunchRequest?>,
    onLaunchRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val reduceMotion = LocalReduceMotion.current
    var paywallReason by remember { mutableStateOf<String?>(null) }
    val start = remember { if (state.settings.onboardingDone) Routes.TODAY else Routes.ONBOARDING }

    val actions = remember(navController) {
        AppActions(
            navigate = { route -> navController.navigate(route) { launchSingleTop = true } },
            back = {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    navController.navigate(Routes.TODAY) { popUpTo(navController.graph.id) { inclusive = true } }
                }
            },
            goHome = { navController.navigateTopLevel(Routes.TODAY) },
            showPaywall = { reason -> paywallReason = reason },
            selectPet = { id -> appVm.selectPet(id) },
            canAddPet = { appVm.canAddPet() },
        )
    }

    // Deep links from notifications
    val request by launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request, state.settings.onboardingDone) {
        val r = request ?: return@LaunchedEffect
        if (!state.settings.onboardingDone) return@LaunchedEffect
        r.petId?.let { appVm.selectPet(it) }
        val petId = r.petId ?: state.selectedPet?.id
        when (r.open) {
            NotificationHelper.OPEN_CHECKIN -> if (petId != null) navController.navigate(Routes.checkIn(petId))
            NotificationHelper.OPEN_MEDS -> if (petId != null) navController.navigate(Routes.meds(petId))
            else -> navController.navigateTopLevel(Routes.TODAY)
        }
        onLaunchRequestHandled()
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = TopLevel.entries.any { it.route == currentRoute }

    CompositionLocalProvider(LocalAppActions provides actions) {
        androidx.compose.material3.Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        TopLevel.entries.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = { navController.navigateTopLevel(item.route) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        ) { padding ->
            SharedTransitionLayout(
                Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
            ) {
                CompositionLocalProvider(LocalSharedScope provides this) {
                    NavHost(
                        navController = navController,
                        startDestination = start,
                        enterTransition = {
                            if (reduceMotion) fadeIn(tween(150))
                            else fadeIn(tween(260)) + slideInHorizontally(tween(320)) { it / 10 }
                        },
                        exitTransition = { fadeOut(tween(180)) },
                        popEnterTransition = { fadeIn(tween(220)) },
                        popExitTransition = {
                            if (reduceMotion) fadeOut(tween(150))
                            else fadeOut(tween(200)) + slideOutHorizontally(tween(260)) { it / 10 }
                        },
                    ) {
                        screen(Routes.ONBOARDING) {
                            OnboardingScreen(onFinished = {
                                navController.navigate(Routes.petEdit()) {
                                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                                }
                            })
                        }
                        screen(Routes.TODAY) { TodayScreen() }
                        screen(Routes.JOURNAL) { JournalScreen() }
                        screen(Routes.INSIGHTS) { InsightsScreen() }
                        screen(Routes.PETS) { PetsScreen() }
                        screen(Routes.SETTINGS) { SettingsScreen() }
                        screen(Routes.REMINDERS) { RemindersHealthScreen() }

                        screen(Routes.PET_DETAIL, listOf(stringArg("petId"))) { entry ->
                            PetDetailScreen(petId = entry.arguments?.getString("petId").orEmpty())
                        }
                        screen(Routes.MEMORIAL, listOf(stringArg("petId"))) { entry ->
                            MemorialScreen(petId = entry.arguments?.getString("petId").orEmpty())
                        }
                        screen(Routes.PET_EDIT, listOf(optionalArg("petId"))) { entry ->
                            PetEditorScreen(
                                petId = entry.arguments?.getString("petId"),
                                onDone = { savedId ->
                                    appVm.selectPet(savedId)
                                    if (navController.previousBackStackEntry == null) {
                                        navController.navigate(Routes.TODAY) {
                                            popUpTo(navController.graph.id) { inclusive = true }
                                        }
                                    } else {
                                        navController.popBackStack()
                                    }
                                },
                            )
                        }
                        screen(Routes.MEDS, listOf(stringArg("petId"))) { entry ->
                            MedsScreen(petId = entry.arguments?.getString("petId").orEmpty())
                        }
                        screen(Routes.MED_EDIT, listOf(stringArg("petId"), optionalArg("medId"))) { entry ->
                            MedicationEditorScreen(
                                petId = entry.arguments?.getString("petId").orEmpty(),
                                medId = entry.arguments?.getString("medId"),
                            )
                        }
                        screen(Routes.CHECKIN, listOf(stringArg("petId"))) { entry ->
                            CheckInScreen(petId = entry.arguments?.getString("petId").orEmpty())
                        }
                        screen(Routes.SYMPTOM_EDIT, listOf(stringArg("petId"), optionalArg("entryId"))) { entry ->
                            SymptomEditorScreen(
                                petId = entry.arguments?.getString("petId").orEmpty(),
                                entryId = entry.arguments?.getString("entryId"),
                            )
                        }
                    }
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
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(Routes.TODAY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun stringArg(name: String): NamedNavArgument = navArgument(name) { type = NavType.StringType }

private fun optionalArg(name: String): NamedNavArgument = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) {
            content(entry)
        }
    }
}
