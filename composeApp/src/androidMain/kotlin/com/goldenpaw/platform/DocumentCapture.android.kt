package com.goldenpaw.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val CAPTURE_PREFS = "goldenpaw_permissions"
private const val KEY_ASKED_CAMERA = "asked_camera"
private const val MAX_PAGES = 10
private const val PAGE_MAX_SIZE = 2200

@Composable
actual fun rememberDocumentCapture(outputDir: String, onResult: (CaptureResult) -> Unit): DocumentCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onResult)
    val prefs = remember(context) { context.getSharedPreferences(CAPTURE_PREFS, Context.MODE_PRIVATE) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) {
            deliver(CaptureResult.Cancelled)
            return
        }
        scope.launch {
            val dir = File(outputDir)
            val paths = uris.mapNotNull { importPhoto(context, it, maxSize = PAGE_MAX_SIZE, dir = dir, quality = 88) }
            deliver(if (paths.isEmpty()) CaptureResult.Failed("Couldn't read those images.") else CaptureResult.Pages(paths))
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            importAll(pages.map { it.imageUri })
        } else {
            deliver(CaptureResult.Cancelled)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        if (saved && uri != null) importAll(listOf(uri)) else deliver(CaptureResult.Cancelled)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PAGES)) { uris ->
        importAll(uris)
    }

    /** Plain system camera: used when Google Play services can't provide the document scanner. */
    fun launchSystemCamera() {
        val result = runCatching {
            val file = File(context.cacheDir, "capture/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri.toString()
            cameraLauncher.launch(uri)
        }
        if (result.isFailure) deliver(CaptureResult.Failed("No camera app is available."))
    }

    fun launchScanner() {
        val activity = context.findActivity()
        if (activity == null) {
            launchSystemCamera()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false) // the gallery has its own button
            .setPageLimit(MAX_PAGES)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { launchSystemCamera() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScanner()
        } else {
            val activity = context.findActivity()
            // After "Don't allow" twice (or "don't ask again"), Android stops showing the dialog.
            val permanently = activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            deliver(CaptureResult.PermissionDenied(permanently))
        }
    }

    return DocumentCapture(
        cameraAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        autoCrop = true,
        scanWithCamera = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val activity = context.findActivity()
            val askedBefore = prefs.getBoolean(KEY_ASKED_CAMERA, false)
            when {
                granted -> launchScanner()
                askedBefore && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) ->
                    deliver(CaptureResult.PermissionDenied(permanently = true))
                else -> {
                    prefs.edit().putBoolean(KEY_ASKED_CAMERA, true).apply()
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        },
        pickFromGallery = {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        openAppSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}
