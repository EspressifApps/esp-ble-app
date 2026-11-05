package com.espressif.espbleapp.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

data class BadgeComposeResult(
    val raw: ByteArray,
    val preview: Bitmap,
)

/** Converts gallery images to the Mosaico badge's fixed 480×480 RGB565 frame. */
object BadgeImageEncoder {
    const val FRAME_WIDTH = 480
    const val FRAME_HEIGHT = 480
    const val BYTES_PER_PIXEL = 2
    const val FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * BYTES_PER_PIXEL

    fun compose(source: Bitmap): BadgeComposeResult {
        require(!source.isRecycled) { "source bitmap recycled" }
        val preview = centerCrop(source)
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT)
        preview.getPixels(pixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        return BadgeComposeResult(raw = encodeRgb565Le(pixels), preview = preview)
    }

    /**
     * Packs ARGB pixels as RGB565, least-significant byte first on the wire.
     * The 16-bit value is RRRRRGGG GGGBBBBB, so red's high bits are in byte 1.
     */
    fun encodeRgb565Le(argb: IntArray): ByteArray {
        require(argb.size == FRAME_WIDTH * FRAME_HEIGHT) {
            "expected ${FRAME_WIDTH * FRAME_HEIGHT} pixels, got ${argb.size}"
        }
        val out = ByteArray(FRAME_BYTES)
        var dst = 0
        for (color in argb) {
            val r = (color ushr 16) and 0xff
            val g = (color ushr 8) and 0xff
            val b = color and 0xff
            val rgb565 = ((r and 0xf8) shl 8) or ((g and 0xfc) shl 3) or (b ushr 3)
            out[dst++] = (rgb565 and 0xff).toByte()
            out[dst++] = (rgb565 ushr 8).toByte()
        }
        return out
    }

    private fun centerCrop(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
        val scale = max(FRAME_WIDTH / source.width.toFloat(), FRAME_HEIGHT / source.height.toFloat())
        val dx = (FRAME_WIDTH - source.width * scale) / 2f
        val dy = (FRAME_HEIGHT - source.height * scale) / 2f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        Canvas(out).drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }
}
