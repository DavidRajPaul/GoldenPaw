package com.goldenpaw.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenpaw.domain.model.CareRings
import com.goldenpaw.domain.model.DayMark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A single soft burst of paper and paw dots for unlocking a badge. One Animatable drives every
 * particle in the draw phase (no recomposition per frame); capped at 48 particles; skipped
 * entirely with reduce motion.
 */
@Composable
fun CelebrationBurst(trigger: Any, modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) return
    val colors = listOf(GpColors.Honey, GpColors.Sage, GpColors.Clay, GpColors.HoneyLight, GpColors.SageLight)
    val particles = remember(trigger) {
        val r = Random(trigger.hashCode())
        List(48) {
            val angle = r.nextFloat() * 2f * PI.toFloat()
            val speed = 0.35f + r.nextFloat() * 0.65f
            Particle(
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 0.55f,
                size = 5f + r.nextFloat() * 7f,
                spin = (r.nextFloat() - 0.5f) * 720f,
                color = colors[r.nextInt(colors.size)],
                round = r.nextBoolean(),
            )
        }
    }
    val t = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { t.animateTo(1f, tween(1400, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize()) {
        val time = t.value
        if (time >= 1f) return@Canvas
        val origin = Offset(size.width / 2f, size.height * 0.42f)
        val reach = size.minDimension * 0.55f
        particles.forEach { p ->
            val x = origin.x + p.vx * reach * time
            val y = origin.y + p.vy * reach * time + 0.9f * reach * time * time // gravity
            val alpha = (1f - time).coerceIn(0f, 1f)
            if (p.round) {
                drawCircle(p.color.copy(alpha = alpha), radius = p.size / 2f, center = Offset(x, y))
            } else {
                rotate(p.spin * time, pivot = Offset(x, y)) {
                    drawRect(p.color.copy(alpha = alpha), topLeft = Offset(x - p.size / 2, y - p.size / 4), size = Size(p.size, p.size / 2))
                }
            }
        }
    }
}

private data class Particle(
    val vx: Float,
    val vy: Float,
    val size: Float,
    val spin: Float,
    val color: Color,
    val round: Boolean,
)

/**
 * Apple-style concentric rings for today's care: outer = doses given, inner = check-in.
 * Rings reset every day; a closed ring gets a subtle glow, never a scolding.
 */
@Composable
fun CareRingsView(rings: CareRings, modifier: Modifier = Modifier, size: Dp = 92.dp) {
    val wellness = LocalWellnessColors.current
    val reduce = LocalReduceMotion.current
    val dose = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(rings.doseFraction) {
        if (reduce) dose.snapTo(rings.doseFraction) else dose.animateTo(rings.doseFraction, Motion.spatialSlow())
    }
    LaunchedEffect(rings.checkInFraction) {
        if (reduce) check.snapTo(rings.checkInFraction) else check.animateTo(rings.checkInFraction, Motion.spatialSlow())
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val doseColor = MaterialTheme.colorScheme.primary
    val checkColor = wellness.good
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = "Today: ${rings.dosesGiven} of ${rings.dosesDue} doses, " +
                    if (rings.checkedIn) "checked in" else "no check-in yet"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.12f
            fun ring(inset: Float, fraction: Float, color: Color) {
                val topLeft = Offset(inset + stroke / 2, inset + stroke / 2)
                val arc = Size(this.size.width - 2 * inset - stroke, this.size.height - 2 * inset - stroke)
                drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0f) {
                    drawArc(color, -90f, 360f * fraction, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            ring(0f, dose.value, doseColor)
            ring(stroke * 1.35f, check.value, checkColor)
        }
        if (rings.allClosed) {
            Icon(
                GpIcons.Pet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.26f),
            )
        }
    }
}

/** Seven small dots for the last week of the streak: filled = cared, ring = rest day, faint = missed. */
@Composable
fun StreakDots(marks: List<DayMark>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        marks.forEachIndexed { i, mark ->
            val isToday = i == marks.lastIndex
            Canvas(Modifier.size(if (isToday) 12.dp else 10.dp)) {
                val r = this.size.minDimension / 2
                when (mark) {
                    DayMark.CARED -> drawCircle(color, r)
                    DayMark.REST -> drawCircle(color, r - 1.5f, style = Stroke(3f))
                    DayMark.MISSED -> drawCircle(faint, r)
                    DayMark.PENDING -> drawCircle(color.copy(alpha = 0.35f), r, style = Stroke(3f))
                }
            }
        }
    }
}

/** Big badge medallion used in the badge grid and the unlock dialog. */
@Composable
fun BadgeMedallion(icon: ImageVector, unlocked: Boolean, tierColor: Color, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (unlocked) 1f else 0.45f }
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(if (unlocked) tierColor.copy(alpha = 0.22f) else Color.Gray.copy(alpha = 0.18f))
            drawCircle(if (unlocked) tierColor else Color.Gray.copy(alpha = 0.5f), style = Stroke(this.size.minDimension * 0.06f))
        }
        Icon(
            icon,
            contentDescription = null,
            tint = if (unlocked) tierColor else Color.Gray,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
fun LevelBar(title: String, levelNumber: Int, progress: Float, caption: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Level $levelNumber", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        ContributionBar(progress, MaterialTheme.colorScheme.primary)
    }
}
