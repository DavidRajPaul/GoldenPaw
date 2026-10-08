package com.goldenpaw.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.NoMeals
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Vaccines
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goldenpaw.domain.model.ActivityKind
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.model.DocumentType

/*
 * One place that maps app concepts to Material Symbols (Rounded). The UI never shows emoji: icons
 * scale with font size, tint with the theme and read well with TalkBack / VoiceOver. Domain models
 * keep their emoji for plain-text surfaces (widget, share text, notifications).
 */
object GpIcons {
    val Pet: ImageVector get() = Icons.Rounded.Pets
    val Medication: ImageVector get() = Icons.Rounded.Medication
    val CheckIn: ImageVector get() = Icons.Rounded.Mood
    val Weight: ImageVector get() = Icons.Rounded.MonitorWeight
    val Journal: ImageVector get() = Icons.Rounded.EditNote
    val Insights: ImageVector get() = Icons.Rounded.Insights
    val Growth: ImageVector get() = Icons.Rounded.Spa
    val Vet: ImageVector get() = Icons.Rounded.LocalHospital
    val Sparkle: ImageVector get() = Icons.Rounded.AutoAwesome
    val Cloud: ImageVector get() = Icons.Rounded.CloudDone
    val Streak: ImageVector get() = Icons.Rounded.LocalFireDepartment
    val Graduated: ImageVector get() = Icons.Rounded.MilitaryTech
    val Celebrate: ImageVector get() = Icons.Rounded.EmojiEvents
    val Camera: ImageVector get() = Icons.Rounded.CameraAlt
    val Gallery: ImageVector get() = Icons.Rounded.PhotoLibrary
    val Scan: ImageVector get() = Icons.Rounded.DocumentScanner
    val Document: ImageVector get() = Icons.Rounded.Description
    val Vaccine: ImageVector get() = Icons.Rounded.Vaccines

    /** Five faces for the 1..5 check-in scale, worst to best. */
    val faces: List<ImageVector>
        get() = listOf(
            Icons.Rounded.SentimentVeryDissatisfied,
            Icons.Rounded.SentimentDissatisfied,
            Icons.Rounded.SentimentNeutral,
            Icons.Rounded.SentimentSatisfied,
            Icons.Rounded.SentimentVerySatisfied,
        )
}

val Species.icon: ImageVector get() = Icons.Rounded.Pets

val SymptomType.icon: ImageVector
    get() = when (this) {
        SymptomType.VOMITING -> Icons.Rounded.Sick
        SymptomType.DIARRHEA -> Icons.Rounded.Wc
        SymptomType.LIMPING -> Icons.Rounded.Healing
        SymptomType.ACCIDENT -> Icons.Rounded.WaterDrop
        SymptomType.COUGHING -> Icons.Rounded.Air
        SymptomType.LETHARGY -> Icons.Rounded.Bedtime
        SymptomType.NOT_EATING -> Icons.Rounded.NoMeals
        SymptomType.SEIZURE -> Icons.Rounded.Bolt
        SymptomType.CONFUSION -> Icons.Rounded.Psychology
        SymptomType.BREATHING -> Icons.Rounded.MonitorHeart
        SymptomType.SKIN -> Icons.Rounded.Healing
        SymptomType.OTHER -> Icons.Rounded.EditNote
    }

val Badge.icon: ImageVector
    get() = when (this) {
        Badge.FIRST_CHECKIN -> Icons.Rounded.Spa
        Badge.FIRST_DOSE -> Icons.Rounded.Medication
        Badge.PERFECT_DAY -> Icons.Rounded.WbSunny
        Badge.STREAK_7 -> Icons.Rounded.LocalFireDepartment
        Badge.STREAK_30 -> Icons.Rounded.MilitaryTech
        Badge.STREAK_100 -> Icons.Rounded.Favorite
        Badge.PERFECT_WEEK -> Icons.Rounded.Star
        Badge.DOSES_50 -> Icons.Rounded.Pets
        Badge.DOSES_250 -> Icons.Rounded.VolunteerActivism
        Badge.DOSES_1000 -> Icons.Rounded.EmojiEvents
        Badge.CHECKINS_30 -> Icons.Rounded.CalendarMonth
        Badge.CHECKINS_100 -> Icons.Rounded.MenuBook
        Badge.WEIGH_IN_REGULAR -> Icons.Rounded.MonitorWeight
        Badge.CAREFUL_OBSERVER -> Icons.Rounded.Search
        Badge.TEAM_CARE -> Icons.Rounded.Handshake
        Badge.VET_READY -> Icons.Rounded.MedicalServices
    }

val ActivityKind.icon: ImageVector
    get() = when (this) {
        ActivityKind.DOSE_GIVEN -> Icons.Rounded.Medication
        ActivityKind.DOSE_SKIPPED -> Icons.Rounded.SkipNext
        ActivityKind.CHECK_IN -> Icons.Rounded.Mood
        ActivityKind.SYMPTOM -> Icons.Rounded.EditNote
        ActivityKind.WEIGHT -> Icons.Rounded.MonitorWeight
    }

val DocumentType.icon: ImageVector
    get() = when (this) {
        DocumentType.VACCINE_CARD -> Icons.Rounded.Vaccines
        DocumentType.VET_CARD -> Icons.Rounded.LocalHospital
        DocumentType.PRESCRIPTION -> Icons.Rounded.Medication
        DocumentType.LAB_REPORT -> Icons.Rounded.MonitorHeart
        DocumentType.OTHER -> Icons.Rounded.Description
    }

/** An icon on a soft circular tint: the app's replacement for "big emoji" moments. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentDescription: String? = null,
) {
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(size * 0.56f))
    }
}
