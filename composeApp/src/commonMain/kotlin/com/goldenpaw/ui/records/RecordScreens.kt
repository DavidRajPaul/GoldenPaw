package com.goldenpaw.ui.records

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldenpaw.core.Fmt
import com.goldenpaw.core.minusYears
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.VaccineDue
import com.goldenpaw.domain.model.VaccineRecord
import com.goldenpaw.domain.model.VaccineStatus
import com.goldenpaw.platform.CaptureResult
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.platform.PlatformBackHandler
import com.goldenpaw.platform.rememberDocumentCapture
import com.goldenpaw.ui.common.ConfirmDialog
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.rememberToday
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.GpIcons
import com.goldenpaw.ui.designsystem.IconBadge
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.icon
import com.goldenpaw.ui.designsystem.pressScale
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Route
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope

/** Names offered as one-tap chips when adding a vaccine row by hand. */
private val commonVaccines = listOf(
    "Rabies", "DHPPi + Lepto", "DHPP", "Leptospirosis", "Kennel cough (Bordetella)", "Canine influenza",
    "FVRCP", "FeLV", "Deworming", "Tick & flea",
)

// =================================================================== Scan / edit

/**
 * Add (or edit) a scanned health record: choose camera or gallery → review pages (rotate, enhance,
 * reorder, delete, add more) → confirm the entry text recognition pre-filled → save as a PDF.
 */
@Composable
fun RecordScanScreen(petId: String, recordId: String?) {
    val vm: RecordEditorViewModel = koinViewModel()
    LaunchedEffect(petId, recordId) { vm.load(petId, recordId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var permissionDialog by remember { mutableStateOf(false) }

    val capture = rememberDocumentCapture(outputDir = vm.inboxDir) { result ->
        when (result) {
            is CaptureResult.Pages -> vm.onPagesCaptured(result.paths)
            CaptureResult.Cancelled -> Unit
            is CaptureResult.PermissionDenied -> {
                if (result.permanently) {
                    permissionDialog = true
                } else {
                    scope.launch { snackbar.showSnackbar("GoldenPaw needs the camera to scan. You can also pick a photo.") }
                }
            }
            is CaptureResult.Failed -> {
                scope.launch { snackbar.showSnackbar(result.message) }
            }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.showError(null)
        }
    }

    val goBack: () -> Unit = { if (!vm.back()) actions.back() }
    PlatformBackHandler(
        enabled = state.step != ScanStep.SOURCE && !(state.step == ScanStep.DETAILS && !state.isNew),
        onProgress = { _, _ -> },
        onCancel = {},
        onBack = { vm.back() },
    )

    Scaffold(
        topBar = {
            GpTopBar(
                title = when (state.step) {
                    ScanStep.SOURCE -> "Add a health record"
                    ScanStep.REVIEW -> "Check the pages"
                    ScanStep.DETAILS -> if (state.isNew) "Confirm the details" else "Edit record"
                },
                onBack = goBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (state.step != ScanStep.SOURCE && !state.loading) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = {
                            if (state.step == ScanStep.REVIEW) vm.continueToDetails()
                            else vm.save { id -> actions.back(); actions.navigate(Route.RecordDetail(state.petId, id)) }
                        },
                        enabled = if (state.step == ScanStep.REVIEW) state.pages.isNotEmpty() && !state.processing else state.canSave,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(52.dp),
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text("Creating PDF…")
                        } else {
                            Text(if (state.step == ScanStep.REVIEW) "Continue" else if (state.isNew) "Save & create PDF" else "Save changes")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        val reduce = LocalReduceMotion.current
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (reduce) {
                    fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                } else {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(Motion.MEDIUM2, easing = Motion.EmphasizedDecelerate)) { if (forward) it / 5 else -it / 5 } +
                        fadeIn(tween(Motion.MEDIUM1))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM1)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(Motion.SHORT2)))
                }
            },
            label = "scanStep",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { step ->
            when (step) {
                ScanStep.SOURCE -> SourceStep(
                    state = state,
                    cameraAvailable = capture.cameraAvailable,
                    autoCrop = capture.autoCrop,
                    onType = vm::setType,
                    onCamera = capture.scanWithCamera,
                    onGallery = capture.pickFromGallery,
                )
                ScanStep.REVIEW -> ReviewStep(
                    state = state,
                    cameraAvailable = capture.cameraAvailable,
                    onSelect = vm::selectPage,
                    onRotate = vm::rotate,
                    onEnhance = vm::toggleEnhance,
                    onMove = vm::movePage,
                    onRemove = vm::removePage,
                    onCamera = capture.scanWithCamera,
                    onGallery = capture.pickFromGallery,
                )
                ScanStep.DETAILS -> DetailsStep(state = state, vm = vm)
            }
        }
    }

    if (permissionDialog) {
        AlertDialog(
            onDismissRequest = { permissionDialog = false },
            icon = { Icon(Icons.Rounded.NoPhotography, contentDescription = null) },
            title = { Text("Camera access is off") },
            text = {
                Text(
                    "To scan a card, allow camera access for GoldenPaw in Settings. " +
                        "The camera is only used while you scan, and pages stay on this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { permissionDialog = false; capture.openAppSettings() }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { permissionDialog = false; capture.pickFromGallery() }) { Text("Pick a photo instead") }
            },
        )
    }
}

