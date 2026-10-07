package com.goldenpaw.data.export

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.today
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.CloudAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GDPR-style data export (JSON) and full local data deletion. */
class DataManager(
    private val db: GoldenPawDatabase,
    private val files: AppFiles,
    private val auth: CloudAuthRepository,
    private val clock: AppClock,
) {
    private val pretty = Json { prettyPrint = true }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    /** Writes everything to a JSON file in the cache directory and returns its path. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val root = buildJsonObject {
            put("app", "GoldenPaw")
            put("exportedAt", clock.now().toEpochMilliseconds())
            put("schemaVersion", 2)
            put("households", JsonArray(db.householdDao().all().map {
                obj("id" to it.id, "name" to it.name, "cloudEnabled" to it.cloudEnabled, "createdAt" to it.createdAt)
            }))
            put("caregivers", JsonArray(db.householdDao().allMembers().map {
                obj(
                    "id" to it.id, "householdId" to it.householdId, "displayName" to it.displayName,
                    "role" to it.role, "status" to it.status, "accessUntil" to it.accessUntil,
                )
            }))
            put("pets", JsonArray(db.petDao().all().map { p ->
                obj(
                    "id" to p.id, "householdId" to p.householdId, "name" to p.name, "species" to p.species,
                    "breed" to p.breed, "sex" to p.sex, "birthEpochDay" to p.birthEpochDay, "conditions" to p.conditions,
                    "vetName" to p.vetName, "vetPhone" to p.vetPhone, "notes" to p.notes, "createdAt" to p.createdAt,
                    "archivedAt" to p.archivedAt,
                )
            }))
            put("medications", JsonArray(db.medicationDao().all().map { m ->
                obj(
                    "id" to m.id, "petId" to m.petId, "name" to m.name, "dosage" to m.dosage, "unit" to m.unit,
                    "route" to m.route, "reason" to m.reason, "prescribedBy" to m.prescribedBy, "withFood" to m.withFood,
                    "scheduleType" to m.scheduleType, "times" to m.times, "intervalDays" to m.intervalDays,
                    "weekdays" to m.weekdays, "taperSteps" to m.taperSteps, "startEpochDay" to m.startEpochDay,
                    "endEpochDay" to m.endEpochDay, "supplyRemaining" to m.supplyRemaining, "isActive" to m.isActive,
                    "notes" to m.notes,
                )
            }))
            put("doseEvents", JsonArray(db.doseEventDao().all().map { e ->
                obj(
                    "id" to e.id, "medicationId" to e.medicationId, "petId" to e.petId, "scheduledAt" to e.scheduledAt,
                    "actualAt" to e.actualAt, "status" to e.status, "dosageGiven" to e.dosageGiven,
                    "givenBy" to e.givenBy, "givenById" to e.givenById, "notes" to e.notes,
                )
            }))
            put("checkIns", JsonArray(db.checkInDao().all().map { c ->
                obj(
                    "id" to c.id, "petId" to c.petId, "epochDay" to c.epochDay, "appetite" to c.appetite,
                    "water" to c.water, "mobility" to c.mobility, "mood" to c.mood, "pain" to c.pain,
                    "hygiene" to c.hygiene, "sleep" to c.sleep, "notes" to c.notes, "loggedBy" to c.loggedBy,
                )
            }))
            put("weights", JsonArray(db.weightDao().all().map { w ->
                obj(
                    "id" to w.id, "petId" to w.petId, "epochDay" to w.epochDay, "weightKg" to w.weightKg,
                    "notes" to w.notes, "loggedBy" to w.loggedBy,
                )
            }))
            put("symptoms", JsonArray(db.symptomDao().all().map { s ->
                obj(
                    "id" to s.id, "petId" to s.petId, "epochDay" to s.epochDay, "loggedAt" to s.loggedAt,
                    "type" to s.type, "severity" to s.severity, "tags" to s.tags, "notes" to s.notes,
                    "loggedBy" to s.loggedBy,
                )
            }))
        }
        val dir = "${files.cacheDir}/exports"
        files.deleteRecursively(dir)
        val path = "$dir/goldenpaw-export-${clock.today()}.json"
        files.writeText(path, pretty.encodeToString(JsonObject.serializer(), root))
        path
    }

    /** Deletes every local record, photo and preference ("delete my data" for this device). */
    suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        runCatching { auth.signOut() }
        val m = db.maintenanceDao()
        m.clearDoseEvents()
        m.clearSummaries()
        m.clearVetVisits()
        m.clearCheckIns()
        m.clearWeights()
        m.clearSymptoms()
        m.clearMedications()
        m.clearPets()
        m.clearCaregivers()
        m.clearHouseholds()
        m.clearAchievements()
        m.clearSettings()
        files.deleteRecursively(files.photosDir)
        files.deleteRecursively("${files.cacheDir}/reports")
        files.deleteRecursively("${files.cacheDir}/exports")
    }
}
