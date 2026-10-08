package com.goldenpaw.report

/**
 * Pixel size and colour components of a JPEG, read from its SOF marker (no decoding).
 * Used to fit scanned pages on a PDF page and to embed them with DCTDecode.
 */
data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height

    companion object {
        fun read(bytes: ByteArray): JpegInfo? {
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            if (bytes.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
            var i = 2
            while (i + 3 < bytes.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val marker = u8(i + 1)
                when {
                    marker == 0xFF -> { i++; continue } // fill byte
                    marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> { i += 2; continue }
                    marker == 0xD9 || marker == 0xDA -> return null // end of image / start of scan before any SOF
                }
                val length = u16(i + 2)
                // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (i + 9 >= bytes.size) return null
                    val height = u16(i + 5)
                    val width = u16(i + 7)
                    val components = u8(i + 9)
                    return if (width > 0 && height > 0) JpegInfo(width, height, components) else null
                }
                i += 2 + length
            }
            return null
        }
    }
}
