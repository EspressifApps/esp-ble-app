package com.espressif.espbleapp.flux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxProtocolTest {

    @Test
    fun testSinglePacketBuildAndParse() {
        val payload = "Hello Flux!".toByteArray(Charsets.UTF_8)
        val pktBytes = FluxProtocol.buildSinglePacket(streamId = 1, payload = payload)

        val parsed = FluxProtocol.parsePacket(pktBytes)
        assertNotNull(parsed)
        assertTrue(parsed is FluxProtocol.FluxPacket.Single)

        val single = parsed as FluxProtocol.FluxPacket.Single
        assertEquals(1, single.streamId)
        assertEquals(0, single.seqNum)
        assertArrayEquals(payload, single.payload)
    }

    @Test
    fun testStartPacketBuildAndParse() {
        val startPayload = "First chunk".toByteArray(Charsets.UTF_8)
        val pktBytes = FluxProtocol.buildStartPacket(
            streamId = 2,
            windowSize = 6,
            windowThresholdPercent = 80,
            startPayload = startPayload,
            totalFrags = 5,
            totalSize = 1000L,
        )

        val parsed = FluxProtocol.parsePacket(pktBytes)
        assertNotNull(parsed)
        assertTrue(parsed is FluxProtocol.FluxPacket.Start)

        val start = parsed as FluxProtocol.FluxPacket.Start
        assertEquals(2, start.streamId)
        assertEquals(6, start.windowSize)
        assertEquals(80, start.windowThresholdPercent)
        assertEquals(5, start.totalFrags)
        assertEquals(1000L, start.totalSize)
        assertArrayEquals(startPayload, start.payload)
    }

    @Test
    fun testFragmentPacketBuildAndParse() {
        val fragPayload = "Middle chunk".toByteArray(Charsets.UTF_8)
        val pktBytes = FluxProtocol.buildFragmentPacket(
            streamId = 0,
            seqNum = 3,
            fragPayload = fragPayload,
        )

        val parsed = FluxProtocol.parsePacket(pktBytes)
        assertNotNull(parsed)
        assertTrue(parsed is FluxProtocol.FluxPacket.Fragment)

        val frag = parsed as FluxProtocol.FluxPacket.Fragment
        assertEquals(0, frag.streamId)
        assertEquals(3, frag.seqNum)
        assertArrayEquals(fragPayload, frag.payload)
    }

    @Test
    fun testMultiFragmentBuildAndReassembly() {
        // Create 2000 bytes payload
        val testString = "The quick brown fox jumps over the lazy dog. ".repeat(45)
        val testBytes = testString.toByteArray(Charsets.UTF_8)

        val mtu = 247
        val packets = FluxProtocol.buildPacketsForData(
            data = testBytes,
            mtu = mtu,
            streamId = 0,
        )

        assertTrue("Should produce multiple fragments", packets.size > 1)

        val reassembler = FluxReassembler()
        var result: ByteArray? = null

        for (pktBytes in packets) {
            val parsed = FluxProtocol.parsePacket(pktBytes)
            assertNotNull(parsed)
            val res = reassembler.feed(parsed!!)
            if (res != null) {
                result = res
            }
        }

        assertNotNull("Reassembled result should not be null", result)
        assertArrayEquals(testBytes, result)
    }
}
