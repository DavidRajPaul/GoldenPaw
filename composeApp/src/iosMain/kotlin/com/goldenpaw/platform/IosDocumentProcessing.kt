package com.goldenpaw.platform

import com.goldenpaw.domain.logic.TextBox
import com.goldenpaw.domain.logic.TextLineAssembler
import com.goldenpaw.domain.repository.DocumentTextReader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGContextRotateCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.CoreImage.kCIInputBrightnessKey
import platform.CoreImage.kCIInputContrastKey
import platform.CoreImage.kCIInputImageKey
import platform.CoreImage.kCIInputSaturationKey
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setValue
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import kotlin.math.PI

/** Rotation with Core Graphics, "document" enhancement with Core Image (grey, contrast, brighter paper). */
@OptIn(ExperimentalForeignApi::class)
class IosPageImageProcessor : PageImageProcessor {
    override suspend fun process(sourcePath: String, outputDir: String, quarterTurns: Int, enhance: Boolean): String? =
        withContext(Dispatchers.IO) {
            val source = UIImage.imageWithContentsOfFile(sourcePath) ?: return@withContext null
            val rotated = rotate(source, ((quarterTurns % 4) + 4) % 4) ?: source
            val output = if (enhance) enhanceDocument(rotated) ?: rotated else rotated
            val fm = NSFileManager.defaultManager
            if (!fm.fileExistsAtPath(outputDir)) fm.createDirectoryAtPath(outputDir, true, null, null)
            saveScaled(output, outputDir, maxSize = 2200.0, quality = 0.88)
        }

    private fun rotate(image: UIImage, turns: Int): UIImage? {
        if (turns == 0) return image
        val (w, h) = image.size.useContents { width to height }
        val swap = turns % 2 == 1
        val outW = if (swap) h else w
        val outH = if (swap) w else h
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(outW, outH), true, 1.0)
        val context = UIGraphicsGetCurrentContext()
        CGContextTranslateCTM(context, outW / 2, outH / 2)
        CGContextRotateCTM(context, turns * PI / 2) // UIKit's y axis points down, so positive is clockwise
        image.drawInRect(CGRectMake(-w / 2, -h / 2, w, h))
        val result = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return result
    }

    private fun enhanceDocument(image: UIImage): UIImage? {
        val cg = image.CGImage ?: return null
        val input = CIImage.imageWithCGImage(cg)
        val filter = CIFilter.filterWithName("CIColorControls") ?: return null
        filter.setValue(input, forKey = kCIInputImageKey)
        filter.setValue(0.0, forKey = kCIInputSaturationKey)
        filter.setValue(1.35, forKey = kCIInputContrastKey)
        filter.setValue(0.07, forKey = kCIInputBrightnessKey)
        val output = filter.outputImage ?: return null
        val rendered = CIContext.contextWithOptions(null).createCGImage(output, fromRect = output.extent) ?: return null
        val result = UIImage.imageWithCGImage(rendered)
        CGImageRelease(rendered)
        return result
    }
}

/** On-device text recognition with Apple's Vision framework. Nothing leaves the phone. */
@OptIn(ExperimentalForeignApi::class)
class VisionTextReader : DocumentTextReader {
    override val isAvailable: Boolean = true

    override suspend fun read(imagePath: String): String = withContext(Dispatchers.IO) {
        val data: NSData = NSData.dataWithContentsOfFile(imagePath) ?: return@withContext ""
        val request = VNRecognizeTextRequest()
        val handler = VNImageRequestHandler(data = data, options = emptyMap<Any?, Any?>())
        val ok = runCatching { handler.performRequests(listOf(request), error = null) }.getOrDefault(false)
        if (!ok) return@withContext ""
        val boxes = request.results.orEmpty().filterIsInstance<VNRecognizedTextObservation>().mapNotNull { observation ->
            val text = (observation.topCandidates(1u).firstOrNull() as? VNRecognizedText)?.string ?: return@mapNotNull null
            // Vision boxes are normalised with the origin at the bottom-left: flip y so it grows downward.
            observation.boundingBox.useContents {
                TextBox(text, origin.x.toFloat(), (1.0 - origin.y - size.height).toFloat(), (1.0 - origin.y).toFloat())
            }
        }
        TextLineAssembler.assemble(boxes)
    }
}
