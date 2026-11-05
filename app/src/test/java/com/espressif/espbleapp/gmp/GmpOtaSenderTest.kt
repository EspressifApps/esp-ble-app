package com.espressif.espbleapp.gmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GmpOtaSenderTest {
    @Test
    fun otaUsesGmpDataAcksAndExcludesNegotiationAndFinishFromTiming() = runTest {
        val firmware = ByteArray(600) { it.toByte() }
        val requests = mutableListOf<GmpFrame>()
        val link = object : GmpRequestLink {
            override val maxGmpPayload = 520
            override suspend fun send(frame: ByteArray) = error("OTA must use GMP request/response")
            override suspend fun request(frame: GmpFrame, onFirstPacketSent: (() -> Unit)?): GmpFrame {
                requests += frame
                val response = when {
                    frame.groupId == 0 -> {
                        assertEquals(GmpOperation.READ_REQUEST, frame.operation)
                        assertEquals(null, onFirstPacketSent)
                        delay(2_000)
                        ByteArray(12).also { it[8] = 0x10; it[10] = 1 } // max payload 4096
                    }
                    frame.commandId == GmpOtaProtocol.OTA_UPLOAD_DATA -> {
                        onFirstPacketSent?.invoke()
                        delay(100)
                        byteArrayOf()
                    }
                    frame.payload[0].toInt() == GmpOtaProtocol.OTA_ACTION_START -> {
                        assertEquals(null, onFirstPacketSent)
                        delay(3_000)
                        byteArrayOf(7, 1, 0) // session 7, chunk hint 256
                    }
                    else -> {
                        assertEquals(null, onFirstPacketSent)
                        delay(4_000)
                        byteArrayOf()
                    }
                }
                return frame.copy(
                    operation = if (frame.operation == GmpOperation.READ_REQUEST) GmpOperation.READ_RESPONSE else GmpOperation.WRITE_RESPONSE,
                    payload = response,
                )
            }
        }
        val sender = GmpOtaSender(link, {}, clockMs = { testScheduler.currentTime + 1 })
        sender.upload(firmware)
        val state = sender.progress.value
        assertFalse(state.isUploading)
        assertEquals(600, state.confirmedBytes)
        assertEquals(5_001L, state.dataTransferStartedAtMs)
        assertEquals(300L, state.dataTransferElapsedMs)
        val data = requests.filter { it.groupId == 1 && it.commandId == GmpOtaProtocol.OTA_UPLOAD_DATA }
        assertEquals(listOf(256, 256, 88), data.map { it.payload.size - 8 })
        assertTrue(data.all { it.payload[0].toInt() == 7 })
        assertEquals(1, data.last().payload[1].toInt())
        assertArrayEquals(firmware, data.flatMap { it.payload.drop(8) }.toByteArray())
        assertArrayEquals(GmpOtaProtocol.calculateSha256(firmware), requests.last().payload.copyOfRange(8, 40))
    }

    @Test
    fun missingDataAckDoesNotAdvanceConfirmedBytesOrSendFinish() = runTest {
        val commands = mutableListOf<GmpFrame>()
        val link = object : GmpRequestLink {
            override val maxGmpPayload = 4096
            override suspend fun send(frame: ByteArray) = Unit
            override suspend fun request(frame: GmpFrame, onFirstPacketSent: (() -> Unit)?): GmpFrame {
                commands += frame
                val payload = when {
                    frame.groupId == 0 -> ByteArray(12).also { it[8] = 0x10; it[10] = 1 }
                    frame.commandId == GmpOtaProtocol.OTA_UPLOAD_CONTROL -> byteArrayOf(7, 1, 0)
                    else -> {
                        onFirstPacketSent?.invoke()
                        error("GMP DATA response timeout")
                    }
                }
                return frame.copy(operation = if (frame.groupId == 0) GmpOperation.READ_RESPONSE else GmpOperation.WRITE_RESPONSE, payload = payload)
            }
        }
        val sender = GmpOtaSender(link, {})
        sender.upload(ByteArray(300))
        assertEquals(0, sender.progress.value.confirmedBytes)
        assertFalse(sender.progress.value.isUploading)
        assertTrue(sender.progress.value.status.contains("timeout"))
        assertEquals(3, commands.size)
    }
}
