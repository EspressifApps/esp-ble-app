package com.espressif.espbleapp.image

import java.util.zip.CRC32

object BinaryImageEncoder {
    /** SSD1680 panel logical size reported by Tag Display Information. */
    const val FRAME_WIDTH = 296
    const val FRAME_HEIGHT = 128

    /**
     * Raw SSD1680 framebuffer size matching Tag epaper driver:
     * EPAPER_W(128) * EPAPER_H(296) / 8 = 4736.
     */
    const val FRAME_BYTES = 128 * 296 / 8

    fun crc32Hex(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return "%08x".format(crc.value)
    }

    /** Unsigned CRC32 as Long (0..0xFFFFFFFF). */
    fun crc32Value(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    /**
     * Full-frame 1bpp checkerboard in epaper screen_buffer order
     * (16 bytes/row × 296 rows).
     */
    fun demoCheckerboard(): ByteArray {
        val widthBytes = 16
        val rows = 296
        val out = ByteArray(FRAME_BYTES)
        for (y in 0 until rows) {
            for (xb in 0 until widthBytes) {
                val cell = ((y / 8) + xb) and 1
                out[y * widthBytes + xb] = if (cell == 0) 0xFF.toByte() else 0x00
            }
        }
        return out
    }

    fun demoSolid(fill: Int): ByteArray = ByteArray(FRAME_BYTES) { fill.toByte() }
}
