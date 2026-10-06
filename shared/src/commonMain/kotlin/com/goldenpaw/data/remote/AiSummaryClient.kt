package com.goldenpaw.data.remote

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.newId
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.SummarySource
import com.goldenpaw.domain.model.WeeklyDigest
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.SummaryGenerator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Request sent to the `weekly-summary` Supabase Edge Function. Facts only; no photos or contact details. */
@Serializable
internal data class SummaryRequest(
    @SerialName("pet_name") val petName: String,
    val species: String,
    @SerialName("age_years") val ageYears: Int?,
    val conditions: List<String>,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    val medications: List<String>,
    @SerialName("doses_given") val dosesGiven: Int,
    @SerialName("doses_skipped") val dosesSkipped: Int,
    @SerialName("doses_missed") val dosesMissed: Int,
    @SerialName("adherence_pct") val adherencePct: Int?,
    @SerialName("previous_adherence_pct") val previousAdherencePct: Int?,
    @SerialName("check_ins") val checkIns: Int,
    @SerialName("qol_average") val qolAverage: Int?,
    @SerialName("previous_qol_average") val previousQolAverage: Int?,
    @SerialName("good_days") val goodDays: Int,
    @SerialName("okay_days") val okayDays: Int,
    @SerialName("hard_days") val hardDays: Int,
    @SerialName("weakest_area") val weakestArea: String?,
    @SerialName("strongest_area") val strongestArea: String?,
    val symptoms: Map<String, Int>,
    @SerialName("symptom_days") val symptomDays: Map<String, Int>,
    val patterns: List<String>,
    @SerialName("weight_start") val weightStart: String?,
    @SerialName("weight_end") val weightEnd: String?,
    @SerialName("weight_change_pct") val weightChangePct: Double?,
    val notes: List<String>,
    val caregivers: Map<String, Int>,
)

@Serializable
internal data class SummaryResponse(
    val headline: String,
    val summary: String,
    val highlights: List<String> = emptyList(),
    @SerialName("watch_items") val watchItems: List<String> = emptyList(),
    @SerialName("vet_questions") val vetQuestions: List<String> = emptyList(),
)

/**
 * Claude-written weekly summaries via a Supabase Edge Function (supabase/functions/weekly-summary).
 * The Anthropic API key lives only on the server; the app sends the structured digest above.
 */
class AiSummaryClient(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val auth: SupabaseAuthRepository,
    private val clock: AppClock,
) : SummaryGenerator {

    override val isAvailable: Boolean get() = config.isConfigured

    override suspend fun generate(digest: WeeklyDigest, unit: WeightUnit): Result<WeeklySummary> = runCatching {
        val token = auth.validSession()?.accessToken
        val response = http.post("${config.baseUrl}/functions/v1/weekly-summary") {
            supabaseHeaders(config, token)
            contentType(ContentType.Application.Json)
            setBody(digest.toRequest(unit))
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
        val body = response.body<SummaryResponse>()
        require(body.headline.isNotBlank() && body.summary.isNotBlank()) { "Empty summary" }
        WeeklySummary(
            id = newId(),
            petId = digest.petId,
            weekStart = digest.weekStart,
            headline = body.headline.trim().take(120),
            summary = body.summary.trim().take(1200),
            highlights = body.highlights.map { it.trim() }.filter { it.isNotEmpty() }.take(4),
            watchItems = body.watchItems.map { it.trim() }.filter { it.isNotEmpty() }.take(4),
            vetQuestions = body.vetQuestions.map { it.trim() }.filter { it.isNotEmpty() }.take(3),
            source = SummarySource.AI,
            createdAt = clock.now(),
        )
    }

    private fun WeeklyDigest.toRequest(unit: WeightUnit) = SummaryRequest(
        petName = petName,
        species = species.label,
        ageYears = ageYears,
        conditions = conditions,
        weekStart = weekStart.toString(),
        weekEnd = weekEnd.toString(),
        medications = medicationNames,
        dosesGiven = dosesGiven,
        dosesSkipped = dosesSkipped,
        dosesMissed = dosesMissed,
        adherencePct = adherence?.let { (it * 100).roundToInt() },
        previousAdherencePct = previousAdherence?.let { (it * 100).roundToInt() },
        checkIns = checkIns,
        qolAverage = qolAverage?.roundToInt(),
        previousQolAverage = previousQolAverage?.roundToInt(),
        goodDays = goodDays,
        okayDays = okayDays,
        hardDays = hardDays,
        weakestArea = weakestArea,
        strongestArea = strongestArea,
        symptoms = symptomCounts.mapKeys { it.key.label },
        symptomDays = symptomDays.mapKeys { it.key.label },
        patterns = patterns.map { it.message },
        weightStart = weightStartKg?.let { UnitConversion.format(it, unit) },
        weightEnd = weightEndKg?.let { UnitConversion.format(it, unit) },
        weightChangePct = weightChangePercent?.let { (it * 10).roundToInt() / 10.0 },
        notes = notes,
        caregivers = caregiverContributions,
    )
}
