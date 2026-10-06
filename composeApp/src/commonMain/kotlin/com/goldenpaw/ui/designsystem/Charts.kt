package com.goldenpaw.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goldenpaw.core.Fmt
import com.goldenpaw.core.epochDay
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.toFixed
import com.goldenpaw.domain.model.DayQuality
import kotlinx.datetime.LocalDate
import kotlin.math.abs

data class ChartPoint(val x: Float, val y: Float, val label: String = "")

/**
 * Line chart whose line "draws on" (path trimming) and then points pop in with a stagger.
 * The path geometry is built once per size/data in drawWithCache; only the trim progress animates.
 */
@Composable
fun AnimatedLineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    minY: Float? = null,
    maxY: Float? = null,
    threshold: Float? = null,
    yLabel: (Float) -> String = { it.toFixed(1) },
    contentDescription: String = "Trend chart",
) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(points) {
        progress.snapTo(if (reduceMotion) 1f else 0f)
        if (!reduceMotion) progress.animateTo(1f, tween(1200, easing = Motion.EmphasizedDecelerate))
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fillTop = lineColor.copy(alpha = 0.22f)
    val pointCenter = MaterialTheme.colorScheme.surfaceContainerLowest
    val textStyle = MaterialTheme.typography.labelSmall

    val description = contentDescription
    Column(modifier.semantics { this.contentDescription = description }) {
        if (points.isEmpty()) return@Column
        val ys = points.map { it.y }
        val rawMin = minY ?: ys.min()
        val rawMax = maxY ?: ys.max()
        val pad = if (minY == null || maxY == null) ((rawMax - rawMin) * 0.15f).coerceAtLeast(0.5f) else 0f
        val lo = rawMin - pad
        val hi = rawMax + pad

        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier.height(180.dp).padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(yLabel(hi), style = textStyle, color = labelColor)
                Text(yLabel((hi + lo) / 2), style = textStyle, color = labelColor)
                Text(yLabel(lo), style = textStyle, color = labelColor)
            }
            Box(
                Modifier.weight(1f).height(180.dp).drawWithCache {
                    val w = size.width
                    val h = size.height
                    val minX = points.minOf { it.x }
                    val maxX = points.maxOf { it.x }
                    val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f
                    val offsets = points.map { p ->
                        Offset(
                            if (points.size == 1) w / 2 else (p.x - minX) / spanX * w,
                            h - (p.y - lo) / (hi - lo) * h,
                        )
                    }
                    val path = Path().apply {
                        offsets.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                    }
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val fill = Path().apply {
                        addPath(path)
                        if (offsets.size > 1) {
                            lineTo(offsets.last().x, h)
                            lineTo(offsets.first().x, h)
                            close()
                        }
                    }
                    val fillBrush = Brush.verticalGradient(listOf(fillTop, Color.Transparent))
                    val lineStroke = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val thresholdY = threshold?.let { t -> h - (t - lo) / (hi - lo) * h }
                    val drawn = Path()
                    onDrawBehind {
                        for (i in 0..2) {
                            val gy = h * i / 2f
                            drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                        }
                        if (thresholdY != null) {
                            drawLine(
                                labelColor.copy(alpha = 0.6f), Offset(0f, thresholdY), Offset(w, thresholdY), strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                            )
                        }
                        val p = progress.value
                        if (offsets.size > 1) drawPath(fill, fillBrush, alpha = p)
                        drawn.reset()
                        measure.getSegment(0f, measure.length * p, drawn, true)
                        drawPath(drawn, lineColor, style = lineStroke)
                        offsets.forEachIndexed { i, o ->
                            val start = i.toFloat() / offsets.size.coerceAtLeast(1)
                            val local = ((p - start) * 3f).coerceIn(0f, 1f)
                            if (local > 0f) {
                                drawCircle(pointCenter, radius = 7f * local, center = o)
                                drawCircle(lineColor, radius = 5f * local, center = o)
                            }
                        }
                    }
                },
            )
        }
        if (points.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp)) {
                Text(points.first().label, style = textStyle, color = labelColor, modifier = Modifier.weight(1f))
                Text(points.last().label, style = textStyle, color = labelColor)
            }
        }
    }
}

/**
 * "Good days" calendar: 5 weeks ending this week (Monday first); cells bloom in a ripple outward
 * from today. One shared Animatable drives every cell, read in each cell's graphics layer.
 */
@Composable
fun GoodDayCalendar(
    days: Map<LocalDate, DayQuality>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    weeks: Int = 5,
) {
    val wellness = LocalWellnessColors.current
    val reduceMotion = LocalReduceMotion.current
    val endOfWeek = today.startOfWeek().plusDays(6)
    val start = endOfWeek.minusDays(weeks * 7 - 1)
    val bloom = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(days) {
        if (!reduceMotion) {
            bloom.snapTo(0f)
            bloom.animateTo(1f, tween(1000, easing = Motion.EmphasizedDecelerate))
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0 until 7) {
                    val date = start.plusDays(week * 7 + d)
                    val quality = days[date]
                    val future = date > today
                    val distance = abs(date.epochDay() - today.epochDay()).toFloat() / (weeks * 7)
                    val color = when (quality) {
                        DayQuality.GOOD -> wellness.good
                        DayQuality.OKAY -> wellness.okay
                        DayQuality.HARD -> wellness.hard
                        null -> wellness.empty
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(vertical = 3.dp)
                            .graphicsLayer {
                                val local = ((bloom.value - distance * 0.7f) / 0.3f).coerceIn(0f, 1f)
                                scaleX = 0.6f + 0.4f * local
                                scaleY = 0.6f + 0.4f * local
                                alpha = if (future) 0.25f else local
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(color)
                            .semantics {
                                contentDescription = "${Fmt.dayMonth(date)} ${quality?.label ?: "no check-in"}"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (quality != null) wellness.onGood else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (date == today) {
                            Box(
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp)
                                    .clip(CircleShape).background(MaterialTheme.colorScheme.onSurface),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(wellness.good, "Good")
            Legend(wellness.okay, "Okay")
            Legend(wellness.hard, "Hard")
            Legend(wellness.empty, "No check-in")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Horizontal bar for a 0..10 HHHHHMM component. */
@Composable
fun ScoreBar(label: String, value: Double, max: Double = 10.0, modifier: Modifier = Modifier) {
    val wellness = LocalWellnessColors.current
    val target = (value / max).toFloat().coerceIn(0f, 1f)
    val fraction = animateFloatAsState(target, animationSpec = motionOr(tween(800, easing = Motion.EmphasizedDecelerate)), label = "bar")
    val color = when {
        target >= 0.7f -> wellness.good
        target >= 0.45f -> wellness.okay
        else -> wellness.hard
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.38f))
        Canvas(Modifier.weight(0.5f).height(10.dp)) {
            val r = size.height / 2
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            drawRoundRect(
                color,
                size = size.copy(width = size.width * fraction.value),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        }
        Text(
            value.toFixed(1),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.12f),
        )
    }
}

/** Horizontal contribution bar used in the care team summary. */
@Composable
fun ContributionBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated = animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = motionOr(Motion.spatialSlow()), label = "contrib")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.height(8.dp).fillMaxWidth()) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        drawRoundRect(color, size = size.copy(width = size.width * animated.value), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
    }
}

@Suppress("unused")
private fun Modifier.clipCircle() = clip(CircleShape)
