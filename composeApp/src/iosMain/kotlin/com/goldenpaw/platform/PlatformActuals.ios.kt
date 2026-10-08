package com.goldenpaw.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.goldenpaw.domain.repository.AppFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.koin.compose.koinInject
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UISelectionFeedbackGenerator
import platform.darwin.NSObject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@Composable
actual fun rememberHaptics(): Haptics = remember {
    object : Haptics {
        override fun confirm() = UIImpactFeedbackGenerator().impactOccurred()
        override fun tick() = UISelectionFeedbackGenerator().selectionChanged()
        override fun reject() = UIImpactFeedbackGenerator().impactOccurred()
    }
}

/**
 * Redraws [image] upright (UIKit applies imageOrientation while drawing), downscaled to [maxSize],
 * and writes it as a JPEG into [dir]. Safe off the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun saveScaled(image: UIImage, dir: String, maxSize: Double = 1280.0, quality: Double = 0.85): String? {
    val (w, h) = image.size.useContents { width to height }
    val scale = minOf(1.0, maxSize / maxOf(w, h))
    val target = CGSizeMake(w * scale, h * scale)
    UIGraphicsBeginImageContextWithOptions(target, true, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, w * scale, h * scale))
    val scaled = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    val data = UIImageJPEGRepresentation(scaled ?: image, quality) ?: return null
    val path = "$dir/${NSUUID().UUIDString}.jpg"
    return if (data.writeToFile(path, atomically = true)) path else null
}

/** Keeps the delegate alive while the picker is on screen (UIKit holds it weakly). */
private var activePickerDelegate: NSObject? = null

@Composable
actual fun rememberPhotoPicker(onPicked: (String?) -> Unit): () -> Unit {
    val files: AppFiles = koinInject()
    return {
        val vc = topViewController()
        if (vc == null) {
            onPicked(null)
        } else {
            val picker = UIImagePickerController()
            // Default source type is the photo library.
            val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
                override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                    val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                    picker.dismissViewControllerAnimated(true, null)
                    onPicked(image?.let { saveScaled(it, files.photosDir) })
                    activePickerDelegate = null
                }

                override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                    picker.dismissViewControllerAnimated(true, null)
                    onPicked(null)
                    activePickerDelegate = null
                }
            }
            activePickerDelegate = delegate
            picker.delegate = delegate
            vc.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/**
 * Prompts only while the status is "not determined" (iOS shows the dialog once, ever). Once denied,
 * the button opens the app's page in Settings instead of silently doing nothing.
 */
@Composable
actual fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val reminders: IosReminderScheduler = koinInject()
    return {
        reminders.readStatus { status ->
            when (status) {
                NotificationStatus.ALLOWED -> onResult(true)
                NotificationStatus.NOT_DETERMINED -> reminders.requestAuthorization(onResult)
                NotificationStatus.DENIED -> {
                    openAppSettings()
                    onResult(false)
                }
            }
        }
    }
}

internal fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (progress: Float, fromLeftEdge: Boolean) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    // iOS navigation uses the top-bar back buttons.
}

/** The report is shared via the system sheet, which shows its own preview on iOS. */
@Composable
actual fun rememberPdfPreview(path: String?): ImageBitmap? = null

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun systemPrefersReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
