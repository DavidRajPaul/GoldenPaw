package com.goldenpaw.platform

import androidx.compose.runtime.Composable

/** Outcome of a scan or gallery pick. Page paths are upright, downscaled JPEGs in app-private storage. */
sealed interface CaptureResult {
    data class Pages(val paths: List<String>) : CaptureResult
    data object Cancelled : CaptureResult

    /** Camera access refused. [permanently]: the system won't ask again, so offer the Settings page. */
    data class PermissionDenied(val permanently: Boolean) : CaptureResult
    data class Failed(val message: String) : CaptureResult
}

/**
 * Camera / gallery capture for vet and vaccine cards.
 *
 *  - Android: asks for CAMERA, then opens Google's ML Kit document scanner (edge detection,
 *    perspective correction, multi-page, clean-up). Falls back to the system camera when Google Play
 *    services can't provide the scanner. Gallery uses the Photo Picker (no storage permission).
 *  - iOS: asks for camera access, then VisionKit's document camera (same auto-crop and multi-page).
 *    Gallery uses PHPicker (no photo-library permission: only the chosen photos are shared).
 *  - Desktop: choose image files.
 */
class DocumentCapture(
    /** False when the device has no camera (simulator, most desktops): the camera option is hidden. */
    val cameraAvailable: Boolean,
    /** True when the camera path finds the card's edges and straightens it automatically. */
    val autoCrop: Boolean,
    val scanWithCamera: () -> Unit,
    val pickFromGallery: () -> Unit,
    /** Opens this app's page in system settings (to turn camera access back on). */
    val openAppSettings: () -> Unit,
)

@Composable
expect fun rememberDocumentCapture(outputDir: String, onResult: (CaptureResult) -> Unit): DocumentCapture

/** Rotates and (optionally) "document-enhances" a page. Always works from the original file. */
interface PageImageProcessor {
    /** Writes a new JPEG into [outputDir] and returns its path, or null if the image couldn't be read. */
    suspend fun process(sourcePath: String, outputDir: String, quarterTurns: Int, enhance: Boolean): String?
}
