package com.goldenpaw

import com.goldenpaw.core.SystemAppClock
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.today
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.VaccineRecord
import com.goldenpaw.domain.model.VaccineStatus
import com.goldenpaw.report.DocumentCanvasFactory
import com.goldenpaw.report.HealthRecordService
import com.goldenpaw.report.SimplePdfCanvas
import com.goldenpaw.ui.records.PetRecordsViewModel
import com.goldenpaw.ui.records.RecordDetailViewModel
import com.goldenpaw.ui.records.RecordEditorViewModel
import com.goldenpaw.ui.records.ScanStep
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordViewModelsTest {
    private val files = TempFiles()
    private val docs = FakeHealthDocuments()
    private val visits = FakeVisits()
    private val pets = FakePets(testPet())
    private val reminders = FakeReminders()
    private val reader = FakeTextReader()
    private val processor = FakeProcessor()
    private val service = HealthRecordService(docs, pets, visits, files, DocumentCanvasFactory { SimplePdfCanvas() }, SystemAppClock)

    private fun editor(textReader: FakeTextReader = reader) = RecordEditorViewModel(
        documents = docs, service = service, pets = pets, settings = FakeSettings(), careTeam = FakeAttribution,
        reminders = reminders, textReader = textReader, processor = processor, files = files, clock = SystemAppClock,
    )

    /** A page as the camera / gallery would deliver it: a JPEG in the inbox. */
    private fun capture(name: String): String =
        File(service.inboxDir, "$name.jpg").apply { parentFile.mkdirs(); writeBytes(fakeJpeg()) }.absolutePath

    private fun RecordEditorViewModel.loaded(recordId: String? = null): RecordEditorViewModel = apply {
        load("pet", recordId)
        awaitValue(state) { !it.loading }
    }

    @AfterTest
    fun cleanup() {
        files.cleanup()
    }

    @Test
    fun newRecordStartsOnTheSourceStepWithThePetsVet() {
        val vm = editor().loaded()
        val s = vm.state.value
        assertTrue(s.isNew)
        assertEquals(ScanStep.SOURCE, s.step)
        assertEquals("Bruno", s.petName)
        assertEquals("Happy Tails Clinic", s.clinic)
        assertEquals(DocumentType.VACCINE_CARD, s.type)
        assertFalse(s.canSave)
    }

    @Test
    fun capturedPagesOpenTheReviewStep() {
        val vm = editor().loaded()
        vm.onPagesCaptured(listOf(capture("a"), capture("b")))
        val s = vm.state.value
        assertEquals(ScanStep.REVIEW, s.step)
        assertEquals(2, s.pages.size)
        assertEquals(0, s.selectedPage)

        vm.onPagesCaptured(listOf(capture("c")))
        assertEquals(3, vm.state.value.pages.size)
        assertEquals(2, vm.state.value.selectedPage)
        assertEquals(ScanStep.REVIEW, vm.state.value.step)
    }

    @Test
    fun rotateAndEnhanceAlwaysRenderFromTheOriginal() {
        val vm = editor().loaded()
        val original = capture("a")
        vm.onPagesCaptured(listOf(original))

        vm.rotate(0)
        awaitValue(vm.state) { !it.processing && it.pages[0].quarterTurns == 1 }
        assertEquals(Triple(original, 1, false), processor.calls.last())
        val rotated = vm.state.value.pages[0].current
        assertTrue(rotated != original)

        vm.toggleEnhance(0)
        awaitValue(vm.state) { !it.processing && it.pages[0].enhanced }
        assertEquals(Triple(original, 1, true), processor.calls.last())
        assertFalse(File(rotated).exists(), "superseded render is deleted")

        // Three more quarter turns and enhance off: back to the untouched original, no processing needed.
        repeat(3) { i ->
            vm.rotate(0)
            awaitValue(vm.state) { !it.processing && it.pages[0].quarterTurns == (2 + i) % 4 }
        }
        vm.toggleEnhance(0)
        awaitValue(vm.state) { !it.processing && !it.pages[0].enhanced }
        assertEquals(original, vm.state.value.pages[0].current)
        assertTrue(File(original).exists())
    }

    @Test
    fun reorderAndRemovePages() {
        val vm = editor().loaded()
        val a = capture("a")
        val b = capture("b")
        vm.onPagesCaptured(listOf(a, b))

        vm.movePage(0, 1)
        assertEquals(listOf(b, a), vm.state.value.pages.map { it.original })
        assertEquals(1, vm.state.value.selectedPage)
        vm.movePage(1, 1) // out of range: ignored
        assertEquals(listOf(b, a), vm.state.value.pages.map { it.original })

        vm.removePage(0)
        vm.removePage(0)
        assertTrue(vm.state.value.pages.isEmpty())
        assertEquals(ScanStep.SOURCE, vm.state.value.step, "removing the last page goes back to choosing a source")
    }

    @Test
    fun readingTheCardFillsOnlyEmptyFields() {
        reader.text = """
            PAWS & CLAWS VETERINARY CLINIC
            Dr. S. Karthik BVSc
            Anti Rabies   12/03/2026   12/03/2027   Batch: RB771
            Deworming 02/06/2026 02/09/2026
        """.trimIndent()
        val vm = editor().loaded()
        vm.onPagesCaptured(listOf(capture("a"), capture("b")))
        vm.update { it.copy(clinic = "My own clinic name") }
        vm.continueToDetails()
        val s = awaitValue(vm.state) { it.step == ScanStep.DETAILS && !it.reading && it.readSummary != null }

        assertEquals(2, reader.read.size, "every page is read")
        assertEquals("My own clinic name", s.clinic, "the owner's text is never overwritten")
        assertEquals("Dr. S. Karthik", s.vetName)
        assertEquals(listOf("Rabies", "Deworming"), s.vaccines.map { it.name })
        assertEquals("RB771", s.vaccines[0].batch)
        assertEquals(kotlinx.datetime.LocalDate(2027, 3, 12), s.vaccines[0].nextDue)
        assertEquals(kotlinx.datetime.LocalDate(2026, 6, 2), s.issuedOn)
        assertEquals(DocumentType.VACCINE_CARD, s.type)
        assertEquals("Vaccination record", s.title)
        assertTrue(s.readSummary!!.contains("2 entries"))
        assertTrue(s.recognizedText.contains("Rabies"))

        // Going back and forward again doesn't re-read unchanged pages.
        assertTrue(vm.back())
        assertEquals(ScanStep.REVIEW, vm.state.value.step)
        vm.continueToDetails()
        assertEquals(2, reader.read.size)
    }

    @Test
    fun withoutTextRecognitionTheOwnerFillsTheForm() {
        val vm = editor(FakeTextReader(isAvailable = false)).loaded()
        vm.onPagesCaptured(listOf(capture("a")))
        vm.continueToDetails()
        val s = vm.state.value
        assertEquals(ScanStep.DETAILS, s.step)
        assertTrue(s.vaccines.isEmpty())
        assertNotNull(s.readSummary)
        assertFalse(s.textReaderAvailable)
    }

    @Test
    fun typeChangeSwapsTheDefaultTitleButKeepsACustomOne() {
        val vm = editor().loaded()
        vm.setType(DocumentType.PRESCRIPTION)
        assertEquals("Prescription", vm.state.value.title)
        vm.setType(DocumentType.LAB_REPORT)
        assertEquals("Lab report", vm.state.value.title)
        vm.update { it.copy(title = "Kidney panel") }
        vm.setType(DocumentType.VET_CARD)
        assertEquals("Kidney panel", vm.state.value.title)
    }

    @Test
    fun backWalksTheStepsThenLetsTheScreenClose() {
        val vm = editor().loaded()
        vm.onPagesCaptured(listOf(capture("a")))
        reader.text = ""
        vm.continueToDetails()
        awaitValue(vm.state) { !it.reading }
        assertTrue(vm.back())
        assertEquals(ScanStep.REVIEW, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(ScanStep.SOURCE, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun vaccineRowsCanBeAddedEditedAndRemoved() {
        val vm = editor().loaded()
        vm.addVaccine("Rabies")
        vm.addVaccine()
        val key = vm.state.value.vaccines[1].key
        vm.updateVaccine(key) { it.copy(name = "FVRCP", batch = "F1") }
        assertEquals(listOf("Rabies", "FVRCP"), vm.state.value.vaccines.map { it.name })
        vm.removeVaccine(vm.state.value.vaccines[0].key)
        assertEquals(listOf("FVRCP"), vm.state.value.vaccines.map { it.name })
    }

    @Test
    fun saveCreatesTheRecordPdfAndDueDateVisits() {
        val vm = editor().loaded()
        val a = capture("a")
        vm.onPagesCaptured(listOf(a))
        vm.rotate(0)
        awaitValue(vm.state) { !it.processing && it.pages[0].quarterTurns == 1 }
        val rendered = vm.state.value.pages[0].current
        reader.text = ""
        vm.continueToDetails()
        awaitValue(vm.state) { !it.reading }
        val nextYear = SystemAppClock.today().plusDays(300)
        vm.addVaccine("Rabies")
        val key = vm.state.value.vaccines[0].key
        vm.updateVaccine(key) { it.copy(givenOn = SystemAppClock.today(), nextDue = nextYear, batch = "RB1") }
        vm.addVaccine("  ") // blank rows are not saved
        assertTrue(vm.state.value.hasFutureDueDate)

        var savedId: String? = null
        vm.save { savedId = it }
        eventually { savedId != null }

        val record = assertNotNull(runBlocking { docs.get(savedId!!) })
        assertEquals(listOf("Rabies"), record.vaccines.map { it.name })
        assertEquals("David", record.loggedBy.name)
        assertEquals(1, record.pagePaths.size)
        assertTrue(File(record.pagePaths[0]).exists())
        assertTrue(File(record.pdfPath!!).readBytes().decodeToString().startsWith("%PDF"))
        assertEquals("Vaccination: Rabies", visits.visits.value.single().title)
        assertEquals(1, reminders.reschedules)
        // Nothing from the session is left in the inbox: neither the original capture nor the rotated render.
        assertFalse(File(a).exists())
        assertFalse(File(rendered).exists())
        assertTrue(File(service.inboxDir).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun editingOpensOnDetailsWithTheSavedEntry() {
        val first = editor().loaded()
        first.onPagesCaptured(listOf(capture("a")))
        first.addVaccine("Rabies")
        first.update { it.copy(addReminders = false, notes = "calm") }
        var id: String? = null
        first.save { id = it }
        eventually { id != null }
        assertTrue(visits.visits.value.isEmpty(), "reminders were switched off")

        val vm = editor().loaded(id)
        val s = vm.state.value
        assertFalse(s.isNew)
        assertEquals(ScanStep.DETAILS, s.step)
        assertEquals(listOf("Rabies"), s.vaccines.map { it.name })
        assertEquals("calm", s.notes)
        assertFalse(s.addReminders)
        assertFalse(vm.back(), "on details, back closes the edit screen")
        vm.editPages()
        assertEquals(ScanStep.REVIEW, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(ScanStep.DETAILS, vm.state.value.step)
    }

    @Test
    fun detailRebuildsAMissingPdfAndDeletes() {
        val saved = runBlocking {
            service.save(
                com.goldenpaw.domain.model.HealthDocument(
                    id = "r1", petId = "pet", type = DocumentType.VET_CARD, title = "", issuedOn = null, clinic = "", vetName = "",
                    notes = "", vaccines = emptyList(), pagePaths = listOf(capture("a")), pdfPath = null, recognizedText = "",
                    createdAt = SystemAppClock.now(),
                ),
                ownerName = "",
            )
        }
        File(saved.pdfPath!!).delete()

        val vm = RecordDetailViewModel(docs, service, pets, FakeSettings(), files, SystemAppClock)
        vm.load("pet", "r1")
        val s = awaitValue(vm.state) { it.pdfReady }
        assertEquals("Vet card", s.record!!.displayTitle)
        assertEquals("Bruno", s.petName)

        var done = false
        vm.delete { done = true }
        eventually { done }
        awaitValue(vm.state) { it.record == null }
        assertNull(runBlocking { docs.get("r1") })
        assertFalse(File(files.documentsDir, "r1").exists())
    }

    @Test
    fun petRecordsSurfaceDueVaccines() {
        val today = SystemAppClock.today()
        runBlocking {
            docs.upsert(
                com.goldenpaw.domain.model.HealthDocument(
                    id = "r1", petId = "pet", type = DocumentType.VACCINE_CARD, title = "", issuedOn = null, clinic = "",
                    vetName = "", notes = "", pagePaths = emptyList(), pdfPath = null, recognizedText = "",
                    createdAt = SystemAppClock.now(),
                    vaccines = listOf(
                        VaccineRecord("Rabies", nextDue = today.plusDays(10)),
                        VaccineRecord("DHPP", nextDue = today.plusDays(200)),
                    ),
                ),
            )
        }
        val vm = PetRecordsViewModel(docs, SystemAppClock)
        vm.load("pet")
        val s = awaitValue(vm.state) { it.records.isNotEmpty() }
        assertEquals(listOf("Rabies"), s.due.map { it.vaccine.name })
        assertEquals(VaccineStatus.DUE_SOON, s.due.single().status)
    }
}
