package com.espressif.espbleapp.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.max

/**
 * Sample-decodes image bytes / content [Uri] into a Bitmap sized for epaper compose,
 * applying EXIF orientation when available.
 */
object ImageBitmapLoader {
    /** Decode so the longer side is at most this many pixels before compose. */
    private const val MAX_EDGE = 1024

    /**
     * Preferred path for Photo Picker: caller opens the stream while the temporary
     * URI grant is still valid (typically on the Activity result callback thread),
     * then decode from the already-read [bytes].
     */
    fun load(bytes: ByteArray): Bitmap {
        if (bytes.isEmpty()) {
            throw IOException("Empty image data")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Invalid image dimensions")
        }

        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("Failed to decode image bytes")

        val orientation = readExifOrientation(bytes)
        return applyExif(decoded, orientation)
    }

    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to open image: $uri")
        return load(bytes)
    }

    private fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (max(w, h) / 2 >= maxEdge) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun readExifOrientation(bytes: ByteArray): Int {
        return try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) {
            bitmap.recycle()
        }
        return oriented
    }
}
