package com.goldenpaw

import com.goldenpaw.core.FixedAppClock
import com.goldenpaw.data.local.SyncState
import com.goldenpaw.data.mapper.toDomain
import com.goldenpaw.data.mapper.toEntity
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.VaccineDueLogic
import com.goldenpaw.domain.model.VaccineRecord
import com.goldenpaw.domain.model.VaccineStatus
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.report.DocumentCanvasFactory
import com.goldenpaw.report.HealthDocumentPdfLayout
import com.goldenpaw.report.HealthRecordService
import com.goldenpaw.report.JpegInfo
import com.goldenpaw.report.ReportOptions
import com.goldenpaw.report.ScannedPage
import com.goldenpaw.report.SimplePdfCanvas
import com.goldenpaw.report.VetReportData
import com.goldenpaw.report.VetReportLayout
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY = LocalDate(2026, 10, 8)

/** 8 Oct 2026, 09:00 UTC (the fixed clock uses UTC). */
private val NOW = Instant.fromEpochMilliseconds(1_791_450_000_000)

private fun record(
    id: String = "rec1",
    petId: String = "pet",
    vaccines: List<VaccineRecord> = emptyList(),
    pages: List<String> = emptyList(),
    type: DocumentType = DocumentType.VACCINE_CARD,
    title: String = "Vaccination record",
    issuedOn: LocalDate? = LocalDate(2026, 3, 12),
) = HealthDocument(
    id = id, petId = petId, type = type, title = title, issuedOn = issuedOn, clinic = "Paws & Claws Veterinary Clinic",
    vetName = "Dr. S. Karthik", notes = "Annual boosters", vaccines = vaccines, pagePaths = pages, pdfPath = null,
    recognizedText = "Rabies 12/03/2026", createdAt = TestData.epoch, loggedBy = Attribution("Priya", "c1"),
)

class VaccineStatusTest {
    @Test
    fun statusAndDaysUntilDue() {
        assertEquals(VaccineStatus.NO_DUE_DATE, VaccineRecord("Rabies").status(TODAY))
        assertNull(VaccineRecord("Rabies").daysUntilDue(TODAY))

        val overdue = VaccineRecord("Rabies", nextDue = LocalDate(2026, 10, 7))
        assertEquals(-1, overdue.daysUntilDue(TODAY))
        assertEquals(VaccineStatus.OVERDUE, overdue.status(TODAY))

        assertEquals(VaccineStatus.DUE_SOON, VaccineRecord("Rabies", nextDue = TODAY).status(TODAY))
        assertEquals(VaccineStatus.DUE_SOON, VaccineRecord("Rabies", nextDue = LocalDate(2026, 11, 7)).status(TODAY)) // 30 days
        assertEquals(VaccineStatus.UP_TO_DATE, VaccineRecord("Rabies", nextDue = LocalDate(2026, 11, 8)).status(TODAY)) // 31 days
        assertEquals(VaccineStatus.DUE_SOON, VaccineRecord("Rabies", nextDue = LocalDate(2026, 11, 8)).status(TODAY, soonDays = 31))
    }

    @Test
    fun displayTitleFallsBackToTheTypeDefault() {
        assertEquals("Prescription", record(type = DocumentType.PRESCRIPTION, title = " ").displayTitle)
        assertEquals("Lab report", record(type = DocumentType.LAB_REPORT, title = "").displayTitle)
        assertEquals("My card", record(title = "My card").displayTitle)
        assertEquals(DocumentType.OTHER, DocumentType.from("nonsense"))
        assertEquals(DocumentType.VET_CARD, DocumentType.from("VET_CARD"))
    }
}

class VaccineDueLogicTest {
    @Test
    fun newestRecordPerVaccineWinsAndOnlyDueOrOverdueAreReturned() {
        val old = record(
            id = "old",
            vaccines = listOf(
                VaccineRecord("Rabies", givenOn = LocalDate(2024, 10, 1), nextDue = LocalDate(2025, 10, 1)), // superseded
                VaccineRecord("Deworming", givenOn = LocalDate(2026, 6, 2), nextDue = LocalDate(2026, 9, 2)), // overdue
            ),
        )
        val new = record(
            id = "new",
            vaccines = listOf(
                VaccineRecord("rabies ", givenOn = LocalDate(2025, 10, 20), nextDue = LocalDate(2026, 10, 20)), // due soon
                VaccineRecord("DHPPi + Lepto", givenOn = LocalDate(2026, 3, 12), nextDue = LocalDate(2027, 3, 12)), // fine
                VaccineRecord("Kennel cough (Bordetella)"), // no due date
            ),
        )
        val due = VaccineDueLogic.dueSoon(listOf(old, new), TODAY)
        assertEquals(listOf("Deworming", "rabies "), due.map { it.vaccine.name })
        assertEquals(VaccineStatus.OVERDUE, due[0].status)
        assertEquals("old", due[0].documentId)
        assertEquals(VaccineStatus.DUE_SOON, due[1].status)
        assertEquals("new", due[1].documentId)
    }

