package com.goldenpaw.ui.pets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.core.minusYears
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.ui.common.ConfirmDialog
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.TimeChipButton
import com.goldenpaw.ui.common.rememberToday
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.navigation.LocalAppActions
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VetVisitEditorScreen(petId: String, visitId: String?) {
    val vm: VetVisitEditorViewModel = koinViewModel()
    LaunchedEffect(petId, visitId) { vm.load(petId, visitId) }
    val form by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val today = rememberToday()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GpTopBar(if (form.isNew) "Vet visit" else form.title, onBack = actions.back) {
                if (!form.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { vm.save { actions.back() } },
                    enabled = form.valid && !form.saving,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(50.dp),
                ) { Text("Save visit") }
            }
        },
    ) { padding ->
        if (form.loading) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (form.petName.isNotBlank()) {
                Text("For ${form.petName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text("Reason", style = MaterialTheme.typography.titleSmall)
            ChoiceChips(
                options = VetVisit.commonReasons,
                selected = { it == form.title },
                onToggle = { r -> vm.update { it.copy(title = r) } },
                label = { it },
            )
            OutlinedTextField(
                value = form.title,
                onValueChange = { v -> vm.update { it.copy(title = v.take(60)) } },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                DateField(
                    label = "Date",
                    date = form.date,
                    onDate = { d -> vm.update { it.copy(date = d) } },
                    allowFuture = true,
                    minDate = today.minusYears(5),
                    modifier = Modifier.weight(1f),
                )
                TimeChipButton(time = form.time, onTime = { t -> vm.update { it.copy(time = t) } })
            }
            OutlinedTextField(
                value = form.clinic,
                onValueChange = { v -> vm.update { it.copy(clinic = v.take(80)) } },
                label = { Text("Clinic or vet (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> vm.update { it.copy(notes = v.take(1000)) } },
                label = { Text("Notes (optional)") },
                placeholder = { Text("Questions to ask, what the vet said, follow-up plan") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Visit done", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Upcoming visits get a reminder the day before.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = form.completed, onCheckedChange = { c -> vm.update { it.copy(completed = c) } })
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this visit?",
            body = "The visit and its notes are removed for everyone on the care team.",
            confirm = "Delete",
            onConfirm = { vm.delete { actions.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
