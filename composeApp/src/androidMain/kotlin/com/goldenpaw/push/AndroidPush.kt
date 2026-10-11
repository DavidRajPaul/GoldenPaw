package com.goldenpaw.push

import android.content.Context
import com.goldenpaw.config.AppConfig
import com.goldenpaw.data.remote.PushRegistrationService
import com.goldenpaw.domain.model.PushMessage
import com.goldenpaw.domain.model.isEnabled
import com.goldenpaw.domain.repository.PushPlatform
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.reminders.NotificationHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * Firebase is set up in code from the generated AppConfig (no google-services.json or Gradle
 * plugin), and only when the build has Firebase keys. Without them the app has no push and nothing
 * else changes.
 */
object FirebaseSetup {
    fun ensureInitialized(context: Context): Boolean {
        if (!AppConfig.isFirebaseConfigured) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return runCatching {
            val options = FirebaseOptions.Builder()
                .setProjectId(AppConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(AppConfig.FIREBASE_ANDROID_APP_ID)
                .setApiKey(AppConfig.FIREBASE_API_KEY)
                .setGcmSenderId(AppConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
            true
        }.getOrDefault(false)
    }
}

class AndroidPushPlatform(
    private val context: Context,
    private val registration: PushRegistrationService,
) : PushPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val platform: String = "android"
    override val isSupported: Boolean get() = AppConfig.isFirebaseConfigured
    override val locale: String get() = Locale.getDefault().toLanguageTag()

    override fun requestToken() {
        if (!FirebaseSetup.ensureInitialized(context)) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) scope.launch { registration.onNewToken(platform, token, locale) }
            }
        }
    }
}

/**
 * Receives FCM messages. The backend sends data-only messages, so the app always draws the
 * notification itself: it respects the owner's per-category choices, uses the right channel and
 * routes taps through the same deep links as reminders.
 */
class GoldenPawMessagingService : FirebaseMessagingService() {
    private val registration: PushRegistrationService by inject()
    private val settings: SettingsRepository by inject()
    private val notifications: NotificationHelper by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { registration.onNewToken("android", token, Locale.getDefault().toLanguageTag()) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = HashMap<String, String?>(message.data)
        // Tolerate "notification" messages too (e.g. sent by hand from the Firebase console).
        message.notification?.let { n ->
            if (data["title"].isNullOrBlank()) data["title"] = n.title
            if (data["body"].isNullOrBlank()) data["body"] = n.body
            if (data["category"].isNullOrBlank()) data["category"] = "product"
        }
        val push = PushMessage.parse(data) ?: return
        // onMessageReceived runs on a background thread and must finish quickly; a settings read is fine.
        val s = runBlocking { settings.current() }
        if (!push.category.isEnabled(s.offerNotifications, s.productNotifications, s.accountNotifications)) return
        notifications.showPush(push)
    }
}
