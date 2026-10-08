package com.goldenpaw.platform

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) {
        object : Haptics {
            override fun confirm() {
                val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                view.performHapticFeedback(effect)
            }
            override fun tick() {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            override fun reject() {
                val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                view.performHapticFeedback(effect)
            }
        }
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) onPicked(null) else scope.launch { onPicked(importPhoto(context, uri)) }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Walks ContextWrappers up to the hosting Activity (needed for permission rationale checks). */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { context.startActivity(intent) }.isSuccess
    if (!ok) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private const val PERMISSION_PREFS = "goldenpaw_permissions"

/**
 * Android only shows the POST_NOTIFICATIONS dialog twice. After the second "Don't allow" the request
 * returns "denied" instantly with no UI, which made the Allow button look broken. We remember that we
 * asked; once the system won't prompt any more (asked before and no rationale is offered), or the
 * app's notifications are switched off in settings, we open the app's notification settings instead.
 */
@Composable
actual fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult(granted && NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    return {
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = !needsRuntimePermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        when {
            granted && enabled -> onResult(true)
            granted -> {
                openAppNotificationSettings(context)
                onResult(false)
            }
            else -> {
                val activity = context.findActivity()
                val askedBefore = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
                val canPrompt = activity != null &&
                    (!askedBefore || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
                if (canPrompt) {
                    prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppNotificationSettings(context)
                    onResult(false)
                }
            }
        }
    }
}

private const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (progress: Float, fromLeftEdge: Boolean) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { e -> onProgress(e.progress, e.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) }
            onBack()
        } catch (e: CancellationException) {
            onCancel()
            throw e
        }
    }
}

@Composable
actual fun rememberPdfPreview(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(0).use { page ->
                            val scale = 1200f / page.width
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(AndroidColor.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun systemPrefersReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
