package com.goldenpaw.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Desktop: no camera; pick one or more image files (photos of the card taken on a phone, scans). */
@Composable
actual fun rememberDocumentCapture(outputDir: String, onResult: (CaptureResult) -> Unit): DocumentCapture {
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onResult)
    return DocumentCapture(
        cameraAvailable = false,
        autoCrop = false,
        scanWithCamera = { deliver(CaptureResult.Failed("No camera on this computer. Choose image files instead.")) },
        pickFromGallery = {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val dialog = FileDialog(null as Frame?, "Choose photos or scans of the card", FileDialog.LOAD).apply {
                        isMultipleMode = true
                        setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } }
                        isVisible = true
                    }
                    val chosen = dialog.files.orEmpty().toList()
                    if (chosen.isEmpty()) return@withContext CaptureResult.Cancelled
                    val dir = File(outputDir).apply { mkdirs() }
                    val paths = chosen.mapNotNull { runCatching { downscale(it, dir, maxSize = 2200) }.getOrNull() }
                    if (paths.isEmpty()) CaptureResult.Failed("Couldn't read those images.") else CaptureResult.Pages(paths)
                }
                deliver(result)
            }
        },
        openAppSettings = {},
    )
}

/** Rotation with AffineTransformOp; enhancement = greyscale with stretched contrast. */
class DesktopPageImageProcessor : PageImageProcessor {
    override suspend fun process(sourcePath: String, outputDir: String, quarterTurns: Int, enhance: Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageIO.read(File(sourcePath)) ?: return@runCatching null
                val turns = ((quarterTurns % 4) + 4) % 4
                var image = toRgb(source)
                if (turns != 0) {
                    val swap = turns % 2 == 1
                    val w = image.width
                    val h = image.height
                    val transform = AffineTransform().apply {
                        translate(if (swap) h / 2.0 else w / 2.0, if (swap) w / 2.0 else h / 2.0)
                        rotate(Math.PI / 2 * turns)
                        translate(-w / 2.0, -h / 2.0)
                    }
                    val target = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_RGB)
                    AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR).filter(image, target)
                    image = target
                }
                if (enhance) {
                    for (y in 0 until image.height) {
                        for (x in 0 until image.width) {
                            val rgb = image.getRGB(x, y)
                            val lum = 0.299 * ((rgb shr 16) and 0xFF) + 0.587 * ((rgb shr 8) and 0xFF) + 0.114 * (rgb and 0xFF)
                            val v = ((lum - 128) * 1.35 + 128 + 18).roundToInt().coerceIn(0, 255)
                            image.setRGB(x, y, (v shl 16) or (v shl 8) or v)
                        }
                    }
                }
                val dir = File(outputDir).apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                ImageIO.write(image, "jpg", file)
                file.absolutePath
            }.getOrNull()
        }

    private fun toRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            drawImage(source, 0, 0, java.awt.Color.WHITE, null)
            dispose()
        }
        return rgb
    }
}
