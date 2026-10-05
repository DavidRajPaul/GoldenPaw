package com.goldenpaw.data.export

import android.content.Context
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.platform.media.ImageStorage
import com.goldenpaw.platform.reminders.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** GDPR-style data export (JSON) and full local data deletion. */
class DataManager(
    private val context: Context,
    private val db: GoldenPawDatabase,
    private val settings: SettingsRepository,
    private val images: ImageStorage,
    private val notifications: NotificationHelper,
) {

    suspend fun exportJson(): File = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", "GoldenPaw")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("schemaVersion", 1)

        root.put("pets", JSONArray().apply {
            db.petDao().all().forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("species", p.species); put("breed", p.breed)
                    put("sex", p.sex); put("birthEpochDay", p.birthEpochDay ?: JSONObject.NULL)
                    put("conditions", p.conditions); put("vetName", p.vetName); put("vetPhone", p.vetPhone)
                    put("notes", p.notes); put("createdAt", p.createdAt); put("archivedAt", p.archivedAt ?: JSONObject.NULL)
                })
            }
        })
        root.put("medications", JSONArray().apply {
            db.medicationDao().all().forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id); put("petId", m.petId); put("name", m.name); put("dosage", m.dosage)
                    put("unit", m.unit); put("route", m.route); put("reason", m.reason)
                    put("prescribedBy", m.prescribedBy); put("withFood", m.withFood)
                    put("scheduleType", m.scheduleType); put("times", m.times); put("intervalDays", m.intervalDays)
                    put("weekdays", m.weekdays); put("taperSteps", m.taperSteps)
                    put("startEpochDay", m.startEpochDay); put("endEpochDay", m.endEpochDay ?: JSONObject.NULL)
                    put("supplyRemaining", m.supplyRemaining ?: JSONObject.NULL); put("isActive", m.isActive)
                    put("notes", m.notes)
                })
            }
        })
        root.put("doseEvents", JSONArray().apply {
            db.doseEventDao().all().forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("medicationId", e.medicationId); put("petId", e.petId)
                    put("scheduledAt", e.scheduledAt); put("actualAt", e.actualAt); put("status", e.status)
                    put("dosageGiven", e.dosageGiven); put("givenBy", e.givenBy); put("notes", e.notes)
                })
            }
        })
        root.put("checkIns", JSONArray().apply {
            db.checkInDao().all().forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("petId", c.petId); put("epochDay", c.epochDay)
                    put("appetite", c.appetite); put("water", c.water); put("mobility", c.mobility)
                    put("mood", c.mood); put("pain", c.pain); put("hygiene", c.hygiene); put("sleep", c.sleep)
                    put("notes", c.notes)
                })
            }
        })
        root.put("weights", JSONArray().apply {
            db.weightDao().all().forEach { w ->
                put(JSONObject().apply {
                    put("id", w.id); put("petId", w.petId); put("epochDay", w.epochDay)
                    put("weightKg", w.weightKg); put("notes", w.notes)
                })
            }
        })
        root.put("symptoms", JSONArray().apply {
            db.symptomDao().all().forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("petId", s.petId); put("epochDay", s.epochDay)
                    put("loggedAt", s.loggedAt); put("type", s.type); put("severity", s.severity)
                    put("tags", s.tags); put("notes", s.notes)
                })
            }
        })

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "goldenpaw-export-${LocalDate.now()}.json")
        file.writeText(root.toString(2))
        file
    }

    /** Deletes every local record, photo and preference ("delete my account" for local-only mode). */
    suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        images.deleteAll()
        File(context.cacheDir, "reports").deleteRecursively()
        File(context.cacheDir, "exports").deleteRecursively()
        notifications.cancelAll()
        settings.clear()
    }
}
