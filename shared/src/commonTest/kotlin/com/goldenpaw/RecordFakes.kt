package com.goldenpaw

import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.HealthDocumentRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/** In-memory file system for record tests. Paths are plain strings; directories are implicit. */
class FakeFiles : AppFiles {
    val files = linkedMapOf<String, ByteArray>()
    override val dataDir: String = "/data"
    override val cacheDir: String = "/cache"
    override val photosDir: String = "/data/photos"
    override val documentsDir: String = "/data/documents"

    override fun exists(path: String?): Boolean = path != null && path in files
    override fun delete(path: String?): Boolean = path != null && files.remove(path) != null
    override fun deleteRecursively(dir: String) {
        files.keys.filter { it == dir || it.startsWith("$dir/") }.forEach { files.remove(it) }
    }
    override fun writeBytes(path: String, bytes: ByteArray) {
        files[path] = bytes
    }
    override fun readBytes(path: String): ByteArray? = files[path]

    fun under(dir: String): List<String> = files.keys.filter { it.startsWith("$dir/") }.sorted()
}

class FakeHealthDocuments : HealthDocumentRepository {
    val rows = MutableStateFlow<Map<String, HealthDocument>>(emptyMap())
    val deleted = mutableSetOf<String>()

    override fun observeForPet(petId: String): Flow<List<HealthDocument>> =
        rows.map { all -> all.values.filter { it.petId == petId && it.id !in deleted } }

    override fun observeForActivePets(): Flow<List<HealthDocument>> = rows.map { all -> all.values.filter { it.id !in deleted } }

    override suspend fun get(id: String): HealthDocument? = rows.value[id]?.takeIf { id !in deleted }

    override suspend fun upsert(document: HealthDocument) {
        rows.value = rows.value + (document.id to document)
    }

    override suspend fun delete(id: String) {
        deleted += id
        rows.value = rows.value.toMap()
    }
}

class FakePets(vararg pets: Pet) : PetRepository {
    private val all = MutableStateFlow(pets.associateBy { it.id })
    override fun observeActivePets(): Flow<List<Pet>> = all.map { it.values.filter { p -> p.archivedAt == null } }
    override fun observeArchivedPets(): Flow<List<Pet>> = all.map { it.values.filter { p -> p.archivedAt != null } }
    override fun observePet(id: String): Flow<Pet?> = all.map { it[id] }
    override suspend fun getPet(id: String): Pet? = all.value[id]
    override suspend fun activePetCount(): Int = all.value.size
    override suspend fun upsert(pet: Pet) {
        all.value = all.value + (pet.id to pet)
    }
    override suspend fun archive(id: String) = Unit
    override suspend fun restore(id: String) = Unit
    override suspend fun delete(id: String) {
        all.value = all.value - id
    }
}

class FakeVisits : VetVisitRepository {
    val visits = MutableStateFlow<List<VetVisit>>(emptyList())
    override fun observeForPet(petId: String): Flow<List<VetVisit>> = visits.map { list -> list.filter { it.petId == petId } }
    override fun observeUpcoming(from: Instant): Flow<List<VetVisit>> = visits.map { list -> list.filter { it.at >= from } }
    override suspend fun upcoming(from: Instant): List<VetVisit> = visits.value.filter { it.at >= from }
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

/** A real (tiny) JPEG header: SOI, JFIF APP0, SOF0 for [width]×[height] RGB, EOI. Enough for JpegInfo and the PDF writer. */
fun fakeJpeg(width: Int = 300, height: Int = 400): ByteArray = intArrayOf(
    0xFF, 0xD8,
    0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    0xFF, 0xC0, 0x00, 0x11, 0x08, height shr 8, height and 0xFF, width shr 8, width and 0xFF, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
    0xFF, 0xD9,
).map { it.toByte() }.toByteArray()

/** PDF bytes as Latin-1 text, so byte offsets equal string offsets. */
fun ByteArray.latin1(): String = buildString(size) { this@latin1.forEach { append((it.toInt() and 0xFF).toChar()) } }
