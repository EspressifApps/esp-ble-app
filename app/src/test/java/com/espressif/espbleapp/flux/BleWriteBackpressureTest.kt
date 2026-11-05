package com.espressif.espbleapp.flux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleWriteBackpressureTest {
    @Test
    fun healthyWritesHaveNoArtificialDelay() = runTest {
        val pressure = BleWriteBackpressure { testScheduler.currentTime }
        repeat(100) {
            pressure.awaitReady()
            pressure.onWriteSucceeded()
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun congestionBackoffGrowsAndIsBounded() = runTest {
        val pressure = BleWriteBackpressure { testScheduler.currentTime }
        for (expected in listOf(10L, 20L, 40L, 80L, 100L, 100L)) {
            assertTrue(pressure.onCongested())
            assertEquals(expected, pressure.remainingDelayMs())
            pressure.awaitReady()
            assertEquals(0L, pressure.remainingDelayMs())
        }
    }

    @Test
    fun lateSuccessKeepsCurrentPauseAndResetsNextBackoff() = runTest {
        val pressure = BleWriteBackpressure { testScheduler.currentTime }
        pressure.onCongested()
        pressure.onCongested()
        pressure.onWriteSucceeded()
        assertEquals(20L, pressure.remainingDelayMs())
        pressure.awaitReady()
        pressure.onCongested()
        assertEquals(10L, pressure.remainingDelayMs())
    }

    @Test
    fun persistentCongestionHasFiniteRetryBudget() = runTest {
        val pressure = BleWriteBackpressure { testScheduler.currentTime }
        repeat(20) { assertTrue(pressure.onCongested()) }
        assertFalse(pressure.onCongested())
        val newConnection = BleWriteBackpressure { testScheduler.currentTime }
        assertEquals(0L, newConnection.remainingDelayMs())
        assertTrue(newConnection.onCongested())
    }

    @Test
    fun congestionPausesWritesAndFluxRetransmitsOnlyTheMissingFragment() = runTest {
        val pressure = BleWriteBackpressure { testScheduler.currentTime }
        val attempts = mutableListOf<FluxProtocol.FluxPacket>()
        var dropped = false
        val session = ReliableFluxSession(this, FluxPacketWriter { bytes ->
            pressure.awaitReady()
            val packet = requireNotNull(FluxProtocol.parsePacket(bytes))
            attempts += packet
            if (!dropped && packet is FluxProtocol.FluxPacket.Fragment && packet.seqNum == 1) {
                dropped = true
                pressure.onCongested()
            } else {
                pressure.onWriteSucceeded()
            }
        }, mtu = 40)
        val data = ByteArray(80) { it.toByte() }
        val send = async { session.send(data) }
        runCurrent()
        assertEquals(2, attempts.size)
        advanceTimeBy(9)
        runCurrent()
        assertEquals(2, attempts.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, attempts.size)
        assertFalse(send.isCompleted)
        val stream = attempts.first().streamId
        session.onPacket(FluxProtocol.buildSackPacket(stream, 2, ByteArray(16).also { it[0] = 2 }))
        runCurrent()
        assertEquals(4, attempts.size)
        assertEquals(1, (attempts.last() as FluxProtocol.FluxPacket.Fragment).seqNum)
        assertEquals(1, attempts.count { it is FluxProtocol.FluxPacket.Start })
        assertFalse(send.isCompleted)
        session.onPacket(FluxProtocol.buildSackPacket(stream, 2))
        assertArrayEquals(data, send.await())
    }
}
