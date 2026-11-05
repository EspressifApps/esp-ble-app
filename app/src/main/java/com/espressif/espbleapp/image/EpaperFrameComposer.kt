package com.espressif.espbleapp.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

enum class EpaperScaleMode {
    /** Fill 296×128, crop overflow (default for shelf labels). */
    CenterCrop,

    /** Fit entire image with white letterbox/pillarbox. */
    FitCenter,
}

data class EpaperComposeOptions(
    val scaleMode: EpaperScaleMode = EpaperScaleMode.CenterCrop,
    val threshold: Int = 128,
    val dither: Boolean = true,
    val invert: Boolean = false,
)

data class EpaperComposeResult(
    val raw: ByteArray,
    /** ARGB_8888 preview at 296×128 (black/white only). */
    val preview: Bitmap,
)

/**
 * Converts an Android [Bitmap] into the SSD1680 raw framebuffer used by Tag/AP.
 *
 * User / ESL Display Information canvas: [BinaryImageEncoder.FRAME_WIDTH] ×
 * [BinaryImageEncoder.FRAME_HEIGHT] (296×128 landscape).
 *
 * Wire buffer: 16 bytes/row × 296 rows (driver EPAPER_W=128 × EPAPER_H=296).
 * Mapping: canvasX → buffer row Y, canvasY → buffer column X; MSB-first;
 * white bit=1, black bit=0 (matches epaper paint / checkerboard).
 */
object EpaperFrameComposer {
    private const val BUF_WIDTH = 128
    private const val BUF_HEIGHT = 296
    private const val WIDTH_BYTES = BUF_WIDTH / 8

    fun compose(source: Bitmap, options: EpaperComposeOptions = EpaperComposeOptions()): EpaperComposeResult {
        require(!source.isRecycled) { "source bitmap recycled" }
        val canvasBmp = scaleToCanvas(source, options.scaleMode)
        try {
            val gray = FloatArray(BinaryImageEncoder.FRAME_WIDTH * BinaryImageEncoder.FRAME_HEIGHT)
            fillGray(canvasBmp, gray)
            val black = BooleanArray(gray.size)
            if (options.dither) {
                floydSteinberg(gray, black, options.threshold)
            } else {
                threshold(gray, black, options.threshold)
            }
            if (options.invert) {
                for (i in black.indices) black[i] = !black[i]
            }
            val raw = packToFramebuffer(black)
            val preview = blackToPreview(black)
            return EpaperComposeResult(raw = raw, preview = preview)
        } finally {
            if (canvasBmp !== source) {
                canvasBmp.recycle()
            }
        }
    }

    /**
     * Packs a 296×128 black map (true = black) into 4736-byte screen_buffer order.
     * Exposed for unit tests / calibration patterns.
     */
    fun packToFramebuffer(black: BooleanArray): ByteArray {
        require(black.size == BinaryImageEncoder.FRAME_BYTES * 8) {
            "expected ${BinaryImageEncoder.FRAME_BYTES * 8} bits, got ${black.size}"
        }
        val out = ByteArray(BinaryImageEncoder.FRAME_BYTES) { 0xFF.toByte() }
        for (cy in 0 until BinaryImageEncoder.FRAME_HEIGHT) {
            for (cx in 0 until BinaryImageEncoder.FRAME_WIDTH) {
                val isBlack = black[cy * BinaryImageEncoder.FRAME_WIDTH + cx]
                val bx = cy
                val by = cx
                val addr = bx / 8 + by * WIDTH_BYTES
                val mask = 0x80 shr (bx % 8)
                if (isBlack) {
                    out[addr] = (out[addr].toInt() and mask.inv()).toByte()
                } else {
                    out[addr] = (out[addr].toInt() or mask).toByte()
                }
            }
        }
        return out
    }

    /** Builds an asymmetric corner marker pattern for orientation calibration. */
    fun calibrationCorners(): ByteArray {
        val black = BooleanArray(BinaryImageEncoder.FRAME_WIDTH * BinaryImageEncoder.FRAME_HEIGHT)
        fun fillRect(x0: Int, y0: Int, w: Int, h: Int) {
            for (y in y0 until min(y0 + h, BinaryImageEncoder.FRAME_HEIGHT)) {
                for (x in x0 until min(x0 + w, BinaryImageEncoder.FRAME_WIDTH)) {
                    black[y * BinaryImageEncoder.FRAME_WIDTH + x] = true
                }
            }
        }
        // Distinct corner sizes so rotation/mirror is obvious.
        fillRect(0, 0, 40, 20) // top-left large
        fillRect(BinaryImageEncoder.FRAME_WIDTH - 16, 0, 16, 16) // top-right
        fillRect(0, BinaryImageEncoder.FRAME_HEIGHT - 12, 12, 12) // bottom-left
        fillRect(BinaryImageEncoder.FRAME_WIDTH - 8, BinaryImageEncoder.FRAME_HEIGHT - 8, 8, 8) // bottom-right tiny
        return packToFramebuffer(black)
    }

    private fun scaleToCanvas(source: Bitmap, mode: EpaperScaleMode): Bitmap {
        val tw = BinaryImageEncoder.FRAME_WIDTH
        val th = BinaryImageEncoder.FRAME_HEIGHT
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()
        if (sw <= 0f || sh <= 0f) {
            return out
        }
        val scale = when (mode) {
            EpaperScaleMode.CenterCrop -> max(tw / sw, th / sh)
            EpaperScaleMode.FitCenter -> min(tw / sw, th / sh)
        }
        val dw = sw * scale
        val dh = sh * scale
        val dx = (tw - dw) / 2f
        val dy = (th - dh) / 2f
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return out
    }

    private fun fillGray(bmp: Bitmap, gray: FloatArray) {
        val w = BinaryImageEncoder.FRAME_WIDTH
        val h = BinaryImageEncoder.FRAME_HEIGHT
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // Transparent → white
            val lum = if (a < 16) {
                255f
            } else {
                0.299f * r + 0.587f * g + 0.114f * b
            }
            gray[i] = lum
        }
    }

    private fun threshold(gray: FloatArray, black: BooleanArray, threshold: Int) {
        val t = threshold.coerceIn(0, 255).toFloat()
        for (i in gray.indices) {
            black[i] = gray[i] < t
        }
    }

    private fun floydSteinberg(gray: FloatArray, black: BooleanArray, threshold: Int) {
        val w = BinaryImageEncoder.FRAME_WIDTH
        val h = BinaryImageEncoder.FRAME_HEIGHT
        val buf = gray.copyOf()
        val t = threshold.coerceIn(0, 255).toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = buf[i]
                val isBlack = old < t
                black[i] = isBlack
                val neu = if (isBlack) 0f else 255f
                val err = old - neu
                if (x + 1 < w) buf[i + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) buf[i + w - 1] += err * 3f / 16f
                    buf[i + w] += err * 5f / 16f
                    if (x + 1 < w) buf[i + w + 1] += err * 1f / 16f
                }
            }
        }
    }

    private fun blackToPreview(black: BooleanArray): Bitmap {
        val w = BinaryImageEncoder.FRAME_WIDTH
        val h = BinaryImageEncoder.FRAME_HEIGHT
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (i in black.indices) {
            pixels[i] = if (black[i]) Color.BLACK else Color.WHITE
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

}
