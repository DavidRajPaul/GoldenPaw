package com.goldenpaw.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Copies a picked photo into app-private storage, downscaled to ~[maxSize] px and EXIF-rotated (so
 * the saved JPEG is upright with no orientation tag, which the PDF writer relies on).
 */
suspend fun importPhoto(
    context: Context,
    uri: Uri,
    maxSize: Int = 1280,
    dir: File = File(context.filesDir, "photos"),
    quality: Int = 85,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        dir.mkdirs()
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val largest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / (sample * 2) >= maxSize) sample *= 2

        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null

        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = minOf(1f, maxSize.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val output = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (output !== decoded) decoded.recycle()
        file.absolutePath
    }.getOrNull()
}
