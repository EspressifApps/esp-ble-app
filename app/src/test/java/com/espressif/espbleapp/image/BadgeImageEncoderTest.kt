package com.espressif.espbleapp.image

import com.espressif.espbleapp.data.HelloInfo
import com.espressif.espbleapp.data.TagRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeImageEncoderTest {
    @Test
    fun rgb565Frame_hasRequiredWireSizeAndLittleEndianPixels() {
        val pixels = IntArray(BadgeImageEncoder.FRAME_WIDTH * BadgeImageEncoder.FRAME_HEIGHT)
        pixels[0] = 0xffff0000.toInt()
        pixels[1] = 0xff00ff00.toInt()
        pixels[2] = 0xff0000ff.toInt()

        val raw = BadgeImageEncoder.encodeRgb565Le(pixels)

        assertEquals(460800, raw.size)
        assertArrayEquals(
            byteArrayOf(0x00, 0xf8.toByte(), 0xe0.toByte(), 0x07, 0x1f, 0x00),
            raw.copyOfRange(0, 6),
        )
    }

    @Test
    fun badgeGate_requiresExactFullRgbDisplayInfo() {
        assertTrue(TagRecord(displayWidth = 480, displayHeight = 480, displayType = 11).supportsBadgeImage)
        assertFalse(TagRecord(displayWidth = 480, displayHeight = 480, displayType = 1).supportsBadgeImage)
        assertFalse(TagRecord(displayWidth = 296, displayHeight = 128, displayType = 11).supportsBadgeImage)
        assertFalse(TagRecord().supportsBadgeImage)
    }

    @Test
    fun apBadgeGate_requiresUploadAndTransferCapabilities() {
        assertTrue(
            HelloInfo(
                capabilities = listOf("upload_badge_image_begin", "transfer_badge_image_to_tag"),
            ).supportsBadgeImage,
        )
        assertFalse(HelloInfo(capabilities = listOf("upload_badge_image_begin")).supportsBadgeImage)
        assertFalse(HelloInfo().supportsBadgeImage)
        assertTrue(HelloInfo(capabilities = listOf("upload_raw")).supportsRawUpload)
        assertFalse(HelloInfo().supportsRawUpload)
    }
}
