package com.goldenpaw.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.goldenpaw.ui.designsystem.LocalReduceMotion

object Routes {
    const val ONBOARDING = "onboarding"
    const val TODAY = "today"
    const val JOURNAL = "journal"
    const val INSIGHTS = "insights"
    const val PETS = "pets"
    const val SETTINGS = "settings"
    const val REMINDERS = "reminders"

    const val PET_DETAIL = "pet/{petId}"
    const val PET_EDIT = "petEdit?petId={petId}"
    const val MEMORIAL = "memorial/{petId}"
    const val MEDS = "meds/{petId}"
    const val MED_EDIT = "medEdit/{petId}?medId={medId}"
    const val CHECKIN = "checkin/{petId}"
    const val SYMPTOM_EDIT = "symptomEdit/{petId}?entryId={entryId}"

    fun petDetail(id: String) = "pet/$id"
    fun petEdit(id: String? = null) = if (id == null) "petEdit" else "petEdit?petId=$id"
    fun memorial(id: String) = "memorial/$id"
    fun meds(petId: String) = "meds/$petId"
    fun medEdit(petId: String, medId: String? = null) = if (medId == null) "medEdit/$petId" else "medEdit/$petId?medId=$medId"
    fun checkIn(petId: String) = "checkin/$petId"
    fun symptomEdit(petId: String, entryId: String? = null) =
        if (entryId == null) "symptomEdit/$petId" else "symptomEdit/$petId?entryId=$entryId"
}

enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", Icons.Outlined.WbSunny),
    JOURNAL(Routes.JOURNAL, "Journal", Icons.Outlined.Book),
    INSIGHTS(Routes.INSIGHTS, "Insights", Icons.Outlined.ShowChart),
    PETS(Routes.PETS, "Pets", Icons.Outlined.Pets),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
}

/** App-wide actions screens can trigger without threading callbacks through every layer. */
data class AppActions(
    val navigate: (String) -> Unit,
    val back: () -> Unit,
    val goHome: () -> Unit,
    val showPaywall: (reason: String) -> Unit,
    val selectPet: (String) -> Unit,
    val canAddPet: () -> Boolean,
)

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element hook for the pet photo (list -> detail). No-op with reduce motion. */
@Composable
fun Modifier.sharedPetPhoto(petId: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (LocalReduceMotion.current) return this
    val base = this
    return with(shared) {
        base.sharedElement(
            state = rememberSharedContentState(key = "pet-photo-$petId"),
            animatedVisibilityScope = animated,
        )
    }
}
