package com.goldenpaw.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goldenpaw.core.epochDay
import com.goldenpaw.core.localDateOfEpochDay
import com.goldenpaw.core.toFixed
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.datetime.LocalDate

/** rememberSaveable for a LocalDate (stored as an epoch day so it survives process death on Android). */
@Composable
fun rememberSaveableDate(initial: LocalDate): MutableState<LocalDate> = rememberSaveable(
    saver = Saver<MutableState<LocalDate>, Long>(
        save = { it.value.epochDay() },
        restore = { mutableStateOf(localDateOfEpochDay(it)) },
    ),
) { mutableStateOf(initial) }

/**
 * Weight entry with a kg/lb picker. Whatever unit is typed, the value is converted and stored in kg.
 */
@Composable
fun WeightLogDialog(
    defaultUnit: WeightUnit,
    onDismiss: () -> Unit,
    onSave: (kg: Double, date: LocalDate, notes: String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(defaultUnit) }
    var date by rememberSaveableDate(rememberToday())
    var notes by rememberSaveable { mutableStateOf("") }
    val parsed = value.toDoubleOrNull()?.takeIf { it > 0 && it < 300 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(value = value, onValue = { value = it }, label = "Weight", suffix = unit.label, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WeightUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = {
                                // Convert the typed value so switching units doesn't change the actual weight.
                                val current = value.toDoubleOrNull()
                                if (current != null && u != unit) {
                                    val kg = UnitConversion.toKg(current, unit)
                                    value = UnitConversion.fromKg(kg, u).toFixed(1)
                                }
                                unit = u
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, WeightUnit.entries.size),
                        ) { Text(u.label) }
                    }
                }
                DateField(label = "Date", date = date, onDate = { date = it })
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(200) },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    parsed?.let { onSave(UnitConversion.toKg(it, unit), date, notes.trim()) }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
