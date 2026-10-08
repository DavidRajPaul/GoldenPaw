package com.goldenpaw.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.newId
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.RecordTextParser
import com.goldenpaw.domain.logic.Ymd
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.VaccineDue
import com.goldenpaw.domain.model.VaccineDueLogic
import com.goldenpaw.domain.model.VaccineRecord
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.DocumentTextReader
import com.goldenpaw.domain.repository.HealthDocumentRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.CareAttribution
import com.goldenpaw.platform.PageImageProcessor
import com.goldenpaw.report.HealthRecordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

// ------------------------------------------------------------------ Scan / edit flow

enum class ScanStep { SOURCE, REVIEW, DETAILS }

/**
 * One page in the review step. [original] is never modified; rotation and enhancement are rendered
 * from it into [current], so toggling "Enhance" back off is lossless.
 */
data class PageDraft(
    val key: String,
    val original: String,
    val current: String,
    val quarterTurns: Int = 0,
    val enhanced: Boolean = false,
)

data class VaccineDraft(
    val key: String = newId(),
    val name: String = "",
    val givenOn: LocalDate? = null,
    val nextDue: LocalDate? = null,
    val batch: String = "",
)

data class RecordEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val step: ScanStep = ScanStep.SOURCE,
    val id: String = "",
    val petId: String = "",
    val petName: String = "",
    val type: DocumentType = DocumentType.VACCINE_CARD,
    val title: String = "",
    val issuedOn: LocalDate? = null,
    val clinic: String = "",
    val vetName: String = "",
    val notes: String = "",
    val vaccines: List<VaccineDraft> = emptyList(),
    val pages: List<PageDraft> = emptyList(),
    val selectedPage: Int = 0,
    val processing: Boolean = false,
    val reading: Boolean = false,
    /** What text recognition filled in, shown above the form ("We filled in 3 vaccines…"). */
    val readSummary: String? = null,
    val textReaderAvailable: Boolean = false,
    val recognizedText: String = "",
    val addReminders: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val createdAt: Instant? = null,
    val loggedBy: Attribution = Attribution.NONE,
) {
    val hasFutureDueDate: Boolean get() = vaccines.any { it.nextDue != null }
    val canSave: Boolean get() = pages.isNotEmpty() && !saving && !processing && !reading
}

/**
 * Drives capture → review → details → save for a new record, and the same screens for editing an
 * existing one (which opens on the details step with its pages already in place).
 */