@Composable
private fun SourceStep(
    state: RecordEditorState,
    cameraAvailable: Boolean,
    autoCrop: Boolean,
    onType: (DocumentType) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.petName.isNotBlank()) {
            Text("For ${state.petName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text("What are you adding?", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentType.entries.forEach { type ->
                FilterChip(
                    selected = state.type == type,
                    onClick = { onType(type) },
                    label = { Text(type.label) },
                    leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        if (cameraAvailable) {
            SourceCard(
                icon = GpIcons.Scan,
                title = "Scan with camera",
                body = if (autoCrop) {
                    "Finds the card's edges, straightens it and handles several pages in one go."
                } else {
                    "Take a photo of each page."
                },
                primary = true,
                onClick = onCamera,
            )
        }
        SourceCard(
            icon = GpIcons.Gallery,
            title = if (cameraAvailable) "Choose from gallery" else "Choose image files",
            body = "Use photos you already took of the card, or a screenshot the clinic sent. Pick up to 10.",
            primary = !cameraAvailable,
            onClick = onGallery,
        )
        GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Private by design. The camera is used only while you scan, the gallery shares only the photos you pick, " +
                        "and the text is read on this device. Pages are kept inside GoldenPaw, not in your photo library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, primary: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon,
                size = 52.dp,
                container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReviewStep(
    state: RecordEditorState,
    cameraAvailable: Boolean,
    onSelect: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onEnhance: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val index = state.selectedPage.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0))
    val page = state.pages.getOrNull(index)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (page != null) {
                AsyncImage(
                    model = page.current,
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
            if (state.processing) CircularProgressIndicator()
            Pill(
                "Page ${index + 1} of ${state.pages.size}",
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                container = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (page != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMove(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Move page earlier")
                }
                IconButton(onClick = { onRotate(index) }, enabled = !state.processing) {
                    Icon(Icons.Rounded.RotateRight, contentDescription = "Rotate page")
                }
                FilterChip(
                    selected = page.enhanced,
                    onClick = { onEnhance(index) },
                    enabled = !state.processing,
                    label = { Text("Enhance") },
                    leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove page")
                }
                IconButton(onClick = { onMove(index, 1) }, enabled = index < state.pages.lastIndex) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Move page later")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(state.pages, key = { _, p -> p.key }) { i, p ->
                val selected = i == index
                AsyncImage(
                    model = p.current,
                    contentDescription = "Page ${i + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 60.dp, height = 80.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(i) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Add another page", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cameraAvailable) {
                OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f)) {
                    Icon(GpIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Camera")
                }
            }
            OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) {
                Icon(GpIcons.Gallery, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Gallery")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: \"Enhance\" makes faded stamps and handwriting easier to read. Rotate any page that's sideways before continuing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailsStep(state: RecordEditorState, vm: RecordEditorViewModel) {
    val today = rememberToday()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.reading) {
            GpCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text("Reading the card on your device…", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            }
        } else {
            state.readSummary?.let { summary ->
                GpCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(GpIcons.Sparkle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Page strip: tap to go back and adjust pages.
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(state.pages, key = { _, p -> p.key }) { _, p ->
                    AsyncImage(
                        model = p.current,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
            TextButton(onClick = vm::editPages) { Text("Edit pages") }
        }

        Text("Type", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentType.entries.forEach { type ->
                FilterChip(
                    selected = state.type == type,
                    onClick = { vm.setType(type) },
                    label = { Text(type.label) },
                    leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        OutlinedTextField(
            value = state.title,
            onValueChange = { v -> vm.update { it.copy(title = v.take(80)) } },
            label = { Text("Title") },
            placeholder = { Text(state.type.defaultTitle) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        DateField(
            label = "Date on the record",
            date = state.issuedOn,
            onDate = { d -> vm.update { it.copy(issuedOn = d) } },
            minDate = today.minusYears(30),
        )
        OutlinedTextField(
            value = state.clinic,
            onValueChange = { v -> vm.update { it.copy(clinic = v.take(100)) } },
            label = { Text("Clinic (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.vetName,
            onValueChange = { v -> vm.update { it.copy(vetName = v.take(80)) } },
            label = { Text("Vet (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        val showVaccines = state.type == DocumentType.VACCINE_CARD || state.vaccines.isNotEmpty()
        AnimatedVisibility(visible = showVaccines, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Vaccines & treatments")
                state.vaccines.forEach { v -> VaccineRow(v, today, vm) }
                if (state.vaccines.isEmpty()) {
                    Text(
                        "Add each vaccine, deworming or flea/tick treatment on the card. Due dates show on Today and in the vet report.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    commonVaccines.filter { name -> state.vaccines.none { it.name.equals(name, ignoreCase = true) } }.take(6).forEach { name ->
                        FilterChip(
                            selected = false,
                            onClick = { vm.addVaccine(name) },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                TextButton(onClick = { vm.addVaccine() }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add another")
                }
            }
        }

        OutlinedTextField(
            value = state.notes,
            onValueChange = { v -> vm.update { it.copy(notes = v.take(1000)) } },
            label = { Text("Notes (optional)") },
            placeholder = { Text("What the vet said, reactions to watch for, follow-up plan") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.hasFutureDueDate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add due dates as vet visits", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "They show on Today, and you get a reminder the evening before.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.addReminders, onCheckedChange = { c -> vm.update { it.copy(addReminders = c) } })
            }
        }

        if (state.recognizedText.isNotBlank()) RecognizedTextCard(state.recognizedText)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VaccineRow(v: VaccineDraft, today: LocalDate, vm: RecordEditorViewModel) {
    GpCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = v.name,
                onValueChange = { name -> vm.updateVaccine(v.key) { it.copy(name = name.take(60)) } },
                label = { Text("Vaccine or treatment") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.removeVaccine(v.key) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove ${v.name}") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField(
                label = "Given",
                date = v.givenOn,
                onDate = { d -> vm.updateVaccine(v.key) { it.copy(givenOn = d) } },
                placeholder = "Date",
                minDate = today.minusYears(30),
                modifier = Modifier.weight(1f),
            )
            DateField(
                label = "Next due",
                date = v.nextDue,
                onDate = { d -> vm.updateVaccine(v.key) { it.copy(nextDue = d) } },
                placeholder = "Date",
                allowFuture = true,
                minDate = today.minusYears(30),
                modifier = Modifier.weight(1f),
            )
        }
        // Quick due dates from the given date: annual boosters, 3-weekly puppy/kitten series, 3-monthly deworming.
        val given = v.givenOn
        if (given != null && v.nextDue == null) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "+3 weeks" to DatePeriod(days = 21),
                    "+3 months" to DatePeriod(months = 3),
                    "+1 year" to DatePeriod(years = 1),
                    "+3 years" to DatePeriod(years = 3),
                ).forEach { (label, period) ->
                    FilterChip(
                        selected = false,
                        onClick = { vm.updateVaccine(v.key) { it.copy(nextDue = given.plus(period)) } },
                        label = { Text(label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = v.batch,
            onValueChange = { b -> vm.updateVaccine(v.key) { it.copy(batch = b.take(30)) } },
            label = { Text("Batch / lot no. (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecognizedTextCard(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    GpCard(onClick = { open = !open }, containerColor = MaterialTheme.colorScheme.surfaceContainer, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TextSnippet, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Text read from the scan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

// =================================================================== Record detail

@Composable
fun RecordDetailScreen(petId: String, recordId: String) {
    val vm: RecordDetailViewModel = koinViewModel()
    LaunchedEffect(petId, recordId) { vm.load(petId, recordId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val platform = LocalPlatform.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val record = state.record

    fun sharePdf() {
        val path = record?.pdfPath ?: return
        platform.shareFile(path, "application/pdf", "Share ${record.displayTitle}")
    }

    Scaffold(
        topBar = {
            GpTopBar(record?.displayTitle.orEmpty(), onBack = actions.back) {
                if (record != null) {
                    IconButton(onClick = { actions.navigate(Route.RecordScan(petId, record.id)) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit record")
                    }
                    IconButton(onClick = ::sharePdf, enabled = state.pdfReady) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share PDF")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (record == null) return@Scaffold
        val today = state.today ?: rememberToday()
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(record.pagePaths) { i, path ->
                    AsyncImage(
                        model = path,
                        contentDescription = "Page ${i + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 120.dp, height = 160.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                            .clickable(enabled = state.pdfReady) { record.pdfPath?.let { platform.openFile(it, "application/pdf") } },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { record.pdfPath?.let { platform.openFile(it, "application/pdf") } },
                    enabled = state.pdfReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open PDF")
                }
                Button(onClick = ::sharePdf, enabled = state.pdfReady, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send to vet")
                }
            }

            GpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(record.type.icon, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(record.displayTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(record.type.label, record.issuedOn?.let { Fmt.dayMonthYear(it) }, state.petName.ifBlank { null })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (record.clinic.isNotBlank() || record.vetName.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    if (record.clinic.isNotBlank()) Text(record.clinic, style = MaterialTheme.typography.bodyMedium)
                    if (record.vetName.isNotBlank()) {
                        Text(record.vetName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (record.vaccines.isNotEmpty()) {
                SectionHeader(if (record.type == DocumentType.VACCINE_CARD) "Vaccinations" else "Treatments")
                record.vaccines.forEach { VaccineLine(it, today) }
            }
            if (record.notes.isNotBlank()) {
                SectionHeader("Notes")
                Text(record.notes, style = MaterialTheme.typography.bodyMedium)
            }
            if (record.recognizedText.isNotBlank()) RecognizedTextCard(record.recognizedText)
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete record", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete && record != null) {
        ConfirmDialog(
            title = "Delete this record?",
            body = "The scanned pages and the PDF are removed from this device. Vet visits created from its due dates stay.",
            confirm = "Delete",
            onConfirm = { vm.delete { actions.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun VaccineLine(v: VaccineRecord, today: LocalDate) {
    GpCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        v.givenOn?.let { "Given ${Fmt.dayMonthYear(it)}" },
                        v.nextDue?.let { "Due ${Fmt.dayMonthYear(it)}" },
                        v.batch.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                    ).joinToString(" · ").ifBlank { "No dates" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(v.status(today), v.nextDue, today)
        }
    }
}

@Composable
private fun StatusPill(status: VaccineStatus, due: LocalDate?, today: LocalDate) {
    val wellness = LocalWellnessColors.current
    val (text, color) = when (status) {
        VaccineStatus.OVERDUE -> "Overdue" to wellness.hard
        VaccineStatus.DUE_SOON -> {
            val days = due?.let { it.toEpochDays() - today.toEpochDays() } ?: 0
            (if (days == 0) "Due today" else "Due in $days ${if (days == 1) "day" else "days"}") to wellness.okay
        }
        VaccineStatus.UP_TO_DATE -> "Up to date" to wellness.good
        VaccineStatus.NO_DUE_DATE -> return
    }
    Pill(text, container = color.copy(alpha = 0.16f), content = MaterialTheme.colorScheme.onSurface)
}

// =================================================================== Pet profile section & Today card

/** "Health records" on the pet profile: scanned cards, newest first, with a scan button. */
@Composable
fun HealthRecordsSection(petId: String, canScan: Boolean) {
    val vm: PetRecordsViewModel = koinViewModel()
    LaunchedEffect(petId) { vm.load(petId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val today = rememberToday()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            "Health records",
            action = if (canScan) "Scan" else null,
            onAction = if (canScan) ({ actions.navigate(Route.RecordScan(petId)) }) else null,
        )
        if (state.records.isEmpty()) {
            GpCard(onClick = if (canScan) ({ actions.navigate(Route.RecordScan(petId)) }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(GpIcons.Scan, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Scan a vaccine or vet card", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Turn the paper card into a PDF you can send to any vet, with due-date reminders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        state.records.forEach { record ->
            RecordRow(record, today) { actions.navigate(Route.RecordDetail(petId, record.id)) }
        }
    }
}

@Composable
private fun RecordRow(record: HealthDocument, today: LocalDate, onClick: () -> Unit) {
    val next = record.vaccines.filter { it.nextDue != null }.minByOrNull { it.nextDue!! }
    GpCard(onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                record.pagePaths.firstOrNull()?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(record.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(record.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                Text(
                    listOfNotNull(
                        record.issuedOn?.let { Fmt.dayMonthYear(it) },
                        record.clinic.takeIf { it.isNotBlank() },
                        "${record.pagePaths.size} ${if (record.pagePaths.size == 1) "page" else "pages"}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (next != null) StatusPill(next.status(today), next.nextDue, today)
        }
    }
}

/** Today: vaccines / treatments overdue or due within 30 days for the selected pet. */
@Composable
fun VaccineDueCard(petName: String, due: List<VaccineDue>, today: LocalDate, onOpen: (VaccineDue) -> Unit, modifier: Modifier = Modifier) {
    val wellness = LocalWellnessColors.current
    val overdue = due.any { it.status == VaccineStatus.OVERDUE }
    GpCard(
        modifier = modifier,
        containerColor = if (overdue) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        onClick = { due.firstOrNull()?.let(onOpen) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GpIcons.Vaccine, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                if (overdue) "$petName has a vaccine overdue" else "Coming up for $petName",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        due.take(3).forEach { d ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(
                        if (d.status == VaccineStatus.OVERDUE) wellness.hard else wellness.okay,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(d.vaccine.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                val dueDate = d.vaccine.nextDue
                Text(
                    when {
                        dueDate == null -> ""
                        dueDate < today -> "was due ${Fmt.dayMonth(dueDate)}"
                        else -> Fmt.relativeDay(dueDate, today)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (due.size > 3) {
            Text("+${due.size - 3} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
