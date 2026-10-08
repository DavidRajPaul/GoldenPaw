package com.goldenpaw

import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.DocumentTextReader
import com.goldenpaw.domain.repository.HealthDocumentRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.UserSettings
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.domain.usecase.CareAttribution
import com.goldenpaw.platform.PageImageProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import java.io.File
import java.nio.file.Files

/** Real files in a temp folder, so the PDF writer and the inbox clean-up run for real. */
class TempFiles : AppFiles {
    val root: File = Files.createTempDirectory("goldenpaw-test").toFile()
    override val dataDir: String get() = root.absolutePath
    override val cacheDir: String get() = File(root, "cache").apply { mkdirs() }.absolutePath
    override val photosDir: String get() = File(root, "photos").apply { mkdirs() }.absolutePath
    override val documentsDir: String get() = File(root, "documents").apply { mkdirs() }.absolutePath
    override fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).exists()
    override fun delete(path: String?): Boolean = !path.isNullOrBlank() && File(path).delete()
    override fun deleteRecursively(dir: String) {
        File(dir).deleteRecursively()
    }
    override fun writeBytes(path: String, bytes: ByteArray) {
        File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }
    override fun readBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    fun cleanup() = root.deleteRecursively()
}

class FakeHealthDocuments : HealthDocumentRepository {
    val rows = MutableStateFlow<Map<String, HealthDocument>>(emptyMap())
    override fun observeForPet(petId: String): Flow<List<HealthDocument>> = rows.map { it.values.filter { d -> d.petId == petId } }
    override fun observeForActivePets(): Flow<List<HealthDocument>> = rows.map { it.values.toList() }
    override suspend fun get(id: String): HealthDocument? = rows.value[id]
    override suspend fun upsert(document: HealthDocument) {
        rows.value = rows.value + (document.id to document)
    }
    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }
}

class FakePets(vararg pets: Pet) : PetRepository {
    private val all = MutableStateFlow(pets.associateBy { it.id })
    override fun observeActivePets(): Flow<List<Pet>> = all.map { it.values.toList() }
    override fun observeArchivedPets(): Flow<List<Pet>> = all.map { emptyList() }
    override fun observePet(id: String): Flow<Pet?> = all.map { it[id] }
    override suspend fun getPet(id: String): Pet? = all.value[id]
    override suspend fun activePetCount(): Int = all.value.size
    override suspend fun upsert(pet: Pet) {
        all.value = all.value + (pet.id to pet)
    }
    override suspend fun archive(id: String) = Unit
    override suspend fun restore(id: String) = Unit
    override suspend fun delete(id: String) = Unit
}

class FakeVisits : VetVisitRepository {
    val visits = MutableStateFlow<List<VetVisit>>(emptyList())
    override fun observeForPet(petId: String): Flow<List<VetVisit>> = visits.map { l -> l.filter { it.petId == petId } }
    override fun observeUpcoming(from: Instant): Flow<List<VetVisit>> = visits
    override suspend fun upcoming(from: Instant): List<VetVisit> = visits.value
    override suspend fun get(id: String): VetVisit? = visits.value.firstOrNull { it.id == id }
    override suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<VetVisit> =
        visits.value.filter { it.petId == petId && it.at >= from && it.at <= to }
    override suspend fun upsert(visit: VetVisit) {
        visits.value = visits.value.filterNot { it.id == visit.id } + visit
    }
    override suspend fun delete(id: String) {
        visits.value = visits.value.filterNot { it.id == id }
    }
}

class FakeSettings(initial: UserSettings = UserSettings(ownerName = "David")) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state
    override suspend fun current(): UserSettings = state.value
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
    override suspend fun clear() {
        state.value = UserSettings()
    }
}

class FakeReminders : ReminderGateway {
    var reschedules = 0
    override suspend fun rescheduleAll() {
        reschedules += 1
    }
    override fun cancelDoseNotification(medicationId: String, scheduledAt: Instant) = Unit
    override suspend fun notifyRefill(medication: Medication) = Unit
}

object FakeAttribution : CareAttribution {
    override suspend fun attributionFor(petId: String): Attribution = Attribution("David", "c1")
}

class FakeTextReader(var text: String = "", override val isAvailable: Boolean = true) : DocumentTextReader {
    val read = mutableListOf<String>()
    override suspend fun read(imagePath: String): String {
        read += imagePath
        return text
    }
}

/** Copies the source to a new file and records what was asked, like a real rotate / enhance would. */
class FakeProcessor : PageImageProcessor {
    val calls = mutableListOf<Triple<String, Int, Boolean>>()
    override suspend fun process(sourcePath: String, outputDir: String, quarterTurns: Int, enhance: Boolean): String? {
        calls += Triple(sourcePath, quarterTurns, enhance)
        val out = File(outputDir, "processed-${calls.size}.jpg").apply { parentFile?.mkdirs() }
        File(sourcePath).copyTo(out, overwrite = true)
        return out.absolutePath
    }
}

fun testPet(id: String = "pet") = Pet(
    id = id, householdId = "h1", name = "Bruno", species = Species.DOG, breed = "Labrador Retriever", sex = PetSex.MALE,
    birthDate = null, photoPath = null, conditions = emptyList(), vetName = "Happy Tails Clinic", vetPhone = "",
    notes = "", createdAt = Instant.fromEpochMilliseconds(0), archivedAt = null,
)

/** Header-only JPEG (SOI, APP0, SOF0 300×400, EOI): enough for JpegInfo and the PDF writer. */
fun fakeJpeg(): ByteArray = intArrayOf(
    0xFF, 0xD8,
    0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    0xFF, 0xC0, 0x00, 0x11, 0x08, 0x01, 0x90, 0x01, 0x2C, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
    0xFF, 0xD9,
).map { it.toByte() }.toByteArray()

/** Waits (on the test thread) until [flow] emits a value matching [predicate]. ViewModels run on the Swing main dispatcher. */
fun <T> awaitValue(flow: Flow<T>, timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
    runBlocking { withTimeout(timeoutMs) { flow.first(predicate) } }

/** Polls [condition] until it's true (for results delivered through callbacks rather than state). */
fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
        Thread.sleep(10)
    }
}