class RecordEditorViewModel(
    private val documents: HealthDocumentRepository,
    private val service: HealthRecordService,
    private val pets: PetRepository,
    private val settings: SettingsRepository,
    private val careTeam: CareAttribution,
    private val reminders: ReminderGateway,
    private val textReader: DocumentTextReader,
    private val processor: PageImageProcessor,
    private val files: AppFiles,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordEditorState())
    val state: StateFlow<RecordEditorState> = _state.asStateFlow()

    /** Inbox files created in this session; whatever isn't saved is deleted when the screen closes. */
    private val tempFiles = mutableSetOf<String>()
    private var loaded = false
    private var textRead = false

    val inboxDir: String get() = service.inboxDir

    fun load(petId: String, recordId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val pet = pets.getPet(petId)
            val existing = recordId?.let { documents.get(it) }
            _state.value = if (existing == null) {
                RecordEditorState(
                    loading = false,
                    id = newId(),
                    petId = petId,
                    petName = pet?.name.orEmpty(),
                    clinic = pet?.vetName.orEmpty(),
                    textReaderAvailable = textReader.isAvailable,
                )
            } else {
                textRead = true // don't overwrite a confirmed entry with a fresh read
                RecordEditorState(
                    loading = false,
                    isNew = false,
                    step = ScanStep.DETAILS,
                    id = existing.id,
                    petId = existing.petId,
                    petName = pet?.name.orEmpty(),
                    type = existing.type,
                    title = existing.title,
                    issuedOn = existing.issuedOn,
                    clinic = existing.clinic,
                    vetName = existing.vetName,
                    notes = existing.notes,
                    vaccines = existing.vaccines.map { VaccineDraft(name = it.name, givenOn = it.givenOn, nextDue = it.nextDue, batch = it.batch) },
                    pages = existing.pagePaths.map { PageDraft(key = newId(), original = it, current = it) },
                    textReaderAvailable = textReader.isAvailable,
                    recognizedText = existing.recognizedText,
                    addReminders = false,
                    createdAt = existing.createdAt,
                    loggedBy = existing.loggedBy,
                )
            }
        }
    }

    fun update(transform: (RecordEditorState) -> RecordEditorState) = _state.update(transform)

    fun setType(type: DocumentType) = _state.update {
        // Keep a custom title; swap the default one.
        val title = if (it.title.isBlank() || DocumentType.entries.any { t -> t.defaultTitle == it.title }) type.defaultTitle else it.title
        it.copy(type = type, title = title)
    }

    fun onPagesCaptured(paths: List<String>) {
        if (paths.isEmpty()) return
        tempFiles += paths
        _state.update { s ->
            val added = paths.map { PageDraft(key = newId(), original = it, current = it) }
            s.copy(
                pages = s.pages + added,
                selectedPage = s.pages.size,
                step = if (s.step == ScanStep.SOURCE) ScanStep.REVIEW else s.step,
                error = null,
            )
        }
        // New pages may hold text we haven't read yet.
        if (_state.value.isNew) textRead = false
    }

    fun showError(message: String?) = _state.update { it.copy(error = message) }

    fun selectPage(index: Int) = _state.update { it.copy(selectedPage = index.coerceIn(0, (it.pages.size - 1).coerceAtLeast(0))) }

    fun rotate(index: Int) = reprocess(index) { it.copy(quarterTurns = (it.quarterTurns + 1) % 4) }

    fun toggleEnhance(index: Int) = reprocess(index) { it.copy(enhanced = !it.enhanced) }

    private fun reprocess(index: Int, change: (PageDraft) -> PageDraft) {
        val page = _state.value.pages.getOrNull(index) ?: return
        if (_state.value.processing) return
        val target = change(page)
        viewModelScope.launch {
            _state.update { it.copy(processing = true) }
            val path = if (target.quarterTurns == 0 && !target.enhanced) {
                target.original
            } else {
                processor.process(target.original, service.inboxDir, target.quarterTurns, target.enhanced)
            }
            if (path == null) {
                _state.update { it.copy(processing = false, error = "Couldn't edit that page.") }
                return@launch
            }
            if (path != target.original) tempFiles += path
            val previous = page.current
            _state.update { s ->
                s.copy(
                    processing = false,
                    pages = s.pages.map { if (it.key == page.key) target.copy(current = path) else it },
                )
            }
            // Drop the superseded render (never an original).
            if (previous != page.original && previous != path && previous in tempFiles) {
                files.delete(previous)
                tempFiles -= previous
            }
            textRead = false
        }
    }

    fun movePage(index: Int, by: Int) = _state.update { s ->
        val to = index + by
        if (index !in s.pages.indices || to !in s.pages.indices) return@update s
        val list = s.pages.toMutableList()
        val item = list.removeAt(index)
        list.add(to, item)
        s.copy(pages = list, selectedPage = to)
    }

    fun removePage(index: Int) = _state.update { s ->
        if (index !in s.pages.indices) return@update s
        val list = s.pages.toMutableList().also { it.removeAt(index) }
        s.copy(
            pages = list,
            selectedPage = s.selectedPage.coerceAtMost((list.size - 1).coerceAtLeast(0)),
            step = if (list.isEmpty() && s.isNew) ScanStep.SOURCE else s.step,
        )
    }

    /** Review → details. Runs on-device text recognition once and fills only the fields still empty. */
    fun continueToDetails() {
        val s = _state.value
        if (s.pages.isEmpty()) return
        _state.update { it.copy(step = ScanStep.DETAILS) }
        if (textRead || !textReader.isAvailable) {
            if (!textReader.isAvailable && s.readSummary == null) {
                _state.update { it.copy(readSummary = "Fill in what the card says. GoldenPaw turns it into a tidy PDF with the scans attached.") }
            }
            return
        }
        textRead = true
        viewModelScope.launch {
            _state.update { it.copy(reading = true, readSummary = null) }
            val text = runCatching {
                _state.value.pages.map { textReader.read(it.current) }.filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault("")
            val parsed = RecordTextParser.parse(text, dayFirst = true)
            _state.update { st ->
                val vaccines = if (st.vaccines.isEmpty()) {
                    parsed.vaccines.map { VaccineDraft(name = it.name, givenOn = it.given?.toLocalDate(), nextDue = it.due?.toLocalDate(), batch = it.batch) }
                } else {
                    st.vaccines
                }
                val type = if (st.isNew && parsed.suggestedType != null) parsed.suggestedType!! else st.type
                val filled = buildList {
                    if (vaccines.isNotEmpty() && st.vaccines.isEmpty()) add("${vaccines.size} ${if (vaccines.size == 1) "entry" else "entries"}")
                    if (parsed.clinic != null && st.clinic.isBlank()) add("the clinic")
                    if (parsed.vetName != null && st.vetName.isBlank()) add("the vet")
                    if (parsed.issuedOn != null && st.issuedOn == null) add("the date")
                }
                st.copy(
                    reading = false,
                    recognizedText = text,
                    type = type,
                    title = if (st.title.isBlank()) type.defaultTitle else st.title,
                    vaccines = vaccines,
                    clinic = st.clinic.ifBlank { parsed.clinic.orEmpty() },
                    vetName = st.vetName.ifBlank { parsed.vetName.orEmpty() },
                    issuedOn = st.issuedOn ?: parsed.issuedOn?.toLocalDate(),
                    readSummary = when {
                        text.isBlank() -> "We couldn't read text on this card. Fill in the details below."
                        filled.isEmpty() -> "We read the card but couldn't match anything. Fill in the details below."
                        else -> "We filled in ${filled.joinToString(", ")} from the scan. Please check each one: scans can misread."
                    },
                )
            }
        }
    }

    /** Returns false when the screen should close instead (already on the first step). */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.step == ScanStep.DETAILS && s.isNew -> {
                _state.update { it.copy(step = ScanStep.REVIEW) }
                true
            }
            s.step == ScanStep.REVIEW && s.isNew -> {
                _state.update { it.copy(step = ScanStep.SOURCE) }
                true
            }
            s.step == ScanStep.REVIEW && !s.isNew -> {
                _state.update { it.copy(step = ScanStep.DETAILS) }
                true
            }
            else -> false
        }
    }

    fun editPages() = _state.update { it.copy(step = ScanStep.REVIEW) }

    fun addVaccine(name: String = "") = _state.update { it.copy(vaccines = it.vaccines + VaccineDraft(name = name)) }

    fun updateVaccine(key: String, transform: (VaccineDraft) -> VaccineDraft) =
        _state.update { s -> s.copy(vaccines = s.vaccines.map { if (it.key == key) transform(it) else it }) }

    fun removeVaccine(key: String) = _state.update { s -> s.copy(vaccines = s.vaccines.filterNot { it.key == key }) }

    fun save(onSaved: (String) -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val by = if (s.isNew || s.loggedBy.name.isBlank()) careTeam.attributionFor(s.petId) else s.loggedBy
                val document = HealthDocument(
                    id = s.id,
                    petId = s.petId,
                    type = s.type,
                    title = s.title.trim().ifBlank { s.type.defaultTitle },
                    issuedOn = s.issuedOn,
                    clinic = s.clinic,
                    vetName = s.vetName,
                    notes = s.notes,
                    vaccines = s.vaccines
                        .filter { it.name.isNotBlank() }
                        .map { VaccineRecord(it.name.trim(), it.givenOn, it.nextDue, it.batch.trim()) },
                    pagePaths = s.pages.map { it.current },
                    pdfPath = null,
                    recognizedText = s.recognizedText,
                    createdAt = s.createdAt ?: clock.now(),
                    loggedBy = by,
                )
                val saved = service.save(document, settings.current().ownerName)
                if (s.addReminders && saved.vaccines.any { it.nextDue != null }) {
                    if (service.addDueDateVisits(saved, by) > 0) runCatching { reminders.rescheduleAll() }
                }
                saved
            }
            result.onSuccess { saved ->
                // Saved pages were moved out of the inbox; everything else from this session goes.
                service.discardInbox(tempFiles)
                tempFiles.clear()
                _state.update { it.copy(saving = false) }
                onSaved(saved.id)
            }.onFailure {
                _state.update { it.copy(saving = false, error = "Couldn't save the record. Please try again.") }
            }
        }
    }

    override fun onCleared() {
        service.discardInbox(tempFiles)
        tempFiles.clear()
    }

    fun today(): LocalDate = clock.today()
}

