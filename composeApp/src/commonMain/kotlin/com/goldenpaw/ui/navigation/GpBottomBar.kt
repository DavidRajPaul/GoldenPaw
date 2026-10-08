package com.goldenpaw.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldenpaw.platform.rememberHaptics
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Motion
import kotlinx.coroutines.launch

/**
 * Floating pill navigation bar.
 *
 * The selection indicator is "liquid": its leading edge (the side it's travelling towards) runs on
 * a stiff spring and its trailing edge on a soft one, so it stretches towards the new tab and then
 * catches up. Both edges are read only in the draw phase. Icons swap from outlined to filled with a
 * small pop, and every switch gives a haptic tick. Reduce motion: the indicator simply moves.
 */
@Composable
fun GpBottomBar(
    current: Route,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = TopLevel.entries
    val selectedIndex = TopLevel.of(current)?.ordinal ?: 0
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()

    val left = remember { Animatable(selectedIndex.toFloat()) }
    val right = remember { Animatable(selectedIndex.toFloat()) }
    var previous by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex, reduceMotion) {
        val target = selectedIndex.toFloat()
        val movingRight = selectedIndex > previous
        previous = selectedIndex
        if (reduceMotion) {
            left.snapTo(target)
            right.snapTo(target)
            return@LaunchedEffect
        }
        val fast = spring<Float>(dampingRatio = 0.8f, stiffness = 900f)
        val slow = spring<Float>(dampingRatio = 0.75f, stiffness = 260f)
        launch { left.animateTo(target, if (movingRight) slow else fast) }
        launch { right.animateTo(target, if (movingRight) fast else slow) }
    }

    val indicator = MaterialTheme.colorScheme.primaryContainer
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(68.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(6.dp)
                .drawBehind {
                    val itemWidth = size.width / items.size
                    val inset = 4.dp.toPx()
                    val l = minOf(left.value, right.value)
                    val r = maxOf(left.value, right.value)
                    drawRoundRect(
                        color = indicator,
                        topLeft = Offset(l * itemWidth + inset, 0f),
                        size = Size((r - l + 1f) * itemWidth - 2 * inset, size.height),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                    )
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(Motion.SHORT4),
                    label = "tabTint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            onClick = {
                                if (!selected) haptics.tick()
                                onSelect(item.route)
                            },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            if (reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else {
                                (scaleIn(spring(dampingRatio = 0.45f, stiffness = 700f), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                                    (scaleOut(tween(100), targetScale = 0.8f) + fadeOut(tween(100)))
                            }
                        },
                        label = "tabIcon",
                    ) { isSelected ->
                        Icon(
                            if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
