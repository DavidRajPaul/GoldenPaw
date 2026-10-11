package com.goldenpaw.platform

import com.goldenpaw.data.remote.PushRegistrationService
import com.goldenpaw.domain.repository.PushPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIApplication
import platform.UIKit.registerForRemoteNotifications

/**
 * iOS push goes straight through APNs (no Firebase SDK). Asking for a token doesn't prompt the user:
 * the alert permission is the same one reminders already ask for. The token arrives in Swift's
 * AppDelegate, which hands it to [PushBridge].
 */
object IosPushPlatform : PushPlatform {
    override val platform: String = "ios"
    override val isSupported: Boolean = true
    override val locale: String get() = NSLocale.currentLocale.localeIdentifier.replace('_', '-')

    override fun requestToken() {
        onMain { UIApplication.sharedApplication.registerForRemoteNotifications() }
    }
}

/** Entry points for Swift (`PushBridge.shared.onApnsToken(token:)`). */
object PushBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [token] is the APNs device token as lowercase hex. */
    fun onApnsToken(token: String) {
        val registration = KoinPlatform.getKoinOrNull()?.get<PushRegistrationService>() ?: return
        scope.launch { registration.onNewToken(IosPushPlatform.platform, token, IosPushPlatform.locale) }
    }

    fun onRegistrationFailed(message: String) {
        // Expected on the simulator and without the push entitlement; the app works without push.
        println("GoldenPaw: APNs registration failed: $message")
    }
}
