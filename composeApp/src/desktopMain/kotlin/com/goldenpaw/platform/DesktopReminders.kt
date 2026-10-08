package com.goldenpaw.platform

import com.goldenpaw.core.at
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * Desktop notifications.
 *  - macOS ignores AWT tray balloons, so we post through Notification Center with `osascript`.
 *  - Many Linux desktops (GNOME) have no tray at all; `notify-send` (libnotify) works everywhere.
 *  - Windows uses the tray balloon ([send], wired up in main()).
 */
class DesktopNotifier {
    /** Tray sender, set once the Compose tray exists. */
    @Volatile
    var send: ((title: String, body: String) -> Unit)? = null

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val isMac = os.contains("mac")
    private val isLinux = os.contains("linux") || os.contains("nix") || os.contains("bsd")

    private val notifySend: String? by lazy {
        if (!isLinux) null
        else System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .map { java.io.File(it, "notify-send") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    /** True when a notification can actually be shown on this machine. */
    val available: Boolean get() = isMac || notifySend != null || send != null

    fun notify(title: String, body: String): Boolean {
        return when {
            isMac -> run("osascript", "-e", "display notification ${appleScriptString(body)} with title ${appleScriptString(title)} sound name \"default\"")
            notifySend != null -> run(notifySend!!, "--app-name=GoldenPaw", "--icon=dialog-information", title, body)
            else -> {
                val s = send ?: return false
                s(title, body)
                true
            }
        } || send?.let { it(title, body); true } == true
    }

    private fun run(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command).redirectErrorStream(true).start().also { p ->
            // Don't block the caller; reap the process in the background.
            Thread { runCatching { p.inputStream.readBytes(); p.waitFor() } }.apply { isDaemon = true }.start()
        }
        true
    }.getOrDefault(false)

    /** AppleScript string literal: quotes and backslashes escaped. */
    private fun appleScriptString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Opens the OS notification settings where that's possible. */
    fun openSettings() {
        when {
            isMac -> run("open", "x-apple.systempreferences:com.apple.preference.notifications")
            os.contains("win") -> run("cmd", "/c", "start", "ms-settings:notifications")
            else -> Unit
        }
    }
}

/**
 * Desktop reminders run while the app (or its tray icon) is open: a coroutine sleeps until the
 * next dose or check-in time, posts a tray notification, then re-arms.
 */
class DesktopReminderScheduler(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val pets: PetRepository,
    private val checkIns: CheckInRepository,
    private val settings: SettingsRepository,
    private val notifier: DesktopNotifier,
) : ReminderGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var doseJob: Job? = null
    private var checkInJob: Job? = null

    override suspend fun rescheduleAll() { // <-- Use { instead of =
        mutex.withLock {
            doseJob?.cancel()
            checkInJob?.cancel()
            val s = settings.current()
            if (!s.remindersEnabled) return@withLock

            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()
            val next = ScheduleEngine.nextOccurrence(medications.allActiveForActivePets(), now, zone)

            if (next != null) {
                doseJob = scope.launch {
                    val delayMs: Long = (next - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
                    delay(delayMs)
                    notifyDueAt(next, zone)
                    rescheduleAll()
                }
            }

            if (s.checkInReminderEnabled) {
                val today = now.toLocalDate(zone)
                var target = today.at(s.checkInReminderTime, zone)
                if (target <= now) target = today.plusDays(1).at(s.checkInReminderTime, zone)
                checkInJob = scope.launch {
                    val delayMs: Long = (target - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
                    delay(delayMs)
                    val day = Clock.System.now().toLocalDate(zone)
                    val missing = pets.observeActivePets().first().filter { checkIns.forPetBetween(it.id, day, day).isEmpty() }
                    if (missing.isNotEmpty()) notifier.notify("How was ${missing.first().name}'s day?", "A 30-second check-in keeps the picture clear.")
                    delay(61_000L)
                    rescheduleAll()
                }
            }
        }
    }

    private suspend fun notifyDueAt(target: Instant, zone: TimeZone) {
        val date = target.toLocalDate(zone)
        for (med in medications.allActiveForActivePets()) {
            val match = ScheduleEngine.scheduledInstants(med, date, zone).firstOrNull { it == target } ?: continue
            if (doseEvents.find(med.id, match) != null) continue
            val pet = pets.getPet(med.petId) ?: continue
            notifier.notify("${pet.name} · ${med.name}", "${med.dosageOn(date)} is due now")
        }
    }

    override fun cancelDoseNotification(medicationId: String, scheduledAt: Instant) = Unit

    override suspend fun notifyRefill(medication: Medication) {
        val pet = pets.getPet(medication.petId) ?: return
        notifier.notify("Refill ${medication.name} for ${pet.name}", "About ${medication.daysOfSupplyLeft() ?: 0} days of supply left.")
    }
}
