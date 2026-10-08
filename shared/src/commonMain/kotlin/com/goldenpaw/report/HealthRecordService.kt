package com.goldenpaw.report

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.at
import com.goldenpaw.core.newId
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.today
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.HealthDocumentRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime

/**
 * Files and PDFs for scanned health records.
 *
 * Layout on disk (app-private, never in the photo gallery):
 *   documents/inbox/…            fresh captures that haven't been saved yet
 *   documents/<recordId>/page-N  the pages of a saved record (upright JPEGs)
 *   documents/<recordId>/<name>.pdf  the generated PDF (summary page + every scan)
 */
class HealthRecordService(
    private val documents: HealthDocumentRepository,
    private val pets: PetRepository,
    private val visits: VetVisitRepository,
    private val files: AppFiles,
    private val canvasFactory: DocumentCanvasFactory,
    private val clock: AppClock,
) {
    val inboxDir: String get() = "${files.documentsDir}/inbox"

    private fun recordDir(id: String) = "${files.documentsDir}/$id"

    /**
     * Saves the confirmed entry: moves new pages out of the inbox into the record's folder, deletes
     * pages the owner removed, regenerates the PDF and stores the row. Returns the saved record.
     */
    suspend fun save(document: HealthDocument, ownerName: String): HealthDocument = withContext(Dispatchers.IO) {
        val previous = documents.get(document.id)
        val dir = recordDir(document.id)
        val stamp = clock.now().toEpochMilliseconds().toString(36)

        val pagePaths = document.pagePaths.mapIndexedNotNull { index, path ->
            if (path.startsWith("$dir/")) {
                path
            } else {
                val bytes = files.readBytes(path) ?: return@mapIndexedNotNull null
                val target = "$dir/page-${index + 1}-$stamp.jpg"
                files.writeBytes(target, bytes)
                if (path.startsWith(inboxDir)) files.delete(path)
                target
            }
        }
        previous?.pagePaths?.filter { it !in pagePaths }?.forEach { files.delete(it) }

        val withPages = document.copy(pagePaths = pagePaths)
        val pdf = writePdf(withPages, ownerName)
        if (previous?.pdfPath != null && previous.pdfPath != pdf) files.delete(previous.pdfPath)
        val saved = withPages.copy(pdfPath = pdf)
        documents.upsert(saved)
        saved
    }

    /** Builds the PDF for [document] and returns its path (null if the pet no longer exists). */
    suspend fun writePdf(document: HealthDocument, ownerName: String): String? = withContext(Dispatchers.IO) {
        val pet = pets.getPet(document.petId) ?: return@withContext null
        val pages = document.pagePaths.mapNotNull { path ->
            val bytes = files.readBytes(path) ?: return@mapNotNull null
            val info = JpegInfo.read(bytes) ?: return@mapNotNull null
            ScannedPage(bytes, info)
        }
        val text = buildString {
            append(pet.name).append(pet.breed).append(document.title).append(document.clinic)
            append(document.vetName).append(document.notes).append(ownerName)
            document.vaccines.forEach { append(it.name).append(it.batch) }
        }
        val canvas = canvasFactory.create(needsUnicode = HealthDocumentPdfLayout.needsUnicode(text))
        val bytes = HealthDocumentPdfLayout(canvas).render(document, pet, pages, ownerName, clock.today())
        val safePet = pet.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "pet" }
        val safeTitle = document.displayTitle.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "record" }
        val date = document.issuedOn ?: clock.today()
        val path = "${recordDir(document.id)}/GoldenPaw_${safePet}_${safeTitle}_$date.pdf"
        files.writeBytes(path, bytes)
        path
    }

    /** Removes the record and its files. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        documents.delete(id)
        files.deleteRecursively(recordDir(id))
    }

    /** Throws away captures that were never saved (e.g. the owner backed out of the scan flow). */
    fun discardInbox(paths: Collection<String>) {
        paths.filter { it.startsWith(inboxDir) }.forEach { files.delete(it) }
    }

    /**
     * Adds an upcoming vet visit ("Vaccination: Rabies") for every future due date on the record,
     * so the due date shows on Today and gets the evening-before reminder. Skips dates that already
     * have a visit with the same title. Returns how many were added.
     */
    suspend fun addDueDateVisits(document: HealthDocument, by: Attribution): Int {
        val zone = clock.zone()
        val today = clock.today()
        var added = 0
        document.vaccines.forEach { v ->
            val due = v.nextDue ?: return@forEach
            if (due < today || v.name.isBlank()) return@forEach
            val title = "Vaccination: ${v.name.trim()}"
            val existing = visits.forPetBetween(document.petId, due.at(LocalTime(0, 0), zone), due.plusDays(1).at(LocalTime(0, 0), zone))
            if (existing.any { it.title.equals(title, ignoreCase = true) }) return@forEach
            visits.upsert(
                VetVisit(
                    id = newId(),
                    petId = document.petId,
                    title = title,
                    clinic = document.clinic,
                    at = due.at(LocalTime(10, 0), zone),
                    notes = "Due date from ${document.displayTitle}" + if (v.batch.isNotBlank()) " (last batch ${v.batch})" else "",
                    completed = false,
                    createdAt = clock.now(),
                    loggedBy = by,
                ),
            )
            added += 1
        }
        return added
    }
}
