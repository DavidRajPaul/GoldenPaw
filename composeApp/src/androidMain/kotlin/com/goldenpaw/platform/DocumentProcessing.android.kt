package com.goldenpaw.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.goldenpaw.domain.logic.TextBox
import com.goldenpaw.domain.logic.TextLineAssembler
import com.goldenpaw.domain.repository.DocumentTextReader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Rotation + a "scanned document" look (grey, higher contrast, brighter paper). */
class AndroidPageImageProcessor : PageImageProcessor {
    override suspend fun process(sourcePath: String, outputDir: String, quarterTurns: Int, enhance: Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = BitmapFactory.decodeFile(sourcePath) ?: return@runCatching null
                val turns = ((quarterTurns % 4) + 4) % 4
                val rotated = if (turns == 0) {
                    source
                } else {
                    val matrix = Matrix().apply { postRotate(90f * turns) }
                    Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                }
                val output = if (!enhance) {
                    rotated
                } else {
                    val result = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(documentMatrix()) }
                    Canvas(result).drawBitmap(rotated, 0f, 0f, paint)
                    result
                }
                val dir = File(outputDir).apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                file.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (output !== rotated) output.recycle()
                if (rotated !== source) rotated.recycle()
                source.recycle()
                file.absolutePath
            }.getOrNull()
        }

    private fun documentMatrix(): ColorMatrix {
        val contrast = 1.35f
        val offset = (1f - contrast) * 128f + 18f
        return ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
    }
}

/** On-device text recognition (ML Kit, Latin script). Nothing leaves the phone. */
class MlKitTextReader(private val context: Context) : DocumentTextReader {
    override val isAvailable: Boolean = true

    override suspend fun read(imagePath: String): String = withContext(Dispatchers.IO) {
        val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(File(imagePath))) }.getOrNull()
            ?: return@withContext ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            suspendCancellableCoroutine<String> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val boxes = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                            val box: Rect = line.boundingBox ?: return@mapNotNull null
                            TextBox(line.text, box.left.toFloat(), box.top.toFloat(), box.bottom.toFloat())
                        }
                        cont.resume(TextLineAssembler.assemble(boxes).ifBlank { text.text })
                    }
                    .addOnFailureListener { cont.resume("") }
            }
        } finally {
            recognizer.close()
        }
    }
}
