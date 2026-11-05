package com.espressif.espbleapp.gmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GmpFrameTest {
    @Test
    fun writeRequestMatchesReferenceLayout() {
        val frame = GmpFrame(
            operation = GmpOperation.WRITE_REQUEST,
            groupId = 0x08,
            sequence = 0x1234,
            commandId = 0x01,
            payload = byteArrayOf(0xAA.toByte(), 0x55),
        )
        assertArrayEquals(
            byteArrayOf(
                0x12, 0x08, 0x12, 0x34, 0, 1, 0, 0, 0, 2,
                0xAA.toByte(), 0x55,
            ),
            GmpFrameCodec.encode(frame),
        )
    }

    @Test
    fun responseRoundTripPreservesSequenceAndStatus() {
        val expected = GmpFrame(
            GmpOperation.WRITE_RESPONSE, 0x08, 0xFFFE, 0x02,
            status = GmpStatus.BUSY.code, payload = byteArrayOf(),
        )
        assertEquals(expected, GmpFrameCodec.decode(GmpFrameCodec.encode(expected)))
    }

    @Test
    fun commonCodecSupportsOsOtaAndFileTransferGroups() {
        for (group in listOf(0x00, 0x01, 0x08)) {
            val frame = GmpFrame(GmpOperation.WRITE_REQUEST, group, 1, 1, flags = 1, payload = byteArrayOf(2))
            assertEquals(frame, GmpFrameCodec.decode(GmpFrameCodec.encode(frame)))
        }
    }

    @Test
    fun rejectsOutOfRangeFieldsAndZeroSequence() {
        for (frame in listOf(
            GmpFrame(GmpOperation.WRITE_REQUEST, 256, 1, 1, payload = byteArrayOf()),
            GmpFrame(GmpOperation.WRITE_REQUEST, 8, 1, 1, flags = 256, payload = byteArrayOf()),
            GmpFrame(GmpOperation.WRITE_REQUEST, 8, 0, 1, payload = byteArrayOf()),
        )) {
            assertFailsWith<IllegalArgumentException> { GmpFrameCodec.encode(frame) }
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFrameCodec.decode(byteArrayOf(0x12, 8, 0, 0, 0, 1, 0, 0, 0, 0))
        }
    }

    @Test
    fun rejectsLengthMismatchAndReservedByte() {
        assertFailsWith<IllegalArgumentException> {
            GmpFrameCodec.decode(byteArrayOf(0x12, 8, 0, 1, 0, 1, 0, 0, 0, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFrameCodec.decode(byteArrayOf(0x12, 8, 0, 1, 1, 1, 0, 0, 0, 0))
        }
    }
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
        throw AssertionError("Expected ${T::class.simpleName}")
    } catch (e: Throwable) {
        if (e is AssertionError && e.message?.startsWith("Expected") == true) throw e
        if (e !is T) {
            throw AssertionError("Expected ${T::class.simpleName}, got ${e::class.simpleName}", e)
        }
    }
}
