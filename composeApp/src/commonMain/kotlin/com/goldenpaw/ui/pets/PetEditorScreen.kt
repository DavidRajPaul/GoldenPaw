package com.goldenpaw.ui.pets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.core.firstOfMonth
import com.goldenpaw.core.minusMonths
import com.goldenpaw.domain.model.PetCatalog
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.Species
import com.goldenpaw.platform.rememberPhotoPicker
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.NumberField
import com.goldenpaw.ui.common.rememberToday
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.PetAvatar
import com.goldenpaw.ui.designsystem.StepProgress
import com.goldenpaw.ui.navigation.LocalAppActions
import org.koin.compose.viewmodel.koinViewModel


@Composable
fun PetEditorScreen(petId: String?, onDone: (String) -> Unit) {
    val vm: PetEditorViewModel = koinViewModel()
    LaunchedEffect(petId) { vm.load(petId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val reduceMotion = LocalReduceMotion.current

    val photoPicker = rememberPhotoPicker { path -> vm.photoPicked(path) }

    Scaffold(
        topBar = {
            GpTopBar(
                title = if (state.isNew) "Add a pet" else "Edit ${state.name}",
                onBack = { if (state.step > 0) vm.back() else actions.back() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.step > 0) {
                        OutlinedButton(onClick = vm::back, modifier = Modifier.weight(1f).height(50.dp)) { Text("Back") }
                    }
                    val last = state.step == 2
                    Button(
                        onClick = { if (last) vm.save(onDone) else vm.next() },
                        enabled = state.canProceed && !state.saving && state.name.isNotBlank(),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Text(if (last) (if (state.isNew) "Save ${state.name.ifBlank { "pet" }}" else "Save changes") else "Next")
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            StepProgress(state.step, 3, Modifier.padding(vertical = 8.dp))
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    else {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(Motion.MEDIUM2, easing = Motion.EmphasizedDecelerate)) { it / 4 * dir } + fadeIn(tween(Motion.MEDIUM2))) togetherWith
                            (slideOutHorizontally(tween(Motion.SHORT4, easing = Motion.EmphasizedAccelerate)) { -it / 4 * dir } + fadeOut(tween(Motion.SHORT2)))
                    }
                },
                label = "petStep",
                modifier = Modifier.weight(1f),
            ) { step ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (step) {
                        0 -> StepBasics(
                            state = state,
                            onPickPhoto = {
                                vm.photoPicking()
                                photoPicker()
                            },
                            vm = vm,
                        )
                        1 -> StepDetails(state, vm)
                        else -> StepHealth(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBasics(state: PetEditorState, onPickPhoto: () -> Unit, vm: PetEditorViewModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onPickPhoto)) {
                PetAvatar(state.photoPath, state.species, size = 128.dp)
            }
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                IconButton(onClick = onPickPhoto, modifier = Modifier.size(44.dp)) {
                    if (state.photoImporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Outlined.PhotoCamera, contentDescription = "Choose photo")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Add a photo (optional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Species", style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Species.entries.forEachIndexed { i, sp ->
            SegmentedButton(
                selected = state.species == sp,
                onClick = { vm.update { it.copy(species = sp, breed = if (it.species != sp) "" else it.breed) } },
                shape = SegmentedButtonDefaults.itemShape(i, Species.entries.size),
            ) { Text("${sp.emoji} ${sp.label}") }
        }
    }
    OutlinedTextField(
        value = state.name,
        onValueChange = { v -> vm.update { it.copy(name = v.take(40)) } },
        label = { Text("Name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StepDetails(state: PetEditorState, vm: PetEditorViewModel) {
    val breeds = PetCatalog.breedsFor(state.species)
    val query = state.breed.trim()
    val suggestions = if (query.isEmpty()) breeds.take(8)
    else breeds.filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }.take(8)

    OutlinedTextField(
        value = state.breed,
        onValueChange = { v -> vm.update { it.copy(breed = v.take(60)) } },
        label = { Text("Breed") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    if (suggestions.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { b ->
                SuggestionChip(onClick = { vm.update { it.copy(breed = b) } }, label = { Text(b) })
            }
        }
    }
    Text("Sex", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(
        options = PetSex.entries.toList(),
        selected = { it == state.sex },
        onToggle = { sex -> vm.update { it.copy(sex = sex) } },
        label = { it.label },
    )
    DateField(
        label = "Birth date",
        date = state.birthDate,
        onDate = { d -> vm.update { it.copy(birthDate = d) } },
        placeholder = "Pick a date",
    )
    val today = rememberToday()
    var approxAge by rememberSaveable { mutableStateOf("") }
    NumberField(
        value = approxAge,
        onValue = { v ->
            approxAge = v
            v.toDoubleOrNull()?.takeIf { it in 0.0..35.0 }?.let { years ->
                val months = (years * 12).toInt()
                vm.update { it.copy(birthDate = today.minusMonths(months).firstOfMonth()) }
            }
        },
        label = "…or approximate age",
        suffix = "years",
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Senior care usually starts around 7 years for dogs and 10 for cats.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepHealth(state: PetEditorState, vm: PetEditorViewModel) {
    Text("Health conditions", style = MaterialTheme.typography.titleSmall)
    val options = (PetCatalog.commonConditions + state.conditions).distinct()
    ChoiceChips(
        options = options,
        selected = { it in state.conditions },
        onToggle = vm::toggleCondition,
        label = { it },
    )
    var custom by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = custom,
        onValueChange = { custom = it.take(40) },
        label = { Text("Add another condition") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { vm.addCustomCondition(custom); custom = "" }, enabled = custom.isNotBlank()) {
                Icon(Icons.Filled.Add, contentDescription = "Add condition")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.isNew) {
        NumberField(
            value = state.initialWeight,
            onValue = { v -> vm.update { it.copy(initialWeight = v) } },
            label = "Current weight (optional)",
            suffix = state.weightUnit.label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text("Vet", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = state.vetName,
        onValueChange = { v -> vm.update { it.copy(vetName = v.take(60)) } },
        label = { Text("Vet or clinic name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.vetPhone,
        onValueChange = { v -> vm.update { it.copy(vetPhone = v.filter { c -> c.isDigit() || c in "+-() " }.take(20)) } },
        label = { Text("Vet phone") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.notes,
        onValueChange = { v -> vm.update { it.copy(notes = v.take(500)) } },
        label = { Text("Notes (diet, temperament, anything useful)") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}
