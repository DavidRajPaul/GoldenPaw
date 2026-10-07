package com.goldenpaw.platform

import com.goldenpaw.domain.repository.AppFiles
import java.io.File

/** Per-user app folder: ~/Library/Application Support/GoldenPaw, %APPDATA%\GoldenPaw or ~/.local/share/goldenpaw. */
class DesktopAppFiles : AppFiles {
    private val root: File by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val dir = when {
            os.contains("mac") -> File(home, "Library/Application Support/GoldenPaw")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home, "GoldenPaw")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "goldenpaw")
        }
        dir.apply { mkdirs() }
    }

    override val dataDir: String get() = root.absolutePath
    override val cacheDir: String get() = File(root, "cache").apply { mkdirs() }.absolutePath
    override val photosDir: String get() = File(root, "photos").apply { mkdirs() }.absolutePath

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
}