    @Test
    fun petsAreKeptApart() {
        val a = record(id = "a", petId = "dog", vaccines = listOf(VaccineRecord("Rabies", LocalDate(2025, 10, 1), LocalDate(2026, 10, 1))))
        val b = record(id = "b", petId = "cat", vaccines = listOf(VaccineRecord("Rabies", LocalDate(2026, 9, 1), LocalDate(2027, 9, 1))))
        val due = VaccineDueLogic.dueSoon(listOf(a, b), TODAY)
        assertEquals(listOf("dog"), due.map { it.petId })
    }
}

class HealthDocumentMapperTest {
    @Test
    fun roundTripsThroughTheEntity() {
        val doc = record(
            vaccines = listOf(
                VaccineRecord("  Rabies ", LocalDate(2026, 3, 12), LocalDate(2027, 3, 12), " RB-1 "),
                VaccineRecord("Deworming", null, LocalDate(2026, 9, 2)),
                VaccineRecord("   "), // blank rows are dropped
            ),
            pages = listOf("/data/documents/rec1/page-1.jpg", "/data/documents/rec1/page-2.jpg"),
        ).copy(pdfPath = "/data/documents/rec1/x.pdf")
        val entity = doc.toEntity(now = 42L)
        assertEquals(SyncState.PENDING, entity.syncState)
        assertEquals(42L, entity.updatedAt)
        assertEquals("VACCINE_CARD", entity.type)

        val back = entity.toDomain()
        assertEquals(DocumentType.VACCINE_CARD, back.type)
        assertEquals(doc.issuedOn, back.issuedOn)
        assertEquals(doc.pagePaths, back.pagePaths)
        assertEquals(doc.pdfPath, back.pdfPath)
        assertEquals(Attribution("Priya", "c1"), back.loggedBy)
        assertEquals(
            listOf(
                VaccineRecord("Rabies", LocalDate(2026, 3, 12), LocalDate(2027, 3, 12), "RB-1"),
                VaccineRecord("Deworming", null, LocalDate(2026, 9, 2)),
            ),
            back.vaccines,
        )
    }

    @Test
    fun corruptJsonDecodesToEmptyLists() {
        val entity = record().toEntity(now = 1L).copy(vaccines = "{not json", pages = "")
        val back = entity.toDomain()
        assertTrue(back.vaccines.isEmpty())
        assertTrue(back.pagePaths.isEmpty())
    }
}

class HealthDocumentPdfTest {
    private fun pages(n: Int) = List(n) { fakeJpeg(1200, 1600).let { ScannedPage(it, JpegInfo.read(it)!!) } }

    @Test
    fun summaryPageThenOnePagePerScan() {
        val doc = record(
            vaccines = listOf(
                VaccineRecord("DHPPi + Lepto", LocalDate(2026, 3, 12), LocalDate(2027, 3, 12), "A2341"),
                VaccineRecord("Rabies", LocalDate(2025, 10, 1), LocalDate(2026, 10, 20)),
            ),
        )
        val pdf = HealthDocumentPdfLayout(SimplePdfCanvas()).render(doc, TestData.pet(), pages(2), "David", TODAY).latin1()
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertEquals(3, Regex("/Type /Page ").findAll(pdf).count())
        assertEquals(2, Regex("/Subtype /Image").findAll(pdf).count())
        listOf("Vaccination record", "Bruno", "DHPPi + Lepto", "A2341", "Paws & Claws Veterinary Clinic", "VACCINATIONS", "scan 2 of 2")
            .forEach { assertTrue(it in pdf, "missing '$it'") }
    }

    @Test
    fun nonVaccineRecordsHaveNoVaccineTable() {
        val doc = record(type = DocumentType.LAB_REPORT, title = "Blood work")
        val pdf = HealthDocumentPdfLayout(SimplePdfCanvas()).render(doc, TestData.pet(), pages(1), "", TODAY).latin1()
        assertFalse("VACCINATIONS" in pdf)
        assertEquals(2, Regex("/Type /Page ").findAll(pdf).count())
    }

    @Test
    fun detectsTextTheBuiltInFontsCannotShow() {
        assertFalse(HealthDocumentPdfLayout.needsUnicode("Bruno – 12 Mar 2026 · Café"))
        assertTrue(HealthDocumentPdfLayout.needsUnicode("புருனோ"))
        assertTrue(HealthDocumentPdfLayout.needsUnicode("ब्रूनो"))
    }

