package com.goldenpaw.platform

import android.content.Context
import com.goldenpaw.domain.repository.AppFiles
import java.io.File

class AndroidAppFiles(context: Context) : AppFiles {
    private val app = context.applicationContext
    override val dataDir: String get() = app.filesDir.absolutePath
    override val cacheDir: String get() = app.cacheDir.absolutePath
    override val photosDir: String get() = File(app.filesDir, "photos").apply { mkdirs() }.absolutePath
    override val documentsDir: String get() = File(app.filesDir, "documents").apply { mkdirs() }.absolutePath

    override fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).exists()

    override fun delete(path: String?): Boolean =
        !path.isNullOrBlank() && runCatching { File(path).delete() }.getOrDefault(false)

    override fun deleteRecursively(dir: String) {
        runCatching { File(dir).deleteRecursively() }
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun readBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()
}
