package com.goldenpaw.platform

import kotlinx.coroutines.flow.MutableSharedFlow

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.runtime.getValue

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.goldenpaw.domain.repository.AppFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.max

@Composable
actual fun rememberHaptics(): Haptics = remember {
    object : Haptics {
        override fun confirm() = Unit
        override fun tick() = Unit
        override fun reject() = Unit
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (String?) -> Unit): () -> Unit {
    val files: AppFiles = koinInject()
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val chosen = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Choose a photo", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } }
                    isVisible = true
                }
                val name = dialog.file ?: return@withContext null
                runCatching { downscale(File(dialog.directory, name), File(files.photosDir)) }.getOrNull()
            }
            onPicked(chosen)
        }
    }
}

internal fun downscale(source: File, dir: File, maxSize: Int = 1280): String? {
    val image = ImageIO.read(source) ?: return null
    val scale = minOf(1.0, maxSize.toDouble() / max(image.width, image.height))
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(image, 0, 0, w, h, null)
        dispose()
    }
    dir.mkdirs()
    val file = File(dir, "${UUID.randomUUID()}.jpg")
    ImageIO.write(out, "jpg", file)
    return file.absolutePath
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit = { onResult(true) }

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (progress: Float, fromLeftEdge: Boolean) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    // Escape in the main window (see main.kt) goes back one screen, like the top-bar back button.
    val latest by rememberUpdatedState(onBack)
    if (enabled) {
        LaunchedEffect(Unit) { DesktopBackEvents.events.collect { latest() } }
    }
}

/** Back requests from the keyboard (Escape). Only delivered while a back handler is enabled. */
object DesktopBackEvents {
    internal val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True when a screen can go back and the request was handed to it. */
    fun requestBack(): Boolean = events.subscriptionCount.value > 0 && events.tryEmit(Unit)
}

@Composable
actual fun rememberPdfPreview(path: String?): ImageBitmap? = null

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun systemPrefersReducedMotion(): Boolean = false
