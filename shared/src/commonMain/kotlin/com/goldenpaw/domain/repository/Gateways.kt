package com.goldenpaw.domain.repository

import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.CloudSession
import com.goldenpaw.domain.model.Household
import com.goldenpaw.domain.model.Invite
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.SyncState
import com.goldenpaw.domain.model.WeeklyDigest
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

/** Platform reminder hooks (AlarmManager on Android, UNUserNotificationCenter on iOS, tray on Desktop). */
interface ReminderGateway {
    suspend fun rescheduleAll()
    fun cancelDoseNotification(medicationId: String, scheduledAt: Instant)
    suspend fun notifyRefill(medication: Medication)
}

/** Files in the app's private storage (photos, exports, reports). */
interface AppFiles {
    val dataDir: String
    val cacheDir: String
    val photosDir: String
    fun exists(path: String?): Boolean
    fun delete(path: String?): Boolean
    fun deleteRecursively(dir: String)
    fun writeBytes(path: String, bytes: ByteArray)
    fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())
}

/** Email one-time-code sign in (Supabase Auth). Only used for sharing and cloud backup. */
interface CloudAuthRepository {
    /** False when the build has no Supabase keys: the app is fully local. */
    val isAvailable: Boolean
    val session: StateFlow<CloudSession?>
    suspend fun requestCode(email: String): Result<Unit>
    suspend fun verifyCode(email: String, code: String): Result<CloudSession>
    /** Current session, refreshed if it's about to expire; null when signed out. */
    suspend fun validSession(): CloudSession?
    suspend fun signOut()
}

/** Shared care: uploads a household, invites people and keeps every device in sync. */
interface CareSyncRepository {
    val isAvailable: Boolean
    val state: StateFlow<SyncState>
    suspend fun sync(): Result<Instant>
    suspend fun enableSharing(householdId: String): Result<Household>
    suspend fun createInvite(householdId: String, role: CaregiverRole, accessUntil: Instant?): Result<Invite>
    suspend fun acceptInvite(code: String, displayName: String): Result<Household>
    suspend fun updateMember(member: Caregiver): Result<Unit>
}

/** Writes the weekly summary. The AI implementation calls a Supabase Edge Function (Claude). */
interface SummaryGenerator {
    val isAvailable: Boolean
    suspend fun generate(digest: WeeklyDigest, unit: WeightUnit): Result<WeeklySummary>
}
