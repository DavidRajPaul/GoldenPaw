package com.goldenpaw.ui.paywall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Pill
import kotlin.math.absoluteValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.goldenpaw.ui.designsystem.GpIcons
import com.goldenpaw.ui.designsystem.IconBadge
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextDecoration
import com.goldenpaw.domain.model.PricingCatalog
import com.goldenpaw.domain.model.PricingPlan
import com.goldenpaw.domain.model.PromoOffer
import kotlinx.datetime.Instant


private data class PlusFeature(val icon: ImageVector, val title: String, val body: String)

private val features = listOf(
    PlusFeature(GpIcons.Pet, "Every pet in the family", "Track as many dogs and cats as you care for, each with their own meds and history."),
    PlusFeature(GpIcons.Sparkle, "Weekly summaries", "A short, kind write-up of each week with things worth asking your vet. Not veterinary advice."),
    PlusFeature(GpIcons.Cloud, "Cloud backup", "Restore everything on a new phone. Your history is never lost."),
    PlusFeature(GpIcons.Insights, "Deeper trends", "Longer history and side-by-side symptom and quality-of-life trends."),
)

/**
 * Soft paywall. Never blocks core logging; shown only when a Plus feature is used, or when the owner
 * taps an offer notification. Plans, prices, trial length, offers and button copy all come from the
 * backend ([PricingCatalog]); the bundled default is used until the first fetch succeeds.
 * Feature cards use a parallax + subtle depth tilt driven by the pager offset.
 */
@Composable
fun PaywallSheet(
    reason: String,
    isPlus: Boolean,
    catalog: PricingCatalog,
    now: Instant,
    onStartTrial: (PricingPlan) -> Unit,
    onDismiss: () -> Unit,
    offerId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pager = rememberPagerState { features.size }
    val reduceMotion = LocalReduceMotion.current
    val plans = catalog.visiblePlans
    val offer = remember(catalog, offerId) { catalog.activeOffer(now, offerId) }
    var planId by remember(catalog.version) { mutableStateOf(offer?.planId?.takeIf { id -> plans.any { it.id == id } } ?: catalog.defaultPlan.id) }
    val plan = plans.firstOrNull { it.id == planId } ?: catalog.defaultPlan
    var unlocked by remember { mutableStateOf(isPlus) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(catalog.paywall.headline, style = MaterialTheme.typography.headlineSmall)
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            if (offer != null && !unlocked) {
                OfferBanner(offer, Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(12.dp))

            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 48.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.height(190.dp),
            ) { page ->
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val feature = features[page]
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().height(180.dp).graphicsLayer {
                        if (!reduceMotion) {
                            rotationY = offset * -12f
                            cameraDistance = 12f * density
                            val scale = 1f - 0.08f * offset.absoluteValue.coerceIn(0f, 1f)
                            scaleX = scale
                            scaleY = scale
                        }
                        alpha = 1f - 0.4f * offset.absoluteValue.coerceIn(0f, 1f)
                    },
                ) {
                    Column(Modifier.padding(20.dp)) {
                        IconBadge(
                            feature.icon,
                            size = 52.dp,
                            modifier = Modifier.graphicsLayer { if (!reduceMotion) translationX = offset * 60f },
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(feature.title, style = MaterialTheme.typography.titleMedium)
                        Text(feature.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (unlocked) {
                Pill("Plus unlocked for this beta")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Thanks for trying Plus. Billing isn't connected in this test build, so nothing will be charged.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDismiss, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(50.dp)) { Text("Continue") }
            } else {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    plans.forEach { p ->
                        val selected = plan.id == p.id
                        val discounted = offer?.takeIf { it.planId == p.id && it.priceLabel.isNotBlank() }
                        Surface(
                            onClick = { planId = p.id },
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                                        val ribbon = discounted?.badge?.ifBlank { null } ?: p.badge
                                        if (ribbon.isNotBlank()) Pill(ribbon)
                                    }
                                    if (p.detail.isNotBlank()) Text(p.detail, style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (discounted != null) {
                                        Text(discounted.priceLabel, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            p.priceLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textDecoration = TextDecoration.LineThrough,
                                        )
                                    } else {
                                        Text(p.priceLabel, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    val cta = if (plan.trialDays > 0) {
                        catalog.paywall.trialCta.replace("{days}", plan.trialDays.toString())
                    } else {
                        catalog.paywall.buyCta
                    }
                    Button(
                        onClick = { onStartTrial(plan); unlocked = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(cta) }
                    Text(
                        paywallTerms(plan, offer) + " " + catalog.paywall.footer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
                }
            }
        }
    }
}

/** The small print under the button: what happens after the trial, in the plan's own words. */
internal fun paywallTerms(plan: PricingPlan, offer: PromoOffer?): String {
    val price = offer?.takeIf { it.planId == plan.id && it.priceLabel.isNotBlank() }?.priceLabel ?: plan.priceLabel
    return if (plan.trialDays > 0) {
        "Free for ${plan.trialDays} days, then $price. Cancel any time in your app store settings before the trial ends and you won't be charged."
    } else {
        "$price. Cancel any time in your app store settings."
    }
}

@Composable
private fun OfferBanner(offer: PromoOffer, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(GpIcons.Sparkle, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(offer.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                if (offer.body.isNotBlank()) {
                    Text(offer.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}
