package com.goldenpaw.ui.achievements

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goldenpaw.LocalGamification
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.BadgeProgress
import com.goldenpaw.domain.model.BadgeTier
import com.goldenpaw.domain.model.GamificationState
import com.goldenpaw.platform.rememberHaptics
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.designsystem.AnimatedCounter
import com.goldenpaw.ui.designsystem.BadgeMedallion
import com.goldenpaw.ui.designsystem.CareRingsView
import com.goldenpaw.ui.designsystem.CelebrationBurst
import com.goldenpaw.ui.designsystem.ContributionBar
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LevelBar
import com.goldenpaw.ui.designsystem.LocalCaregiverPalette
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.StreakDots
import com.goldenpaw.ui.designsystem.rememberStaggerState
import com.goldenpaw.ui.designsystem.staggerIn
import com.goldenpaw.ui.navigation.LocalAppActions

fun BadgeTier.color(): Color = when (this) {
    BadgeTier.BRONZE -> Color(0xFFC98B5B)
    BadgeTier.SILVER -> Color(0xFF9AA3AD)
    BadgeTier.GOLD -> Color(0xFFE2A93B)
}

@Composable
fun AchievementsScreen() {
    val g = LocalGamification.current
    val actions = LocalAppActions.current
    Scaffold(
        topBar = { GpTopBar("Care journey", onBack = actions.back) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (g == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "🌿",
                    "Gentle mode is on",
                    "Streaks, levels and badges are hidden. You can turn them back on in Settings.",
                )
            }
            return@Scaffold
        }
        AchievementsContent(g, PaddingValues(
            start = 20.dp, end = 20.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ))
    }
}

@Composable
private fun AchievementsContent(g: GamificationState, padding: PaddingValues) {
    val stagger = rememberStaggerState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "level", span = { GridItemSpan(maxLineSpan) }) {
            GpCard(modifier = Modifier.staggerIn(stagger, "level", 0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Care points", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedCounter(g.points, style = MaterialTheme.typography.displaySmall)
                        if (g.pointsToday > 0) {
                            Text("+${g.pointsToday} today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    CareRingsView(g.rings)
                }
                Spacer(Modifier.height(14.dp))
                LevelBar(
                    title = g.level.title,
                    levelNumber = g.level.number,
                    progress = g.levelProgress,
                    caption = g.nextLevel?.let { "${it.minPoints - g.points} points to ${it.title}" } ?: "Top level reached",
                )
            }
        }
        item(key = "streak", span = { GridItemSpan(maxLineSpan) }) {
            GpCard(modifier = Modifier.staggerIn(stagger, "streak", 1)) {
                val s = g.streak
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.graduated) "🏅" else "🔥", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (s.graduated) "Care is a habit now" else "${s.current}-day care streak",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                append("Best ${s.best} days")
                                append(" · ${s.restDaysLeftThisWeek} of ${s.restDaysPerWeek} rest days left this week")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                StreakDots(s.lastSeven)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Busy day? Rest days cover it automatically, so a gap never wipes out your progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (g.team.size > 1) {
            item(key = "team", span = { GridItemSpan(maxLineSpan) }) {
                val palette = LocalCaregiverPalette.current
                val total = g.team.sumOf { it.count }.coerceAtLeast(1)
                GpCard(modifier = Modifier.staggerIn(stagger, "team", 2)) {
                    Text("Team care this week", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    g.team.forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                            ContributionBar(c.count.toFloat() / total, palette.forIndex(i), Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text("${c.count}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (g.memories.isNotEmpty()) {
            item(key = "memories", span = { GridItemSpan(maxLineSpan) }) {
                GpCard(modifier = Modifier.staggerIn(stagger, "memories", 3), containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("On this day", style = MaterialTheme.typography.titleMedium)
                    g.memories.forEach { m ->
                        Spacer(Modifier.height(8.dp))
                        Text("${m.label} · ${Formats.dayMonthYear(m.date)}", style = MaterialTheme.typography.labelMedium)
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item(key = "badgesHeader", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Badges · ${g.unlockedCount} of ${g.badges.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(g.badges, key = { it.badge.id }) { b ->
            BadgeTile(b, Modifier.animateItem())
        }
    }
}

@Composable
private fun BadgeTile(b: BadgeProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BadgeMedallion(b.badge.emoji, unlocked = b.unlocked, tierColor = b.badge.tier.color())
        Spacer(Modifier.height(6.dp))
        Text(
            b.badge.title,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (b.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!b.unlocked && b.progress > 0f) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.width(64.dp))
            b.progressLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Text(
                b.unlockedAt?.let { Formats.dayMonthYear(Formats.date(it)) } ?: b.badge.description,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
    }
}

/** Shown once when the care log earns new badges. Bouncy medallion, burst, short copy. */
@Composable
fun BadgeCelebrationDialog(badges: List<Badge>, onDismiss: () -> Unit, onSeeAll: () -> Unit) {
    val first = badges.firstOrNull() ?: return
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val scale = remember(first) { Animatable(if (reduceMotion) 1f else 0.4f) }
    LaunchedEffect(first) {
        haptics.confirm()
        if (!reduceMotion) scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(contentAlignment = Alignment.Center) {
                CelebrationBurst(trigger = first.id, modifier = Modifier.width(220.dp).height(160.dp))
                BadgeMedallion(
                    first.emoji,
                    unlocked = true,
                    tierColor = first.tier.color(),
                    size = 88.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    },
                )
            }
        },
        title = { Text(if (badges.size == 1) first.title else "${badges.size} new badges", textAlign = TextAlign.Center) },
        text = {
            Text(
                if (badges.size == 1) first.description else badges.joinToString(" · ") { "${it.emoji} ${it.title}" },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Lovely") } },
        dismissButton = { TextButton(onClick = onSeeAll) { Text("See all badges") } },
    )
}
