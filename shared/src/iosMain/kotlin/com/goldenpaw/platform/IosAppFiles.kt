package com.goldenpaw.platform

import com.goldenpaw.domain.repository.AppFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
class IosAppFiles : AppFiles {
    private val fm = NSFileManager.defaultManager

    private fun directory(type: NSSearchPathDirectory): String {
        val url = fm.URLForDirectory(type, NSUserDomainMask, null, true, null)
        return requireNotNull(url?.path) { "Missing directory $type" }
    }

    private fun ensure(path: String): String {
        if (!fm.fileExistsAtPath(path)) fm.createDirectoryAtPath(path, true, null, null)
        return path
    }

    override val dataDir: String get() = directory(NSDocumentDirectory)
    override val cacheDir: String get() = directory(NSCachesDirectory)
    override val photosDir: String get() = ensure("$dataDir/photos")
    override val documentsDir: String get() = ensure("$dataDir/documents")

    override fun exists(path: String?): Boolean = !path.isNullOrBlank() && fm.fileExistsAtPath(path)

    override fun delete(path: String?): Boolean =
        !path.isNullOrBlank() && fm.fileExistsAtPath(path) && fm.removeItemAtPath(path, null)

    override fun deleteRecursively(dir: String) {
        if (fm.fileExistsAtPath(dir)) fm.removeItemAtPath(dir, null)
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        ensure(path.substringBeforeLast('/'))
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        data.writeToFile(path, true)
    }

    override fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val length = data.length.toInt()
        if (length == 0) return ByteArray(0)
        val source = data.bytes ?: return null
        return ByteArray(length).apply { usePinned { pinned -> memcpy(pinned.addressOf(0), source, data.length) } }
    }
}
