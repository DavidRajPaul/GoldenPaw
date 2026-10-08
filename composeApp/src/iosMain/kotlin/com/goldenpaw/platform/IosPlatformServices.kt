package com.goldenpaw.platform

import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/** Topmost view controller, for presenting share sheets. */
internal fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }
        ?: return null
    var top = window.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

class IosPlatformServices(private val reminders: IosReminderScheduler) : PlatformServices {
    override val kind = PlatformKind.IOS
    override val supportsDynamicColor = false
    override val supportsWidgets = false

    private fun present(items: List<Any>) {
        val vc = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        // iPad: anchor the popover to the presenting view.
        sheet.popoverPresentationController?.sourceView = vc.view
        vc.presentViewController(sheet, animated = true, completion = null)
    }

    override fun share(text: String, subject: String?) = present(listOf(text))

    override fun shareFile(path: String, mimeType: String, title: String) = present(listOf(NSURL.fileURLWithPath(path)))

    override fun openFile(path: String, mimeType: String) = shareFile(path, mimeType, "")

    override fun dial(phone: String) {
        val url = NSURL.URLWithString("tel:${phone.filter { it.isDigit() || it == '+' }}") ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private fun health(allowed: Boolean) = ReminderHealth(
        notifications = allowed,
        exactAlarms = true,
        batteryUnrestricted = true,
        exactAlarmsApplicable = false,
        batteryApplicable = false,
    )

    /** Cached status (refreshed by [loadReminderHealth]); never prompts. */
    override fun reminderHealth(): ReminderHealth = health(reminders.authorized)

    /** Reads the real status with getNotificationSettings; the old check could pop the system dialog. */
    override suspend fun loadReminderHealth(): ReminderHealth = health(reminders.status() == NotificationStatus.ALLOWED)

    override fun openNotificationSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun openExactAlarmSettings() = Unit
    override fun openBatterySettings() = Unit
    override fun sendTestNotification(): Boolean = reminders.sendTest()
    override fun requestPinWidget(): Boolean = false

    /** iOS apps can't relaunch themselves; the UI returns to onboarding once data is cleared. */
    override fun restartApp() = Unit
}
