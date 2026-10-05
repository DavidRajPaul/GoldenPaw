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

private data class PlusFeature(val emoji: String, val title: String, val body: String)

private val features = listOf(
    PlusFeature("🐾", "Every pet in the family", "Track as many dogs and cats as you care for, each with their own meds and history."),
    PlusFeature("👨‍👩‍👧", "Share the care", "Invite family or a sitter. See who gave each dose so nothing is doubled or missed."),
    PlusFeature("☁️", "Cloud backup", "Restore everything on a new phone. Your history is never lost."),
    PlusFeature("📊", "Deeper trends", "Longer history, side-by-side symptom and QoL trends, and weekly summaries."),
)

private enum class Plan(val label: String, val price: String, val detail: String) {
    YEARLY("Yearly", "$39.99 / year", "≈ $3.33 / month · save 44%"),
    MONTHLY("Monthly", "$5.99 / month", "Cancel any time"),
}

/**
 * Soft paywall. Never blocks core logging; shown only when a Plus feature is used.
 * Feature cards use a parallax + subtle depth tilt driven by the pager offset.
 */
@Composable
fun PaywallSheet(
    reason: String,
    isPlus: Boolean,
    onStartTrial: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pager = rememberPagerState { features.size }
    val reduceMotion = LocalReduceMotion.current
    var plan by remember { mutableStateOf(Plan.YEARLY) }
    var unlocked by remember { mutableStateOf(isPlus) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("GoldenPaw Plus", style = MaterialTheme.typography.headlineSmall)
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
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
                        Text(
                            feature.emoji,
                            fontSize = 34.sp,
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
                Pill("Plus unlocked for this beta 🎉")
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
                    Plan.entries.forEach { p ->
                        val selected = plan == p
                        Surface(
                            onClick = { plan = p },
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
                                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                                    Text(p.detail, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(p.price, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Button(
                        onClick = { onStartTrial(); unlocked = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start 7-day free trial") }
                    Text(
                        "Free for 7 days, then ${plan.price}. Cancel any time in Google Play before the trial ends and you won't be charged. " +
                            "Medication reminders and daily logging always stay free.",
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
