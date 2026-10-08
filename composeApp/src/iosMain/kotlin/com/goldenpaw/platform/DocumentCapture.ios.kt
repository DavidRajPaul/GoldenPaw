package com.goldenpaw.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIImage
import platform.VisionKit.VNDocumentCameraScan
import platform.VisionKit.VNDocumentCameraViewController
import platform.VisionKit.VNDocumentCameraViewControllerDelegateProtocol
import platform.darwin.NSObject

private const val MAX_PAGES = 10L
private const val PAGE_MAX_SIZE = 2200.0

/** UIKit holds delegates weakly: keep the active one alive while its controller is on screen. */
private var activeCaptureDelegate: NSObject? = null

@OptIn(ExperimentalForeignApi::class)
private fun ensureDir(path: String): String {
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(path)) fm.createDirectoryAtPath(path, true, null, null)
    return path
}

private val videoMediaType: String get() = AVMediaTypeVideo ?: "vide"

@Composable
actual fun rememberDocumentCapture(outputDir: String, onResult: (CaptureResult) -> Unit): DocumentCapture {
    val deliver by rememberUpdatedState(onResult)
    // The simulator (and iPads without a camera) report no video device.
    val hasCamera = remember { AVCaptureDevice.defaultDeviceWithMediaType(videoMediaType) != null }

    fun presentScanner() {
        val presenter = topViewController() ?: return deliver(CaptureResult.Failed("Couldn't open the camera."))
        val scanner = VNDocumentCameraViewController()
        val delegate = object : NSObject(), VNDocumentCameraViewControllerDelegateProtocol {
            override fun documentCameraViewController(controller: VNDocumentCameraViewController, didFinishWithScan: VNDocumentCameraScan) {
                val dir = ensureDir(outputDir)
                val count = didFinishWithScan.pageCount.toInt()
                val paths = (0 until count).mapNotNull { i ->
                    saveScaled(didFinishWithScan.imageOfPageAtIndex(i.toULong()), dir, PAGE_MAX_SIZE, quality = 0.88)
                }
                controller.dismissViewControllerAnimated(true, null)
                activeCaptureDelegate = null
                deliver(if (paths.isEmpty()) CaptureResult.Cancelled else CaptureResult.Pages(paths))
            }

            override fun documentCameraViewControllerDidCancel(controller: VNDocumentCameraViewController) {
                controller.dismissViewControllerAnimated(true, null)
                activeCaptureDelegate = null
                deliver(CaptureResult.Cancelled)
            }

            override fun documentCameraViewController(controller: VNDocumentCameraViewController, didFailWithError: NSError) {
                controller.dismissViewControllerAnimated(true, null)
                activeCaptureDelegate = null
                deliver(CaptureResult.Failed(didFailWithError.localizedDescription))
            }
        }
        activeCaptureDelegate = delegate
        scanner.delegate = delegate
        presenter.presentViewController(scanner, animated = true, completion = null)
    }

    fun presentPicker() {
        val presenter = topViewController() ?: return deliver(CaptureResult.Failed("Couldn't open your photos."))
        val configuration = PHPickerConfiguration()
        configuration.selectionLimit = MAX_PAGES
        configuration.filter = PHPickerFilter.imagesFilter
        val picker = PHPickerViewController(configuration = configuration)
        val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, null)
                activeCaptureDelegate = null
                val results = didFinishPicking.filterIsInstance<PHPickerResult>()
                if (results.isEmpty()) {
                    deliver(CaptureResult.Cancelled)
                    return
                }
                val dir = ensureDir(outputDir)
                val slots = arrayOfNulls<String>(results.size)
                var remaining = results.size
                results.forEachIndexed { index, result ->
                    // Loads on a background queue; HEIC and other formats are decoded by UIImage.
                    result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                        val path = data?.let { UIImage(data = it) }?.let { saveScaled(it, dir, PAGE_MAX_SIZE, quality = 0.88) }
                        onMain {
                            slots[index] = path
                            remaining -= 1
                            if (remaining == 0) {
                                val paths = slots.filterNotNull()
                                deliver(if (paths.isEmpty()) CaptureResult.Failed("Couldn't read those photos.") else CaptureResult.Pages(paths))
                            }
                        }
                    }
                }
            }
        }
        activeCaptureDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    return DocumentCapture(
        cameraAvailable = hasCamera,
        autoCrop = true,
        scanWithCamera = {
            when (AVCaptureDevice.authorizationStatusForMediaType(videoMediaType)) {
                AVAuthorizationStatusAuthorized -> presentScanner()
                AVAuthorizationStatusNotDetermined ->
                    AVCaptureDevice.requestAccessForMediaType(videoMediaType) { granted ->
                        onMain { if (granted) presentScanner() else deliver(CaptureResult.PermissionDenied(permanently = true)) }
                    }
                // Denied or restricted: iOS never asks twice, only Settings can turn it back on.
                else -> deliver(CaptureResult.PermissionDenied(permanently = true))
            }
        },
        pickFromGallery = { presentPicker() },
        openAppSettings = { openAppSettings() },
    )
}
