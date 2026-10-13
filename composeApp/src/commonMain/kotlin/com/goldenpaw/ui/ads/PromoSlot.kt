package com.goldenpaw.ui.ads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.goldenpaw.data.ads.PromotionsService
import com.goldenpaw.domain.ads.AdDecision
import com.goldenpaw.domain.ads.AdPlacement
import com.goldenpaw.domain.ads.HouseCreative
import com.goldenpaw.ui.LaunchRequest
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.MotionSpecs
import com.goldenpaw.ui.designsystem.collapseTransition
import com.goldenpaw.ui.designsystem.expandTransition
import com.goldenpaw.ui.navigation.LocalAppActions
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The only composable that can show a promotion. It asks the calm promotions engine whether this
 * [placement] may show something now (caps, cooldowns, quiet days, Plus, holdout: see
 * docs/MONETIZATION_ADS.md) and renders nothing at all otherwise: no empty box, no reserved space.
 */
@Composable
fun PromoSlot(placement: AdPlacement, modifier: Modifier = Modifier) {
    val promotions: PromotionsService = koinInject()
    val actions = LocalAppActions.current
    val scope = rememberCoroutineScope()
    val revision by promotions.revision.collectAsState()
    val catalog by promotions.catalog.collectAsState()
    var decision by remember { mutableStateOf<AdDecision?>(null) }
    LaunchedEffect(placement, revision, catalog.version) { decision = promotions.decide(placement) }

    val current = decision
    val house = (current as? AdDecision.ShowHouse)?.creative
    LaunchedEffect(current) {
        // Counted only when something is actually on screen. Network ads render once an SDK exists.
        if (current is AdDecision.ShowHouse) promotions.onShown(placement, current)
    }

    AnimatedVisibility(
        visible = house != null,
        enter = expandTransition(MotionSpecs.ExpandPreset.Inline),
        exit = collapseTransition(MotionSpecs.ExpandPreset.Inline),
        modifier = modifier,
    ) {
        val creative = house ?: return@AnimatedVisibility
        HouseCard(
            creative = creative,
            onOpen = {
                scope.launch { promotions.onClicked(current!!) }
                actions.openLink(LaunchRequest(open = creative.open, petId = null, offerId = creative.offerId))
            },
            onHide = { scope.launch { promotions.onHidden(current!!) } },
        )
    }
}

/** Our own card: labelled, calm, one action, always hideable. */
@Composable
internal fun HouseCard(creative: HouseCreative, onOpen: () -> Unit, onHide: () -> Unit) {
    GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            "From GoldenPaw",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(creative.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (creative.body.isNotBlank()) {
            Text(creative.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onHide) { Text("Hide") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onOpen) { Text(creative.cta) }
        }
    }
}

/**
 * "Watch a short video to read this week's summary": shown only when a network can serve a rewarded
 * ad right now (never with [com.goldenpaw.domain.ads.NoAdNetwork]), and only when the owner taps it.
 */
@Composable
fun RewardedUnlockButton(onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    val promotions: PromotionsService = koinInject()
    val scope = rememberCoroutineScope()
    val revision by promotions.revision.collectAsState()
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        available = promotions.decide(AdPlacement.WEEKLY_SUMMARY_REWARD) is AdDecision.ShowNetwork
    }
    if (!available) return
    OutlinedButton(
        onClick = { scope.launch { if (promotions.watchRewarded(AdPlacement.WEEKLY_SUMMARY_REWARD)) onUnlocked() } },
        modifier = modifier.fillMaxWidth(),
    ) { Text("Or watch a short video to read this week's") }
}
