package com.goldenpaw.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

class DesktopPlatformServices(private val notifier: DesktopNotifier) : PlatformServices {
    override val kind = PlatformKind.DESKTOP
    override val supportsDynamicColor = false
    override val supportsWidgets = false

    private val desktop: Desktop? get() = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null

    /** Desktop has no share sheet: copy to the clipboard (or open a mail draft for long text). */
    override fun share(text: String, subject: String?) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        notifier.notify("Copied to clipboard", subject ?: "Paste it wherever you like.")
    }

    override fun shareFile(path: String, mimeType: String, title: String) {
        // Reveal the file so it can be attached or dragged into an email.
        val file = File(path)
        runCatching {
            val d = desktop
            if (d != null && d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) d.browseFileDirectory(file) else d?.open(file.parentFile)
        }
    }

    override fun openFile(path: String, mimeType: String) {
        runCatching { desktop?.open(File(path)) }
    }

    override fun dial(phone: String) {
        runCatching { desktop?.browse(URI("tel:${phone.filter { it.isDigit() || it == '+' }}")) }
    }

    override fun reminderHealth() = ReminderHealth(
        notifications = notifier.available,
        exactAlarms = true,
        batteryUnrestricted = true,
        exactAlarmsApplicable = false,
        batteryApplicable = false,
    )

    override fun openNotificationSettings() = notifier.openSettings()
    override fun openExactAlarmSettings() = Unit
    override fun openBatterySettings() = Unit

    override fun sendTestNotification(): Boolean = notifier.notify("Reminders are working", "This is how medication reminders will look.")

    override fun requestPinWidget(): Boolean = false

    override fun restartApp() {
        // The Room file is recreated on next launch; ask the user to reopen.
        kotlin.system.exitProcess(0)
    }
}
