package com.goldenpaw.data.remote

import com.goldenpaw.core.AppClock
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.data.local.SyncState as RowSyncState
import com.goldenpaw.data.mapper.instant
import com.goldenpaw.data.settings.KeyValueStore
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.CloudSession
import com.goldenpaw.domain.model.Household
import com.goldenpaw.domain.model.Invite
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.domain.model.SyncState
import com.goldenpaw.domain.model.SyncStatus
import com.goldenpaw.data.mapper.toDomain
import com.goldenpaw.data.mapper.toEntity
import com.goldenpaw.domain.repository.CareSyncRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Offline-first sync with Supabase (PostgREST).
 *
 *  1. Pull the households this user belongs to (+ their members).
 *  2. Push every local row marked `pending` (households → members → pets → meds → logs).
 *  3. Pull rows changed since this device's per-household cursor (server-assigned `synced_at`).
 *
 * Conflicts: last write wins per row by client `updated_at` (enforced by a server trigger, and
 * locally a pending row that is newer than the remote copy is kept). Dose events are append-only
 * (undo = soft delete) so two caregivers can never overwrite each other's doses.
 */
class SupabaseSyncRepository(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val auth: SupabaseAuthRepository,
    private val db: GoldenPawDatabase,
    private val store: KeyValueStore,
    private val settings: SettingsRepository,
    private val reminders: ReminderGateway,
    private val clock: AppClock,
) : CareSyncRepository {

    override val isAvailable: Boolean get() = config.isConfigured

    private val _state = MutableStateFlow(SyncState(if (config.isConfigured) SyncStatus.IDLE else SyncStatus.OFF))
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    private val mutex = Mutex()

    override suspend fun sync(): Result<Instant> = mutex.withLock {
        if (!isAvailable) return@withLock Result.failure(CloudException("Cloud isn't set up in this build"))
        _state.update { it.copy(status = SyncStatus.SYNCING, message = null) }
        runCatching { doSync() }
            .onSuccess { at -> _state.value = SyncState(SyncStatus.IDLE, at, null) }
            .onFailure { e -> _state.update { it.copy(status = SyncStatus.ERROR, message = e.message ?: "Sync failed") } }
    }

    private suspend fun requireSession(): CloudSession =
        auth.validSession() ?: throw CloudException("Sign in to share and sync", 401)

    private suspend fun doSync(): Instant {
        val session = requireSession()
        pullHouseholds(session)
        val cloudIds = db.householdDao().all().filter { it.cloudEnabled }.map { it.id }
        if (cloudIds.isNotEmpty()) {
            push(session, cloudIds)
            cloudIds.forEach { pullHousehold(session, it) }
            db.petDao().purgeSyncedDeletes()
        }
        val now = clock.now()
        settings.update { it.copy(lastSyncedAt = now) }
        runCatching { reminders.rescheduleAll() }
        return now
    }

    // ------------------------------------------------------------------ households & members

    private suspend fun pullHouseholds(session: CloudSession) {
        val dao = db.householdDao()
        val remote: List<HouseholdDto> = getRows(session, "households", emptyMap())
        val remoteIds = remote.map { it.id }.toSet()
        for (h in remote) {
            val local = dao.get(h.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > h.updatedAt) continue
            dao.upsert(h.toEntity())
        }
        // Households we used to see but no longer can: our access was removed. Drop the local copy.
        dao.all().filter { it.cloudEnabled && it.syncState == RowSyncState.SYNCED && it.id !in remoteIds }
            .forEach { dropHousehold(it.id) }

        if (remoteIds.isEmpty()) return
        val members: List<MemberDto> = getRows(session, "household_members", mapOf("household_id" to inList(remoteIds)))
        for (m in members) {
            val local = dao.member(m.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > m.updatedAt) continue
            dao.upsertMember(m.toEntity())
        }
        // If I've been removed (or my sitter access ended), drop that household's data from this device.
        val nowMillis = clock.now().toEpochMilliseconds()
        members.filter { it.userId == session.userId }
            .filter { it.status == MemberStatus.REMOVED.name || (it.accessUntil != null && it.accessUntil < nowMillis) }
            .forEach { mine ->
                val household = remote.firstOrNull { it.id == mine.householdId }
                if (household?.ownerId != session.userId) dropHousehold(mine.householdId)
            }
    }

    private suspend fun dropHousehold(householdId: String) {
        db.petDao().forHousehold(householdId).forEach { db.petDao().hardDelete(it.id) }
        db.householdDao().deleteMembers(householdId)
        db.householdDao().delete(householdId)
        SYNCED_TABLES
            .forEach { store.put(cursorKey(it, householdId), null) }
    }

    // ------------------------------------------------------------------ push

    private suspend fun push(session: CloudSession, cloudIds: List<String>) {
        val hh = db.householdDao()
        val pendingHouseholds = hh.all().filter { it.cloudEnabled && it.syncState == RowSyncState.PENDING }
        upsertRows(session, "households", pendingHouseholds.map { it.toDto() })
        pendingHouseholds.forEach { hh.markSynced(it.id, it.updatedAt) }

        val members = hh.pendingMembers(cloudIds)
        upsertRows(session, "household_members", members.map { it.toDto() })
        members.forEach { hh.markMemberSynced(it.id, it.updatedAt) }

        val petHousehold = db.petDao().allIncludingDeleted().associate { it.id to it.householdId }

        val pets = db.petDao().pending(cloudIds)
        upsertRows(session, "pets", pets.map { it.toDto() })
        pets.forEach { db.petDao().markSynced(it.id, it.updatedAt) }

        val meds = db.medicationDao().pending(cloudIds)
        upsertRows(session, "medications", meds.map { it.toDto(petHousehold.getValue(it.petId)) })
        meds.forEach { db.medicationDao().markSynced(it.id, it.updatedAt) }

        val doses = db.doseEventDao().pending(cloudIds)
        upsertRows(session, "dose_events", doses.map { it.toDto(petHousehold.getValue(it.petId)) })
        doses.forEach { db.doseEventDao().markSynced(it.id, it.updatedAt) }

        val checkIns = db.checkInDao().pending(cloudIds)
        upsertRows(session, "check_ins", checkIns.map { it.toDto(petHousehold.getValue(it.petId)) })
        checkIns.forEach { db.checkInDao().markSynced(it.id, it.updatedAt) }

        val weights = db.weightDao().pending(cloudIds)
        upsertRows(session, "weight_entries", weights.map { it.toDto(petHousehold.getValue(it.petId)) })
        weights.forEach { db.weightDao().markSynced(it.id, it.updatedAt) }

        val symptoms = db.symptomDao().pending(cloudIds)
        upsertRows(session, "symptom_entries", symptoms.map { it.toDto(petHousehold.getValue(it.petId)) })
        symptoms.forEach { db.symptomDao().markSynced(it.id, it.updatedAt) }

        val visits = db.vetVisitDao().pending(cloudIds)
        upsertRows(session, "vet_visits", visits.map { it.toDto(petHousehold.getValue(it.petId)) })
        visits.forEach { db.vetVisitDao().markSynced(it.id, it.updatedAt) }
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pullHousehold(session: CloudSession, householdId: String) {
        pullTable<PetDto>(session, "pets", householdId) { dto ->
            val local = db.petDao().getIncludingDeleted(dto.id)
            if (local.isNewerThan(dto.updatedAt)) return@pullTable
            db.petDao().upsert(dto.toEntity(local?.photoPath))
        }
        pullTable<MedicationDto>(session, "medications", householdId) { dto ->
            val local = db.medicationDao().getIncludingDeleted(dto.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > dto.updatedAt) return@pullTable
            db.medicationDao().upsert(dto.toEntity())
        }
        pullTable<DoseEventDto>(session, "dose_events", householdId) { dto ->
            val local = db.doseEventDao().get(dto.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > dto.updatedAt) return@pullTable
            db.doseEventDao().upsert(dto.toEntity())
        }
        pullTable<CheckInDto>(session, "check_ins", householdId) { dto ->
            val local = db.checkInDao().get(dto.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > dto.updatedAt) return@pullTable
            // One check-in per pet per day: if another device created one with a different id, the newer wins.
            val sameDay = db.checkInDao().getForDay(dto.petId, dto.epochDay)
            if (sameDay != null && sameDay.id != dto.id) {
                if (sameDay.updatedAt > dto.updatedAt) return@pullTable
                db.checkInDao().deleteOtherForDay(dto.petId, dto.epochDay, dto.id)
            }
            db.checkInDao().upsert(dto.toEntity())
        }
        pullTable<WeightDto>(session, "weight_entries", householdId) { dto ->
            val local = db.weightDao().get(dto.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > dto.updatedAt) return@pullTable
            db.weightDao().upsert(dto.toEntity())
        }
        pullTable<SymptomDto>(session, "symptom_entries", householdId) { dto ->
            val local = db.symptomDao().getIncludingDeleted(dto.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > dto.updatedAt) return@pullTable
            db.symptomDao().upsert(dto.toEntity(local?.photoPath))
        }
        pullTable<VetVisitDto>(session, "vet_visits", householdId) { dto ->
            val local = db.vetVisitDao().get(dto.id)
            if (local != null && local.syncState == RowSyncState.PENDING && local.updatedAt > dto.updatedAt) return@pullTable
            db.vetVisitDao().upsert(dto.toEntity())
        }
    }

    private fun com.goldenpaw.data.local.PetEntity?.isNewerThan(remoteUpdatedAt: Long): Boolean =
        this != null && syncState == RowSyncState.PENDING && updatedAt > remoteUpdatedAt

    /**
     * Pages through rows changed since the household cursor. Rows are applied in server order and the
     * cursor only advances past rows that were applied, so a failure (e.g. a dose whose medication
     * hasn't arrived yet) is retried on the next sync instead of being skipped forever.
     */
    private suspend inline fun <reified T> pullTable(
        session: CloudSession,
        table: String,
        householdId: String,
        crossinline apply: suspend (T) -> Unit,
    ) {
        val key = cursorKey(table, householdId)
        var cursor = store.get(key)
        while (true) {
            val params = buildMap {
                put("household_id", "eq.$householdId")
                if (cursor != null) put("synced_at", "gt.$cursor")
                put("order", "synced_at.asc")
                put("limit", PAGE_SIZE.toString())
            }
            val page: List<T> = getRows(session, table, params)
            if (page.isEmpty()) break
            var applied = 0
            for (row in page) {
                val ok = runCatching { apply(row) }.isSuccess
                if (!ok) break
                cursor = syncedAtOf(row) ?: cursor
                applied++
            }
            store.put(key, cursor)
            if (applied < page.size || page.size < PAGE_SIZE) break
        }
    }

    private fun syncedAtOf(row: Any?): String? = when (row) {
        is PetDto -> row.syncedAt
        is MedicationDto -> row.syncedAt
        is DoseEventDto -> row.syncedAt
        is CheckInDto -> row.syncedAt
        is WeightDto -> row.syncedAt
        is SymptomDto -> row.syncedAt
        is VetVisitDto -> row.syncedAt
        else -> null
    }

    // ------------------------------------------------------------------ sharing

    override suspend fun enableSharing(householdId: String): Result<Household> = runCatching {
        val session = requireSession()
        val hh = db.householdDao()
        val local = hh.get(householdId) ?: throw CloudException("Household not found")
        val now = clock.now().toEpochMilliseconds()
        hh.upsert(
            local.copy(
                cloudEnabled = true,
                ownerUserId = local.ownerUserId ?: session.userId,
                updatedAt = now,
                syncState = RowSyncState.PENDING,
            ),
        )
        // The owner caregiver on this device becomes the signed-in account.
        val members = hh.members(householdId)
        val owner = members.firstOrNull { it.role == CaregiverRole.OWNER.name && it.userId == null }
        if (owner != null && members.none { it.userId == session.userId }) {
            hh.upsertMember(owner.copy(userId = session.userId, updatedAt = now, syncState = RowSyncState.PENDING))
        }
        hh.markMembersPending(householdId)
        db.petDao().markHouseholdPending(householdId)
        db.medicationDao().markHouseholdPending(householdId)
        db.doseEventDao().markHouseholdPending(householdId)
        db.checkInDao().markHouseholdPending(householdId)
        db.weightDao().markHouseholdPending(householdId)
        db.symptomDao().markHouseholdPending(householdId)
        db.vetVisitDao().markHouseholdPending(householdId)
        mutex.withLock { doSync() }
        hh.get(householdId)?.toDomain() ?: throw CloudException("Household not found after sync")
    }

    override suspend fun createInvite(householdId: String, role: CaregiverRole, accessUntil: Instant?): Result<Invite> =
        runCatching {
            val session = requireSession()
            val response = http.post("${config.baseUrl}/rest/v1/rpc/gp_create_invite") {
                supabaseHeaders(config, session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(CreateInviteRequest(householdId, role.name, accessUntil?.toEpochMilliseconds()))
            }
            if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
            val dto = response.body<InviteDto>()
            Invite(
                code = dto.code,
                householdId = dto.householdId,
                role = CaregiverRole.from(dto.role),
                expiresAt = runCatching { Instant.parse(dto.expiresAt) }.getOrElse { clock.now() },
                accessUntil = dto.accessUntil?.instant(),
            )
        }

    override suspend fun acceptInvite(code: String, displayName: String): Result<Household> = runCatching {
        val session = requireSession()
        val response = http.post("${config.baseUrl}/rest/v1/rpc/gp_accept_invite") {
            supabaseHeaders(config, session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(AcceptInviteRequest(code.trim().uppercase(), displayName.trim().ifBlank { session.email.substringBefore('@') }))
        }
        if (!response.status.isSuccess()) {
            val message = response.errorMessage()
            throw CloudException(
                if (message.contains("invalid_code", ignoreCase = true)) "That code didn't work. It may have expired." else message,
                response.status.value,
            )
        }
        val result = response.body<AcceptInviteResult>()
        SYNCED_TABLES
            .forEach { store.put(cursorKey(it, result.householdId), null) }
        mutex.withLock { doSync() }
        val firstPet = db.petDao().forHousehold(result.householdId).firstOrNull { it.deletedAt == null && it.archivedAt == null }
        if (firstPet != null) settings.update { it.copy(selectedPetId = firstPet.id) }
        db.householdDao().get(result.householdId)?.toDomain() ?: throw CloudException("Joined, but the household didn't download yet")
    }

    override suspend fun updateMember(member: Caregiver): Result<Unit> = runCatching {
        db.householdDao().upsertMember(member.toEntity(clock.now().toEpochMilliseconds()))
        if (db.householdDao().get(member.householdId)?.cloudEnabled == true) sync().getOrThrow()
    }

    // ------------------------------------------------------------------ PostgREST helpers

    private suspend inline fun <reified T> getRows(
        session: CloudSession,
        table: String,
        params: Map<String, String>,
    ): List<T> {
        val response = http.get("${config.baseUrl}/rest/v1/$table") {
            supabaseHeaders(config, session.accessToken)
            parameter("select", "*")
            params.forEach { (k, v) -> parameter(k, v) }
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
        return response.body()
    }

    private suspend inline fun <reified T> upsertRows(session: CloudSession, table: String, rows: List<T>) {
        if (rows.isEmpty()) return
        rows.chunked(PAGE_SIZE).forEach { chunk ->
            val response = http.post("${config.baseUrl}/rest/v1/$table") {
                supabaseHeaders(config, session.accessToken)
                parameter("on_conflict", "id")
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(chunk)
            }
            if (!response.status.isSuccess()) {
                throw CloudException("Couldn't upload $table: ${response.errorMessage()}", response.status.value)
            }
        }
    }

    private fun inList(ids: Collection<String>): String = "in.(${ids.joinToString(",") { "\"$it\"" }})"

    private fun cursorKey(table: String, householdId: String) = "sync.cursor.$table.$householdId"

    companion object {
        private const val PAGE_SIZE = 500
        private val SYNCED_TABLES = listOf(
            "pets", "medications", "dose_events", "check_ins", "weight_entries", "symptom_entries", "vet_visits",
        )
    }
}

@Serializable
internal data class CreateInviteRequest(
    @SerialName("p_household") val household: String,
    @SerialName("p_role") val role: String,
    @SerialName("p_access_until") val accessUntil: Long?,
)

@Serializable
internal data class AcceptInviteRequest(
    @SerialName("p_code") val code: String,
    @SerialName("p_display_name") val displayName: String,
)