private fun Ymd.toLocalDate(): LocalDate? = runCatching { LocalDate(year, month, day) }.getOrNull()

// ------------------------------------------------------------------ Record detail

data class RecordDetailState(
    val loading: Boolean = true,
    val record: HealthDocument? = null,
    val petName: String = "",
    val pdfReady: Boolean = false,
    val today: LocalDate? = null,
)

class RecordDetailViewModel(
    private val documents: HealthDocumentRepository,
    private val service: HealthRecordService,
    private val pets: PetRepository,
    private val settings: SettingsRepository,
    private val files: AppFiles,
    private val clock: AppClock,
) : ViewModel() {
    private val ids = MutableStateFlow<Pair<String, String>?>(null)

    /** Bumped when files change on disk (the PDF was rebuilt): the file check isn't part of the database flow. */
    private val filesVersion = MutableStateFlow(0)

    val state: StateFlow<RecordDetailState> = ids.filterNotNull().flatMapLatest { (petId, recordId) ->
        combine(documents.observeForPet(petId), pets.observePet(petId), filesVersion) { list, pet, _ ->
            val record = list.firstOrNull { it.id == recordId }
            RecordDetailState(
                loading = false,
                record = record,
                petName = pet?.name.orEmpty(),
                pdfReady = record?.pdfPath?.let { files.exists(it) } == true,
                today = clock.today(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordDetailState())

    fun load(petId: String, recordId: String) {
        if (ids.value == petId to recordId) return
        ids.value = petId to recordId
        // A PDF can go missing (restored backup, cleared storage): rebuild it from the pages.
        viewModelScope.launch {
            val record = documents.get(recordId) ?: return@launch
            if (record.pdfPath == null || !files.exists(record.pdfPath)) {
                val path = service.writePdf(record, settings.current().ownerName)
                if (path != null) documents.upsert(record.copy(pdfPath = path))
                filesVersion.update { it + 1 }
            }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        ids.value?.second?.let { service.delete(it) }
        onDone()
    }
}

// ------------------------------------------------------------------ Lists (pet profile, Today)

/** Records for one pet plus the vaccine due dates worth surfacing. */
data class PetRecordsState(
    val records: List<HealthDocument> = emptyList(),
    val due: List<VaccineDue> = emptyList(),
)

class PetRecordsViewModel(
    private val documents: HealthDocumentRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val petId = MutableStateFlow<String?>(null)

    val state: StateFlow<PetRecordsState> = petId.filterNotNull().flatMapLatest { id ->
        documents.observeForPet(id).map { list -> PetRecordsState(list, VaccineDueLogic.dueSoon(list, clock.today())) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PetRecordsState())

    fun load(id: String) {
        petId.value = id
    }
}
