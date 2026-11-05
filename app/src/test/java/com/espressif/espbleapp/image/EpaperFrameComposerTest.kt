package com.espressif.espbleapp.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpaperFrameComposerTest {
    @Test
    fun packAllWhite_is0xFF() {
        val black = BooleanArray(BinaryImageEncoder.FRAME_WIDTH * BinaryImageEncoder.FRAME_HEIGHT)
        val raw = EpaperFrameComposer.packToFramebuffer(black)
        assertEquals(BinaryImageEncoder.FRAME_BYTES, raw.size)
        assertTrue(raw.all { it == 0xFF.toByte() })
    }

    @Test
    fun packAllBlack_is0x00() {
        val black = BooleanArray(BinaryImageEncoder.FRAME_WIDTH * BinaryImageEncoder.FRAME_HEIGHT) { true }
        val raw = EpaperFrameComposer.packToFramebuffer(black)
        assertEquals(BinaryImageEncoder.FRAME_BYTES, raw.size)
        assertTrue(raw.all { it == 0x00.toByte() })
    }

    @Test
    fun topLeftPixel_mapsToBufferRow0Col0Msb() {
        val black = BooleanArray(BinaryImageEncoder.FRAME_WIDTH * BinaryImageEncoder.FRAME_HEIGHT)
        black[0] = true // canvas (0,0)
        val raw = EpaperFrameComposer.packToFramebuffer(black)
        // bx=cy=0, by=cx=0 → addr 0, clear bit 0x80
        assertEquals(0x7F.toByte(), raw[0])
        assertTrue(raw.drop(1).all { it == 0xFF.toByte() })
    }

    @Test
    fun topRightPixel_mapsToLastBufferRow() {
        val black = BooleanArray(BinaryImageEncoder.FRAME_WIDTH * BinaryImageEncoder.FRAME_HEIGHT)
        val cx = BinaryImageEncoder.FRAME_WIDTH - 1
        val cy = 0
        black[cy * BinaryImageEncoder.FRAME_WIDTH + cx] = true
        val raw = EpaperFrameComposer.packToFramebuffer(black)
        val addr = 0 / 8 + cx * 16
        assertEquals(0x7F.toByte(), raw[addr])
    }

    @Test
    fun calibrationCorners_areAsymmetric() {
        val raw = EpaperFrameComposer.calibrationCorners()
        assertEquals(BinaryImageEncoder.FRAME_BYTES, raw.size)
        // TL 40×20 → canvas (0,0) blacks buffer row Y=0, bits X=0..7.
        assertEquals(0x00.toByte(), raw[0])
        // TR 16×16 → canvas (280,0) blacks buffer row Y=280, bits X=0..7.
        assertEquals(0x00.toByte(), raw[280 * 16])
        // BL 12×12 → canvas (0,116) blacks buffer row Y=0, bits X=116..123 (byte index 14).
        assertNotEquals(0xFF.toByte(), raw[14])
        // BR 8×8 → canvas x=288..295,y=120..127 → buffer row Y=288..295, high X bits only.
        // Row Y=287 is above BR's x-range and TR only paints low X → last byte stays white.
        assertEquals(0xFF.toByte(), raw[287 * 16 + 15])
        assertEquals(0x00.toByte(), raw[295 * 16 + 15])
        // Distinct corner sizes ⇒ flipping X would move TR onto a different footprint than BR.
        assertNotEquals(raw[280 * 16 + 15], raw[295 * 16 + 15])
        val crc1 = BinaryImageEncoder.crc32Hex(raw)
        val crc2 = BinaryImageEncoder.crc32Hex(EpaperFrameComposer.calibrationCorners())
        assertEquals(crc1, crc2)
    }

    @Test
    fun customImageIds_areStablePerSlot() {
        assertEquals(1001, ImageUploadIds.customImageId(0))
        assertEquals(1004, ImageUploadIds.customImageId(3))
        val ids = (0..3).map { ImageUploadIds.customImageId(it) }.toSet()
        assertEquals(4, ids.size)
        assertTrue(ids.all { it in 1..Int.MAX_VALUE })
    }

    @Test
    fun versionFromCrc_staysPositiveInt32() {
        val bytes = BinaryImageEncoder.demoCheckerboard()
        val hex = BinaryImageEncoder.crc32Hex(bytes)
        val version = ImageUploadIds.versionFromCrc32Hex(hex)
        assertTrue(version in 1..Int.MAX_VALUE)
        assertEquals(version, ImageUploadIds.versionFromCrc32Hex(hex))
    }
}
