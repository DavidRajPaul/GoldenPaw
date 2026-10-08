package com.goldenpaw.platform

import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.LogDoseUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val DOSE_PREFIX = "dose:"
private const val CHECKIN_ID = "checkin"
private const val CATEGORY_DOSE = "DOSE"
private const val ACTION_GIVEN = "GIVEN"
private const val ACTION_SKIP = "SKIP"

/**
 * iOS can't wake the app at an exact time, so reminders are pre-scheduled as local notifications:
 * the next 48 doses (iOS caps pending requests at 64) plus a repeating daily check-in. Every
 * change re-plans the whole batch; it's cheap and keeps edits trivially correct.
 */
class IosReminderScheduler(
    private val medications: MedicationRepository,
    private val pets: PetRepository,
    private val settings: SettingsRepository,
) : ReminderGateway {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    private val mutex = Mutex()

    /** Last status read from the system. Starts false until [readStatus] has run once. */
    @kotlin.concurrent.Volatile
    var authorized: Boolean = false
        private set

    fun registerCategories() {
        val given = UNNotificationAction.actionWithIdentifier(ACTION_GIVEN, "Given", UNNotificationActionOptionNone)
        val skip = UNNotificationAction.actionWithIdentifier(ACTION_SKIP, "Skip", UNNotificationActionOptionNone)
        val category = UNNotificationCategory.categoryWithIdentifier(
            CATEGORY_DOSE, listOf(given, skip), emptyList<Any>(), UNNotificationCategoryOptionNone,
        )
        center.setNotificationCategories(setOf(category))
    }

    /**
     * Shows the system prompt. Only call when [readStatus] says NOT_DETERMINED: after the user has
     * decided, iOS never shows it again. The callback is delivered on the main queue (UNUserNotificationCenter
     * calls back on a background thread, and Compose state written there could be dropped).
     */
    fun requestAuthorization(onResult: (Boolean) -> Unit) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { granted, _ ->
            authorized = granted
            onMain { onResult(granted) }
        }
    }

    /** Reads the current permission without ever prompting. Callback on the main queue. */
    fun readStatus(onResult: (NotificationStatus) -> Unit) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = when (settings?.authorizationStatus) {
                UNAuthorizationStatusNotDetermined -> NotificationStatus.NOT_DETERMINED
                UNAuthorizationStatusDenied -> NotificationStatus.DENIED
                null -> NotificationStatus.NOT_DETERMINED
                else -> NotificationStatus.ALLOWED // authorized, provisional, ephemeral
            }
            authorized = status == NotificationStatus.ALLOWED
            onMain { onResult(status) }
        }
    }

    suspend fun status(): NotificationStatus = suspendCoroutine { cont -> readStatus { cont.resume(it) } }

    override suspend fun rescheduleAll() = mutex.withLock {
        center.removeAllPendingNotificationRequests()
        val s = settings.current()
        if (!s.remindersEnabled) return@withLock
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val petNames = mutableMapOf<String, String>()
        ScheduleEngine.upcomingOccurrences(medications.allActiveForActivePets(), now, zone, limit = 48).forEach { (med, at) ->
            val petName = petNames.getOrPut(med.petId) { pets.getPet(med.petId)?.name.orEmpty() }
            val content = UNMutableNotificationContent().apply {
                setTitle("$petName · ${med.name}")
                setBody("${med.dosageOn(at.toLocalDate(zone))}${if (med.withFood) " · with food" else ""} is due now")
                setSound(UNNotificationSound.defaultSound)
                setCategoryIdentifier(CATEGORY_DOSE)
                setUserInfo(mapOf<Any?, Any?>("medId" to med.id, "at" to at.toEpochMilliseconds().toString(), "petId" to med.petId))
            }
            val seconds = (at - now).inWholeSeconds.toDouble().coerceAtLeast(1.0)
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier("$DOSE_PREFIX${med.id}@${at.toEpochMilliseconds()}", content, trigger),
                withCompletionHandler = null,
            )
        }
        if (s.checkInReminderEnabled) {
            val comps = NSDateComponents().apply {
                hour = (s.checkInReminderTime.hour.toLong())
                minute = (s.checkInReminderTime.minute.toLong())
            }
            val content = UNMutableNotificationContent().apply {
                setTitle("How was today?")
                setBody("A 30-second check-in keeps the picture clear for you and your vet.")
                setUserInfo(mapOf<Any?, Any?>("open" to "checkin"))
            }
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier(CHECKIN_ID, content, UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(comps, repeats = true)),
                withCompletionHandler = null,
            )
        }
    }

    override fun cancelDoseNotification(medicationId: String, scheduledAt: Instant) {
        val id = "$DOSE_PREFIX$medicationId@${scheduledAt.toEpochMilliseconds()}"
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }

    override suspend fun notifyRefill(medication: Medication) {
        val pet = pets.getPet(medication.petId) ?: return
        val content = UNMutableNotificationContent().apply {
            setTitle("Refill ${medication.name} for ${pet.name}")
            setBody("About ${medication.daysOfSupplyLeft() ?: 0} days of supply left.")
        }
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier("refill:${medication.id}", content, UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, false)),
            withCompletionHandler = null,
        )
    }

    fun sendTest(): Boolean {
        val content = UNMutableNotificationContent().apply {
            setTitle("Reminders are working")
            setBody("This is how medication reminders will look.")
        }
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier("test", content, UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(2.0, false)),
            withCompletionHandler = null,
        )
        return authorized
    }
}

enum class NotificationStatus { NOT_DETERMINED, DENIED, ALLOWED }

internal fun onMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}

/**
 * Handles the Given/Skip buttons on dose notifications (through the same use case as the app, so
 * the double-dose guard applies) and shows banners while the app is open.
 */
class IosNotificationDelegate(
    private val logDose: LogDoseUseCase,
    private val doseEvents: DoseEventRepository,
    private val onOpen: (open: String, petId: String?) -> Unit,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        val info = willPresentNotification.request.content.userInfo
        val medId = info["medId"] as? String
        val at = (info["at"] as? String)?.toLongOrNull()
        if (medId == null || at == null) {
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound)
            return
        }
        // Don't show a reminder for a dose someone on the team already logged.
        scope.launch {
            val logged = doseEvents.find(medId, Instant.fromEpochMilliseconds(at)) != null
            withCompletionHandler(if (logged) 0u else UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound)
        }
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val info = didReceiveNotificationResponse.notification.request.content.userInfo
        val medId = info["medId"] as? String
        val at = (info["at"] as? String)?.toLongOrNull()
        val petId = info["petId"] as? String
        when (didReceiveNotificationResponse.actionIdentifier) {
            ACTION_GIVEN, ACTION_SKIP -> if (medId != null && at != null) {
                val status = if (didReceiveNotificationResponse.actionIdentifier == ACTION_GIVEN) DoseStatus.GIVEN else DoseStatus.SKIPPED
                scope.launch {
                    runCatching { logDose(medId, Instant.fromEpochMilliseconds(at), status, notes = "Logged from notification") }
                    withCompletionHandler()
                }
                return
            }
            else -> onOpen(info["open"] as? String ?: "today", petId)
        }
        withCompletionHandler()
    }
}
