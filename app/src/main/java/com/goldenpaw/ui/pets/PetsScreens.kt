package com.goldenpaw.ui.pets

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.ui.common.ConfirmDialog
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LabeledValue
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.PetAvatar
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.StatTile
import com.goldenpaw.ui.koinVm
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Routes
import com.goldenpaw.ui.navigation.sharedPetPhoto

// ------------------------------------------------------------------ List

@Composable
fun PetsScreen() {
    val vm: PetsViewModel = koinVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current

    val addPet = {
        if (actions.canAddPet()) actions.navigate(Routes.petEdit())
        else actions.showPaywall("Caring for more than one pet is part of GoldenPaw Plus.")
    }

    Scaffold(
        topBar = { GpTopBar("Your pets") },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = addPet,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add pet") },
            )
        },
    ) { padding ->
        if (!state.loading && state.active.isEmpty() && state.archived.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState("🐕", "No pets yet", "Add your companion to get started.") {
                    Button(onClick = addPet) { Text("Add a pet") }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.active, key = { it.id }) { pet ->
                PetCard(pet, isCurrent = pet.id == state.selectedId) { actions.navigate(Routes.petDetail(pet.id)) }
            }
            if (state.archived.isNotEmpty()) {
                item { SectionHeader("Memories") }
                items(state.archived, key = { "m-" + it.id }) { pet ->
                    GpCard(onClick = { actions.navigate(Routes.memorial(pet.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PetAvatar(pet.photoPath, pet.species, size = 52.dp, monochrome = 1f)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(pet.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    lifespan(pet),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun lifespan(pet: Pet): String {
    val start = pet.birthDate?.year?.toString() ?: "?"
    val end = pet.archivedAt?.atZone(java.time.ZoneId.systemDefault())?.year?.toString() ?: ""
    return "Remembered · $start – $end"
}

@Composable
private fun PetCard(pet: Pet, isCurrent: Boolean, onClick: () -> Unit) {
    GpCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.sharedPetPhoto(pet.id)) {
                PetAvatar(pet.photoPath, pet.species, size = 72.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pet.name, style = MaterialTheme.typography.titleLarge)
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Pill("Current", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                val sub = listOfNotNull(pet.breed.takeIf { it.isNotBlank() } ?: pet.species.label, pet.ageLabel())
                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pet.conditions.isNotEmpty() || pet.isSenior()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (pet.isSenior()) Pill("Senior", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                        pet.conditions.take(4).forEach { Pill(it) }
                        if (pet.conditions.size > 4) Pill("+${pet.conditions.size - 4}")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Detail

@Composable
fun PetDetailScreen(petId: String) {
    val vm: PetDetailViewModel = koinVm()
    LaunchedEffect(petId) { vm.load(petId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var confirmArchive by remember { mutableStateOf(false) }
    val pet = state.pet

    Scaffold(
        topBar = {
            GpTopBar(pet?.name ?: "", onBack = actions.back) {
                if (pet != null) {
                    IconButton(onClick = { actions.navigate(Routes.petEdit(pet.id)) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit ${pet.name}")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (pet == null) return@Scaffold
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.sharedPetPhoto(pet.id)) {
                        PetAvatar(pet.photoPath, pet.species, size = 148.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(pet.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        listOfNotNull(pet.breed.takeIf { it.isNotBlank() } ?: pet.species.label, pet.ageLabel()).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pet.isArchived) {
                        Spacer(Modifier.height(8.dp))
                        Pill("In memories")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(state.latestWeight?.let { UnitConversion.format(it.weightKg, state.weightUnit) } ?: "–", "Latest weight")
                    StatTile("${state.dosesGiven}", "Doses given")
                    StatTile("${state.meds.count { it.isActive }}", "Active meds")
                }
            }
            item {
                GpCard {
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Species", pet.species.label, Modifier.weight(1f))
                        LabeledValue("Sex", pet.sex.label, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Born", pet.birthDate?.format(Formats.dayMonthYear) ?: "Not set", Modifier.weight(1f))
                        LabeledValue("Breed", pet.breed.ifBlank { "Not set" }, Modifier.weight(1f))
                    }
                    if (pet.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        LabeledValue("Notes", pet.notes)
                    }
                }
            }
            item {
                SectionHeader("Conditions")
                if (pet.conditions.isEmpty()) {
                    Text("None recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pet.conditions.forEach { Pill(it) }
                    }
                }
            }
            item {
                SectionHeader("Medications", action = "Manage", onAction = { actions.navigate(Routes.meds(pet.id)) })
                GpCard(onClick = { actions.navigate(Routes.meds(pet.id)) }) {
                    val active = state.meds.filter { it.isActive }
                    if (active.isEmpty()) {
                        Text("No active medications", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        active.forEachIndexed { i, med ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("${med.name} · ${med.doseLabel}", style = MaterialTheme.typography.titleSmall)
                            Text(med.scheduleSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (pet.vetName.isNotBlank() || pet.vetPhone.isNotBlank()) {
                item {
                    SectionHeader("Vet")
                    GpCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pet.vetName.ifBlank { "Vet" }, style = MaterialTheme.typography.titleMedium)
                                if (pet.vetPhone.isNotBlank()) Text(pet.vetPhone, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (pet.vetPhone.isNotBlank()) {
                                IconButton(onClick = {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${pet.vetPhone}"))
                                    runCatching { context.startActivity(intent) }
                                }) { Icon(Icons.Outlined.Phone, contentDescription = "Call vet") }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                if (!pet.isArchived) {
                    if (state.loading.not()) {
                        OutlinedButton(onClick = { actions.selectPet(pet.id); actions.goHome() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Show ${pet.name} on Today")
                        }
                    }
                    TextButton(onClick = { confirmArchive = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Move to memories", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    OutlinedButton(onClick = { actions.navigate(Routes.memorial(pet.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open memorial")
                    }
                }
            }
        }
    }

    if (confirmArchive && pet != null) {
        ConfirmDialog(
            title = "Move ${pet.name} to memories?",
            body = "Reminders will stop and ${pet.name}'s history will be kept safe in a quiet memorial space. " +
                "You can restore them at any time.",
            confirm = "Move to memories",
            onConfirm = { vm.archive { actions.back() } },
            onDismiss = { confirmArchive = false },
        )
    }
}

// ------------------------------------------------------------------ Memorial

@Composable
fun MemorialScreen(petId: String) {
    val vm: PetDetailViewModel = koinVm()
    LaunchedEffect(petId) { vm.load(petId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val reduceMotion = LocalReduceMotion.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val pet = state.pet

    // Slow fade to monochrome
    val mono = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(pet?.id) {
        if (pet != null && !reduceMotion) mono.animateTo(1f, tween(2600, easing = FastOutSlowInEasing))
    }
    // Candle-glow breathing gradient
    val glow = if (reduceMotion) 0.5f else {
        val t = rememberInfiniteTransition(label = "candle")
        val v by t.animateFloat(0.35f, 0.65f, infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "glow")
        v
    }

    Scaffold(
        topBar = { GpTopBar("", onBack = actions.back) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (pet == null) return@Scaffold
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Color(0xFFFFC56B).copy(alpha = glow * 0.35f), Color.Transparent),
                    radius = 900f,
                ),
            ),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 28.dp, end = 28.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { PetAvatar(pet.photoPath, pet.species, size = 180.dp, monochrome = mono.value) }
                item { Text("In loving memory", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                item { Text(pet.name, style = MaterialTheme.typography.displaySmall) }
                item {
                    Text(lifespan(pet).removePrefix("Remembered · "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Text(
                        "You gave ${pet.name} ${state.dosesGiven} doses of care" +
                            if (state.weightCount > 0) " and kept watch through ${state.weightCount} weigh-ins." else ".",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        "Every good day, remembered.",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { vm.restore() }, modifier = Modifier.fillMaxWidth()) { Text("Restore to active pets") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete all of ${pet.name}'s data", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && pet != null) {
        ConfirmDialog(
            title = "Delete ${pet.name}'s data?",
            body = "This permanently removes ${pet.name}'s profile, medications, check-ins, weights and journal. This can't be undone.",
            confirm = "Delete forever",
            onConfirm = { vm.deleteForever { actions.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