    @Test
    fun vetReportIncludesVaccinations() {
        val data = VetReportData(
            pet = TestData.pet(), options = ReportOptions(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)),
            medications = emptyList(), slots = emptyList(), weights = emptyList(), checkIns = emptyList(),
            symptoms = emptyList(), unit = WeightUnit.KG, ownerName = "Priya", generatedOn = TODAY,
            caregivers = emptyList(),
            vaccinations = listOf(VaccineRecord("Rabies", LocalDate(2025, 10, 1), LocalDate(2026, 10, 1))),
        )
        val pdf = VetReportLayout(SimplePdfCanvas()).render(data).latin1()
        assertTrue("VACCINATIONS & PREVENTIVES" in pdf)
        assertTrue("overdue" in pdf)
    }
}

class HealthRecordServiceTest {
    private val files = FakeFiles()
    private val docs = FakeHealthDocuments()
    private val visits = FakeVisits()
    private val clock = FixedAppClock(NOW)
    private val service = HealthRecordService(
        documents = docs,
        pets = FakePets(TestData.pet()),
        visits = visits,
        files = files,
        canvasFactory = DocumentCanvasFactory { SimplePdfCanvas() },
        clock = clock,
    )

    private fun capture(name: String): String = "${service.inboxDir}/$name.jpg".also { files.writeBytes(it, fakeJpeg()) }

    @Test
    fun saveMovesPagesOutOfTheInboxAndWritesAPdf() = runTest {
        val saved = service.save(record(pages = listOf(capture("a"), capture("b"))), ownerName = "David")

        assertEquals(2, saved.pagePaths.size)
        assertTrue(saved.pagePaths.all { it.startsWith("/data/documents/rec1/page-") })
        assertTrue(files.under(service.inboxDir).isEmpty(), "inbox should be empty")
        val pdf = assertNotNull(saved.pdfPath)
        assertTrue(pdf.startsWith("/data/documents/rec1/GoldenPaw_Bruno_Vaccination_record_2026-03-12"))
        assertTrue(files.readBytes(pdf)!!.latin1().startsWith("%PDF"))
        assertEquals(saved, docs.get("rec1"))
    }

    @Test
    fun editingRemovesDroppedPagesAndReplacesThePdf() = runTest {
        val first = service.save(record(pages = listOf(capture("a"), capture("b"))), ownerName = "")
        val keep = first.pagePaths[0]
        val dropped = first.pagePaths[1]

        val second = service.save(first.copy(title = "Rabies certificate", pagePaths = listOf(keep, capture("c"))), ownerName = "")

        assertFalse(files.exists(dropped))
        assertTrue(files.exists(keep))
        assertEquals(keep, second.pagePaths[0])
        assertFalse(files.exists(first.pdfPath), "old PDF should be deleted when its name changes")
        assertTrue(files.exists(second.pdfPath))
        assertEquals(3, files.under("/data/documents/rec1").size) // 2 pages + 1 pdf
    }

    @Test
    fun missingPagesAreSkipped() = runTest {
        val saved = service.save(record(pages = listOf("${service.inboxDir}/gone.jpg", capture("a"))), ownerName = "")
        assertEquals(1, saved.pagePaths.size)
    }

    @Test
    fun deleteRemovesTheRowAndEveryFile() = runTest {
        val saved = service.save(record(pages = listOf(capture("a"))), ownerName = "")
        service.delete(saved.id)
        assertNull(docs.get(saved.id))
        assertTrue(files.under("/data/documents/rec1").isEmpty())
    }

    @Test
    fun discardInboxNeverTouchesSavedRecords() = runTest {
        val saved = service.save(record(pages = listOf(capture("a"))), ownerName = "")
        val stray = capture("stray")
        service.discardInbox(listOf(stray) + saved.pagePaths)
        assertFalse(files.exists(stray))
        assertTrue(saved.pagePaths.all { files.exists(it) })
    }

    @Test
    fun futureDueDatesBecomeVetVisitsOnce() = runTest {
        val doc = record(
            vaccines = listOf(
                VaccineRecord("Rabies", LocalDate(2025, 10, 20), LocalDate(2026, 10, 20), batch = "RB-9"),
                VaccineRecord("Deworming", LocalDate(2026, 6, 2), LocalDate(2026, 9, 2)), // past: skipped
                VaccineRecord("FVRCP"), // no due date: skipped
            ),
        )
        assertEquals(1, service.addDueDateVisits(doc, Attribution("David", null)))
        assertEquals(0, service.addDueDateVisits(doc, Attribution("David", null)), "second call must not duplicate")

        val visit = visits.visits.value.single()
        assertEquals("Vaccination: Rabies", visit.title)
        assertEquals("pet", visit.petId)
        assertEquals("Paws & Claws Veterinary Clinic", visit.clinic)
        assertTrue("RB-9" in visit.notes)
        assertFalse(visit.completed)
    }
}
