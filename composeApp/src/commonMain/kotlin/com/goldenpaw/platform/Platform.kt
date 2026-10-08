package com.goldenpaw.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

enum class PlatformKind { ANDROID, IOS, DESKTOP }

/** Status of everything that can delay a medication reminder on this device. */
data class ReminderHealth(
    val notifications: Boolean = true,
    val exactAlarms: Boolean = true,
    val batteryUnrestricted: Boolean = true,
    /** False where the concept doesn't exist (iOS, Desktop): the row is hidden. */
    val exactAlarmsApplicable: Boolean = true,
    val batteryApplicable: Boolean = true,
) {
    val allGood: Boolean get() = notifications && exactAlarms && batteryUnrestricted
}

/**
 * Everything the shared UI needs from the operating system. One implementation per platform,
 * provided through Koin and [LocalPlatform].
 */
interface PlatformServices {
    val kind: PlatformKind
    val supportsDynamicColor: Boolean
    val supportsWidgets: Boolean

    fun share(text: String, subject: String? = null)
    fun shareFile(path: String, mimeType: String, title: String)
    fun openFile(path: String, mimeType: String)
    fun dial(phone: String)

    /** Last known status; cheap enough to call during composition. */
    fun reminderHealth(): ReminderHealth

    /**
     * Re-reads the live status from the OS (call on resume). Never shows a permission prompt.
     * iOS can only read notification settings asynchronously, hence suspend.
     */
    suspend fun loadReminderHealth(): ReminderHealth = reminderHealth()
    fun openNotificationSettings()
    fun openExactAlarmSettings()
    fun openBatterySettings()
    fun sendTestNotification(): Boolean

    /** Asks the launcher to pin the home-screen widget. Returns false if unsupported. */
    fun requestPinWidget(): Boolean

    /** Restarts into onboarding after "Delete all data". */
    fun restartApp()
}

val LocalPlatform = staticCompositionLocalOf<PlatformServices> { error("PlatformServices not provided") }

/** Haptic feedback with semantic names (mapped to the best native effect per platform). */
interface Haptics {
    fun confirm()
    fun tick()
    fun reject()
}

@Composable
expect fun rememberHaptics(): Haptics

/**
 * Opens the system photo picker. The chosen image is downscaled (~1280 px), stored privately and
 * its path passed to [onPicked] (null if cancelled or failed).
 */
@Composable
expect fun rememberPhotoPicker(onPicked: (String?) -> Unit): () -> Unit

/**
 * Asks for notification permission where it exists (Android 13+, iOS) — or, when the system will no
 * longer show the prompt (denied twice on Android, denied once on iOS, or notifications switched
 * off for the app), opens the app's notification settings instead so the button never looks dead.
 * [onResult] receives whether notifications are allowed right now.
 */
@Composable
expect fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit

/**
 * System back, including Android's predictive back gesture. [onProgress] receives 0..1 while the
 * user drags (and whether the swipe started from the left edge); [onCancel] if they let go.
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (progress: Float, fromLeftEdge: Boolean) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
)

/** First page of a generated PDF as an image, where the platform can render one. */
@Composable
expect fun rememberPdfPreview(path: String?): ImageBitmap?

/** Material You colors from the wallpaper (Android 12+), else null. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

/** True when the OS asks for reduced motion (animator scale 0 on Android, Reduce Motion on iOS). */
@Composable
expect fun systemPrefersReducedMotion(): Boolean
