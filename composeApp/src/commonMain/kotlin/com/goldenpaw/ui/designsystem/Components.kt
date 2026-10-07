package com.goldenpaw.ui.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.repository.AppFiles
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** App-private file access for composables (photo existence checks). Provided at the root. */
val LocalAppFiles = staticCompositionLocalOf<AppFiles?> { null }

// ------------------------------------------------------------------ Cards & headers

@Composable
fun GpCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            modifier = modifier.pressScale(interaction),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            border = border,
            interactionSource = interaction,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = SectionLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    // A gentle float on the emoji makes empty screens feel alive without demanding attention.
    val reduce = LocalReduceMotion.current
    val float = if (reduce) null else rememberInfiniteTransition(label = "emptyFloat").animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(2200, easing = Motion.Standard), RepeatMode.Reverse),
        label = "emptyFloatY",
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 44.sp, modifier = Modifier.graphicsLayer { translationY = (float?.value ?: 0f) * density })
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: (T) -> Boolean,
    onToggle: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEach { option ->
            val isSelected = selected(option)
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(option) },
                label = { Text(label(option)) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
fun StepProgress(step: Int, total: Int, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = (step + 1f) / total,
        animationSpec = motionOr(Motion.spatialDefault()),
        label = "step",
    )
    Column(modifier) {
        Text(
            "Step ${step + 1} of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ Paw mark

fun DrawScope.drawPaw(color: Color, center: Offset, size: Float) {
    val s = size / 60f
    drawOval(color, topLeft = Offset(center.x - 12.5f * s, center.y + 1f * s), size = Size(25f * s, 21f * s))
    drawOval(color, topLeft = Offset(center.x - 20.7f * s, center.y - 19.6f * s), size = Size(10.4f * s, 13.2f * s))
    drawOval(color, topLeft = Offset(center.x - 11.7f * s, center.y - 28.8f * s), size = Size(10.4f * s, 13.6f * s))
    drawOval(color, topLeft = Offset(center.x + 1.3f * s, center.y - 28.8f * s), size = Size(10.4f * s, 13.6f * s))
    drawOval(color, topLeft = Offset(center.x + 10.3f * s, center.y - 19.6f * s), size = Size(10.4f * s, 13.2f * s))
}

@Composable
fun PawMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) { drawPaw(color, center.copy(y = center.y + size.minDimension * 0.08f), size.minDimension) }
}

// ------------------------------------------------------------------ Avatars

@Composable
fun PetAvatar(
    photoPath: String?,
    species: Species,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    monochrome: Float = 0f,
    selected: Boolean = false,
) {
    val files = LocalAppFiles.current
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(Motion.SHORT4),
        label = "avatarRing",
    )
    val matrix = remember(monochrome) {
        ColorMatrix().apply { setToSaturation(1f - monochrome.coerceIn(0f, 1f)) }
    }
    Box(
        modifier = modifier
            .size(size)
            .border(2.dp, ring, CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val hasPhoto = photoPath != null && (files?.exists(photoPath) ?: true)
        if (hasPhoto) {
            AsyncImage(
                model = photoPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (monochrome > 0f) ColorFilter.colorMatrix(matrix) else null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(species.emoji, fontSize = (size.value * 0.42f).sp)
        }
    }
}

@Composable
fun CaregiverAvatar(caregiver: Caregiver?, modifier: Modifier = Modifier, size: Dp = 32.dp, fallbackName: String = "?") {
    val palette = LocalCaregiverPalette.current
    val color = palette.forIndex(caregiver?.colorIndex ?: 0)
    val initials = caregiver?.initials ?: fallbackName.take(1).uppercase()
    Box(
        modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}

/** Small "Given by Priya" chip tinted with the caregiver's color. */
@Composable
fun AttributionChip(name: String, colorIndex: Int?, modifier: Modifier = Modifier, prefix: String = "") {
    val color = LocalCaregiverPalette.current.forIndex(colorIndex ?: 0)
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Text("$prefix$name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ------------------------------------------------------------------ Wellness ring

/**
 * QoL ring: eased sweep on change plus a slow "breathing" pulse while idle. The sweep and pulse are
 * read only in draw/graphics-layer lambdas, so the ring animates without recomposing.
 */
@Composable
fun WellnessRing(
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    strokeWidth: Dp = 14.dp,
    centerTop: String,
    centerBottom: String,
) {
    val reduceMotion = LocalReduceMotion.current
    val wellness = LocalWellnessColors.current
    val target = fraction ?: 0f
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(target, reduceMotion) {
        if (reduceMotion) sweep.snapTo(target)
        else sweep.animateTo(target, tween(1100, easing = Motion.EmphasizedDecelerate))
    }
    val breathing = if (reduceMotion) null else rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(2600, easing = Motion.Standard), RepeatMode.Reverse),
        label = "breathScale",
    )
    val color = when {
        fraction == null -> wellness.empty
        target >= 0.7f -> wellness.good
        target >= 0.5f -> wellness.okay
        else -> wellness.hard
    }
    val animatedColor by animateColorAsState(color, animationSpec = tween(Motion.LONG2), label = "ringColor")
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val b = breathing?.value ?: 1f
                scaleX = b
                scaleY = b
            }
            .semantics { contentDescription = "Quality of life $centerTop $centerBottom" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val s = sweep.value
            if (s > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(animatedColor.copy(alpha = 0.65f), animatedColor, animatedColor.copy(alpha = 0.65f))),
                    startAngle = -90f,
                    sweepAngle = 360f * s,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(centerBottom, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Emoji scale input

private val faces = listOf("😫", "😟", "😐", "🙂", "😄")

/** Five tappable faces (1..5). Faster and more accessible than a slider, 48dp+ touch targets. */
@Composable
fun EmojiScale(
    label: String,
    question: String,
    lowLabel: String,
    highLabel: String,
    value: Int?,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            faces.forEachIndexed { index, face ->
                val score = index + 1
                val isSelected = value == score
                val scale = animateFloatAsState(
                    targetValue = if (isSelected) 1.18f else if (value == null) 1f else 0.9f,
                    animationSpec = motionOr(Motion.bouncy()),
                    label = "face",
                )
                val bg by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "faceBg",
                )
                val dim = animateFloatAsState(if (value == null || isSelected) 1f else 0.55f, label = "faceAlpha")
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                        .clip(CircleShape)
                        .background(bg)
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onValue(score) }
                        .semantics { contentDescription = "$label $score of 5" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(face, fontSize = 24.sp, modifier = Modifier.graphicsLayer { alpha = dim.value })
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(highLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Severity dots

@Composable
fun SeverityPicker(value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    val wellness = LocalWellnessColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { level ->
            val active = level <= value
            val color = when {
                !active -> MaterialTheme.colorScheme.surfaceVariant
                value >= 4 -> wellness.hard
                value == 3 -> wellness.okay
                else -> wellness.good
            }
            val animated by animateColorAsState(color, label = "sev")
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(animated)
                    .clickable { onValue(level) }
                    .semantics { contentDescription = "Severity $level of 5" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$level",
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Misc

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FadeInVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) { content() }
}

@Composable
fun RowScope.StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.weight(1f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Placeholder card shown while a screen loads (skeleton shimmer instead of a blank page). */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, height: Dp = 96.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.large).shimmer())
}

/** Quiet particle shimmer for milestones: no confetti, no sound, ~40 particles. */
@Composable
fun MilestoneShimmer(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (LocalReduceMotion.current) return
    val transition = rememberInfiniteTransition(label = "shimmer")
    val t = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "t",
    )
    val particles = remember {
        val random = Random(42)
        List(40) { Triple(random.nextFloat(), random.nextFloat(), 0.4f + random.nextFloat() * 0.6f) }
    }
    Canvas(modifier) {
        val time = t.value
        particles.forEach { (px, py, speed) ->
            val phase = (time * speed + py) % 1f
            val x = px * size.width + sin(((phase + px) * 2 * PI).toFloat()) * 6f
            val y = size.height * (1f - phase)
            val alpha = sin((phase * PI).toFloat()).coerceIn(0f, 1f) * 0.6f
            drawCircle(color.copy(alpha = alpha), radius = 1.5f + speed * 2f, center = Offset(x, y))
        }
    }
}
