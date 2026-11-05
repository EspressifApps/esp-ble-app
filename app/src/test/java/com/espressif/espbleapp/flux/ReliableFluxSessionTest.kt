package com.espressif.espbleapp.flux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ReliableFluxSessionTest {
    private class GateFirstDispatcher : CoroutineDispatcher() {
        private var first: Runnable? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val runNow = synchronized(this) {
                if (first == null) {
                    first = block
                    false
                } else {
                    true
                }
            }
            if (runNow) block.run()
        }

        fun releaseFirst() {
            val block = synchronized(this) {
                first.also { first = null }
            } ?: error("No dispatch is gated")
            block.run()
        }
    }

    private class FakeWriter : FluxPacketWriter {
        val packets = mutableListOf<ByteArray>()

        override suspend fun write(packet: ByteArray) {
            packets += packet.copyOf()
        }
    }

    @Test
    fun oneFragmentUsesStartAndWaitsForSack() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 247)

        val send = async { session.send(byteArrayOf(1, 2, 3)) }
        runCurrent()

        val start = FluxProtocol.parsePacket(writer.packets.single()) as FluxProtocol.FluxPacket.Start
        assertEquals(1, start.totalFrags)
        assertEquals(1, start.windowSize)
        assertFalse(send.isCompleted)

        session.onPacket(FluxProtocol.buildSackPacket(start.streamId, 0))

        assertArrayEquals(byteArrayOf(1, 2, 3), send.await())
    }

    @Test
    fun initialSendFillsWindowAndCumulativeAckFillsNextWindow() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 23, windowSize = 2)
        val send = async { session.send(ByteArray(55) { it.toByte() }) }
        runCurrent()

        assertEquals(listOf(0, 1), writer.packets.map(::sequenceNumber))
        val streamId = parsed(writer.packets.first()).streamId

        session.onPacket(FluxProtocol.buildSackPacket(streamId, ackNum = 1))
        runCurrent()

        assertEquals(listOf(0, 1, 2, 3), writer.packets.map(::sequenceNumber))
        session.onPacket(FluxProtocol.buildSackPacket(streamId, ackNum = 3))
        assertArrayEquals(ByteArray(55) { it.toByte() }, send.await())
    }

    @Test
    fun bitmapRetransmitsOnlyMissingFragment() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 40)
        val send = async { session.send(ByteArray(80) { it.toByte() }) }
        runCurrent()
        val streamId = parsed(writer.packets.first()).streamId
        val countBeforeSack = writer.packets.size
        val missing = ByteArray(16).also { it[0] = 0b0000_0010 }

        session.onPacket(FluxProtocol.buildSackPacket(streamId, 2, missing))
        runCurrent()

        assertEquals(countBeforeSack + 1, writer.packets.size)
        assertEquals(1, sequenceNumber(writer.packets.last()))
        send.cancelAndJoin()
    }

    @Test
    fun confirmedProgressDoesNotTreatFinalSequenceWithMissingBitmapAsComplete() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 40)
        val confirmed = mutableListOf<Int>()
        var starts = 0
        val send = async {
            session.send(ByteArray(80), onFirstPacketSent = { starts++ }, onAcknowledged = { bytes, _ -> confirmed += bytes })
        }
        runCurrent()
        val streamId = parsed(writer.packets.first()).streamId
        val missing = ByteArray(16).also { it[0] = 2 }
        session.onPacket(FluxProtocol.buildSackPacket(streamId, 2, missing))
        runCurrent()
        assertFalse(send.isCompleted)
        assertEquals(47, confirmed.last()) // 80 minus the missing 33-byte fragment
        assertEquals(1, starts)
        session.onPacket(FluxProtocol.buildSackPacket(streamId, 2))
        send.await()
        assertEquals(80, confirmed.last())
        assertEquals(1, starts)
    }

    @Test
    fun sendFailsAfterThreeRetries() = runTest {
        supervisorScope {
            val writer = FakeWriter()
            val send = async {
                ReliableFluxSession(this, writer, 247).send(byteArrayOf(9))
            }
            runCurrent()

            repeat(4) {
                advanceTimeBy(500)
                runCurrent()
            }

            assertEquals(4, writer.packets.size)
            assertSuspendFailsWith<FluxTransferException> { send.await() }
        }
    }

    @Test
    fun writerCancellationPropagatesAndReleasesSendSlot() = runTest {
        supervisorScope {
            var cancelFirstWrite = true
            val packets = mutableListOf<ByteArray>()
            val writer = FluxPacketWriter { packet ->
                if (cancelFirstWrite) {
                    cancelFirstWrite = false
                    throw CancellationException("writer cancelled")
                }
                packets += packet.copyOf()
            }
            val session = ReliableFluxSession(this, writer, 247)

            val cancelled = async { session.send(byteArrayOf(1)) }
            assertSuspendFailsWith<CancellationException> { cancelled.await() }

            val next = async { session.send(byteArrayOf(2)) }
            runCurrent()
            assertEquals(1, packets.size)
            val streamId = parsed(packets.single()).streamId
            session.onPacket(FluxProtocol.buildSackPacket(streamId, 0))
            assertArrayEquals(byteArrayOf(2), next.await())
        }
    }

    @Test
    fun timeoutRetransmitCancellationTerminatesSendAndReleasesPermit() = runTest {
        supervisorScope {
            var writes = 0
            val packets = mutableListOf<ByteArray>()
            val writer = FluxPacketWriter { packet ->
                writes++
                if (writes == 2) throw CancellationException("timeout retry cancelled")
                packets += packet.copyOf()
            }
            val session = ReliableFluxSession(this, writer, 247)
            val first = async { session.send(byteArrayOf(1)) }
            try {
                runCurrent()
                advanceTimeBy(500)
                runCurrent()

                assertTrue("Timeout cancellation must terminate send", first.isCompleted)
                assertSuspendFailsWith<CancellationException> { first.await() }

                val second = async { session.send(byteArrayOf(2)) }
                runCurrent()
                val streamId = parsed(packets.last()).streamId
                session.onPacket(FluxProtocol.buildSackPacket(streamId, 0))
                assertArrayEquals(byteArrayOf(2), second.await())
            } finally {
                first.cancelAndJoin()
                session.close()
            }
        }
    }

    @Test
    fun sackRetransmitCancellationTerminatesSendAndReleasesPermit() = runTest {
        supervisorScope {
            var writes = 0
            val packets = mutableListOf<ByteArray>()
            val writer = FluxPacketWriter { packet ->
                writes++
                if (writes == 4) throw CancellationException("SACK retry cancelled")
                packets += packet.copyOf()
            }
            val session = ReliableFluxSession(this, writer, mtu = 40)
            val first = async { session.send(ByteArray(80)) }
            try {
                runCurrent()
                val streamId = parsed(packets.first()).streamId
                val missing = ByteArray(16).also { it[0] = 0b0000_0010 }
                session.onPacket(FluxProtocol.buildSackPacket(streamId, 2, missing))
                runCurrent()

                assertTrue("SACK cancellation must terminate send", first.isCompleted)
                assertSuspendFailsWith<CancellationException> { first.await() }

                val second = async { session.send(byteArrayOf(2)) }
                runCurrent()
                val secondStreamId = parsed(packets.last()).streamId
                session.onPacket(FluxProtocol.buildSackPacket(secondStreamId, 0))
                assertArrayEquals(byteArrayOf(2), second.await())
            } finally {
                first.cancelAndJoin()
                session.close()
            }
        }
    }

    @Test
    fun closeUnblocksSendAndCancelsSuspendedWriter() = runTest {
        supervisorScope {
            val writeStarted = CompletableDeferred<Unit>()
            val writeCancelled = CompletableDeferred<Unit>()
            val writer = FluxPacketWriter {
                writeStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    writeCancelled.complete(Unit)
                }
            }
            val session = ReliableFluxSession(this, writer, 247)
            val send = async { session.send(byteArrayOf(1)) }
            try {
                runCurrent()
                writeStarted.await()
                val closeCause = FluxTransferException("closed by test")

                session.close(closeCause)
                runCurrent()

                assertTrue("close() must unblock send immediately", send.isCompleted)
                assertTrue("close() must cancel the active writer", writeCancelled.isCompleted)
                val failure = assertSuspendFailsWith<FluxTransferException> { send.await() }
                assertEquals(closeCause.message, failure.message)
            } finally {
                send.cancelAndJoin()
            }
        }
    }

    @Test
    fun twoConcurrentSendsUseDistinctRollingStreamIdsAndReleaseSlots() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, 247)
        val first = async { session.send(byteArrayOf(1)) }
        val second = async { session.send(byteArrayOf(2)) }
        val third = async { session.send(byteArrayOf(3)) }
        runCurrent()

        assertEquals(2, writer.packets.size)
        val firstStream = parsed(writer.packets[0]).streamId
        val secondStream = parsed(writer.packets[1]).streamId
        assertEquals(0, firstStream)
        assertEquals(1, secondStream)

        session.onPacket(FluxProtocol.buildSackPacket(firstStream, 0))
        runCurrent()

        assertArrayEquals(byteArrayOf(1), first.await())
        assertEquals(3, writer.packets.size)
        val thirdStream = parsed(writer.packets[2]).streamId
        assertEquals(2, thirdStream)

        session.onPacket(FluxProtocol.buildSackPacket(secondStream, 0))
        session.onPacket(FluxProtocol.buildSackPacket(thirdStream, 0))
        assertArrayEquals(byteArrayOf(2), second.await())
        assertArrayEquals(byteArrayOf(3), third.await())
    }

    @Test
    fun outOfOrderReceiveSendsMissingBitmapAndEmitsOneOrderedMessage() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 40)
        val expected = ByteArray(80) { it.toByte() }
        val packets = buildReliablePackets(expected, mtu = 40, streamId = 5, windowSize = 2)
        val received = async { session.incomingMessages.first() }
        runCurrent()

        session.onPacket(packets[0])
        session.onPacket(packets[2])
        runCurrent()

        val missingSack = parsed(writer.packets.single()) as FluxProtocol.FluxPacket.Sack
        assertEquals(2, missingSack.ackNum)
        assertEquals(16, missingSack.bitmapSize)
        assertEquals(0b0000_0010, missingSack.bitmap!![0].toInt())

        session.onPacket(packets[1])

        assertArrayEquals(expected, received.await())
        val finalSack = parsed(writer.packets.last()) as FluxProtocol.FluxPacket.Sack
        assertEquals(2, finalSack.ackNum)
        assertEquals(0, finalSack.bitmapSize)

        session.onPacket(packets[1])
        assertEquals(2, writer.packets.size)
    }

    @Test
    fun missingBitmapUsesSequenceModuloEightForBitPosition() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 23)
        val packets = buildReliablePackets(ByteArray(167), mtu = 23, streamId = 4, windowSize = 2)

        session.onPacket(packets[0])
        session.onPacket(packets[10])
        runCurrent()

        val sack = parsed(writer.packets.single()) as FluxProtocol.FluxPacket.Sack
        assertEquals(10, sack.ackNum)
        assertEquals(0xfe, sack.bitmap!![0].toInt() and 0xff)
        assertEquals(0x03, sack.bitmap[1].toInt() and 0xff)
        session.close()
    }

    @Test
    fun receiveExpiresAfterTenSeconds() = runTest {
        val writer = FakeWriter()
        val session = ReliableFluxSession(this, writer, mtu = 40)
        val packets = buildReliablePackets(ByteArray(80), mtu = 40, streamId = 3)
        val received = async { session.incomingMessages.first() }
        runCurrent()

        session.onPacket(packets[0])
        advanceTimeBy(10_000)
        runCurrent()
        session.onPacket(packets[1])
        runCurrent()

        assertFalse(received.isCompleted)
        assertTrue(writer.packets.isEmpty())
        received.cancelAndJoin()
    }

    @Test
    fun delayedMissingSackFiresWithoutCancellingItsOwnWrite() = runTest {
        val writer = FakeWriter()
        val yieldingWriter = FluxPacketWriter { packet ->
            yield()
            writer.write(packet)
        }
        val session = ReliableFluxSession(this, yieldingWriter, mtu = 40)
        val packets = buildReliablePackets(ByteArray(80), mtu = 40, streamId = 1)

        session.onPacket(packets[0])
        session.onPacket(packets[2])
        advanceTimeBy(99)
        runCurrent()
        assertTrue(writer.packets.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        val sack = parsed(writer.packets.single()) as FluxProtocol.FluxPacket.Sack
        assertEquals(2, sack.ackNum)
        assertEquals(0b0000_0010, sack.bitmap!![0].toInt())
        session.close()
    }

    @Test
    fun failedDelayedSackRemainsEligibleForRetry() = runTest {
        var attempts = 0
        val packetsWritten = mutableListOf<ByteArray>()
        val writer = FluxPacketWriter { packet ->
            attempts++
            if (attempts == 1) throw IllegalStateException("temporary failure")
            packetsWritten += packet.copyOf()
        }
        val session = ReliableFluxSession(this, writer, mtu = 40)
        val packets = buildReliablePackets(ByteArray(80), mtu = 40, streamId = 2)

        session.onPacket(packets[0])
        session.onPacket(packets[2])
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(packetsWritten.isEmpty())

        advanceTimeBy(100)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(1, packetsWritten.size)
        session.close()
    }

    @Test
    fun replacingStreamCancelsBlockedAckWithoutSendingStaleSack() = runTest {
        val firstAckStarted = CompletableDeferred<Unit>()
        val releaseFirstAck = CompletableDeferred<Unit>()
        val written = mutableListOf<ByteArray>()
        var ackAttempts = 0
        val writer = FluxPacketWriter { packet ->
            if (parsed(packet) is FluxProtocol.FluxPacket.Sack) {
                ackAttempts++
                if (ackAttempts == 1) {
                    firstAckStarted.complete(Unit)
                    releaseFirstAck.await()
                }
                written += packet.copyOf()
            }
        }
        val session = ReliableFluxSession(this, writer, mtu = 23)
        val packets = buildReliablePackets(
            ByteArray(71),
            mtu = 23,
            streamId = 4,
            windowSize = 2,
        )

        session.onPacket(packets[0])
        session.onPacket(packets[2])
        runCurrent()
        firstAckStarted.await()
        session.onPacket(packets[4])
        runCurrent()

        session.onPacket(packets[0])
        releaseFirstAck.complete(Unit)
        runCurrent()

        assertTrue("Replaced stream must not send an old SACK", written.isEmpty())
        session.close()
    }

    @Test
    fun blockedAckCoalescesLatestIntentUntilCooldown() = runTest {
        val firstAckStarted = CompletableDeferred<Unit>()
        val releaseFirstAck = CompletableDeferred<Unit>()
        val written = mutableListOf<ByteArray>()
        var ackAttempts = 0
        val writer = FluxPacketWriter { packet ->
            if (parsed(packet) is FluxProtocol.FluxPacket.Sack) {
                ackAttempts++
                if (ackAttempts == 1) {
                    firstAckStarted.complete(Unit)
                    releaseFirstAck.await()
                }
                written += packet.copyOf()
            }
        }
        val session = ReliableFluxSession(this, writer, mtu = 23)
        val packets = buildReliablePackets(
            ByteArray(71),
            mtu = 23,
            streamId = 5,
            windowSize = 2,
        )

        session.onPacket(packets[0])
        session.onPacket(packets[2])
        runCurrent()
        firstAckStarted.await()
        session.onPacket(packets[4])
        runCurrent()

        releaseFirstAck.complete(Unit)
        runCurrent()

        assertEquals(1, written.size)
        assertEquals(2, (parsed(written.single()) as FluxProtocol.FluxPacket.Sack).ackNum)

        advanceTimeBy(100)
        runCurrent()

        assertEquals(2, written.size)
        assertEquals(4, (parsed(written.last()) as FluxProtocol.FluxPacket.Sack).ackNum)
        session.close()
    }

    @Test
    fun cancelledLazyAckJobsDoNotAccumulateOwnership() = runTest {
        val session = ReliableFluxSession(this, FakeWriter(), mtu = 23)
        val packets = buildReliablePackets(
            ByteArray(71),
            mtu = 23,
            streamId = 6,
            windowSize = 2,
        )

        repeat(50) {
            session.onPacket(packets[0])
            session.onPacket(packets[2])
            session.onPacket(packets[0])
        }

        runCurrent()
        assertEquals(0, session.ownedControlJobCountForTest)
        session.close()
    }

    @Test
    fun closeCancelsSuspendedControlWriteAndUnblocksOnPacket() = runTest {
        supervisorScope {
            val writeStarted = CompletableDeferred<Unit>()
            val writeCancelled = CompletableDeferred<Unit>()
            val writer = FluxPacketWriter {
                writeStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    writeCancelled.complete(Unit)
                }
            }
            val session = ReliableFluxSession(this, writer, mtu = 40)
            val packets = buildReliablePackets(
                ByteArray(80),
                mtu = 40,
                streamId = 3,
                windowSize = 2,
            )
            session.onPacket(packets[0])
            val packetCall = async { session.onPacket(packets[2]) }
            try {
                runCurrent()
                writeStarted.await()

                session.close()
                runCurrent()

                assertTrue("close() must release onPacket", packetCall.isCompleted)
                assertTrue("close() must cancel control writer", writeCancelled.isCompleted)
            } finally {
                packetCall.cancelAndJoin()
            }
        }
    }

    @Test
    fun closeCancelsDeliveryBlockedBySlowIncomingConsumer() = runTest {
        val consumerGate = CompletableDeferred<Unit>()
        val firstReceived = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        val session = ReliableFluxSession(this, FakeWriter(), mtu = 247)
        val collector = launch {
            session.incomingMessages.collect { message ->
                received += message.single().toInt()
                firstReceived.complete(Unit)
                consumerGate.await()
            }
        }
        val calls = (1..4).map { value ->
            async {
                session.onPacket(
                    FluxProtocol.buildSinglePacket(payload = byteArrayOf(value.toByte())),
                )
            }
        }
        try {
            runCurrent()
            firstReceived.await()

            session.close()
            runCurrent()

            assertTrue("close() must unblock all onPacket calls", calls.all { it.isCompleted })
            consumerGate.complete(Unit)
            runCurrent()
            assertTrue("A suspended delivery must be cancelled", received.size < 4)
        } finally {
            consumerGate.complete(Unit)
            calls.forEach { it.cancelAndJoin() }
            collector.cancelAndJoin()
        }
    }

    @Test
    fun initialWindowAlwaysWritesStartBeforeFragments() = runTest {
        val dispatcher = GateFirstDispatcher()
        val ownedScope = CoroutineScope(SupervisorJob() + dispatcher)
        val writer = FakeWriter()
        val session = ReliableFluxSession(ownedScope, writer, mtu = 40)
        val send = launch {
            try {
                session.send(ByteArray(80))
            } catch (_: Throwable) {
                // The test closes the intentionally incomplete transfer.
            }
        }
        try {
            runCurrent()
            dispatcher.releaseFirst()

            assertTrue(writer.packets.isNotEmpty())
            assertTrue(parsed(writer.packets.first()) is FluxProtocol.FluxPacket.Start)
        } finally {
            session.close()
            send.cancelAndJoin()
            ownedScope.cancel()
        }
    }

    @Test
    fun rejectsPacketsWhoseDeclaredPayloadLengthDoesNotMatchBytes() = runTest {
        val session = ReliableFluxSession(this, FakeWriter(), mtu = 247)
        val malformed = FluxProtocol.buildFragmentPacket(0, 1, byteArrayOf(1, 2))
        malformed[2] = 3

        assertSuspendFailsWith<FluxTransferException> {
            session.onPacket(malformed)
        }
    }

    @Test
    fun rejectsMessagesThatNeedMoreThanBitmapCanRepresent() = runTest {
        val session = ReliableFluxSession(this, FakeWriter(), mtu = 23)

        assertSuspendFailsWith<IllegalArgumentException> {
            session.send(ByteArray(2_040))
        }
    }

    @Test
    fun singlePacketIsValidatedAndDeliveredWithoutChangingProtocolCodec() = runTest {
        val session = ReliableFluxSession(this, FakeWriter(), mtu = 247)
        val received = async { session.incomingMessages.first() }
        runCurrent()

        session.onPacket(FluxProtocol.buildSinglePacket(streamId = 7, payload = byteArrayOf(4, 5)))

        assertArrayEquals(byteArrayOf(4, 5), received.await())
    }

    @Test
    fun completedReceiveEmitsOnceWhenFinalSackWriteFails() = runTest {
        val received = mutableListOf<ByteArray>()
        val session = ReliableFluxSession(
            this,
            FluxPacketWriter { throw IllegalStateException("link down") },
            mtu = 40,
        )
        val collect = launch {
            session.incomingMessages.collect { received += it.copyOf() }
        }
        runCurrent()
        val expected = ByteArray(80) { it.toByte() }
        val packets = buildReliablePackets(expected, mtu = 40, streamId = 2, windowSize = 0xff)

        packets.forEach { session.onPacket(it) }
        session.onPacket(packets.last())
        runCurrent()

        assertEquals(1, received.size)
        assertArrayEquals(expected, received.single())
        collect.cancelAndJoin()
    }

    @Test
    fun completedReceiveEmitsOnceWhenFinalSackWriteIsCancelled() = runTest {
        val received = mutableListOf<ByteArray>()
        val session = ReliableFluxSession(
            this,
            FluxPacketWriter { throw CancellationException("control write cancelled") },
            mtu = 40,
        )
        val collect = launch {
            session.incomingMessages.collect { received += it.copyOf() }
        }
        runCurrent()
        val expected = ByteArray(80) { it.toByte() }
        val packets = buildReliablePackets(expected, mtu = 40, streamId = 3, windowSize = 0xff)

        packets.forEach { session.onPacket(it) }
        session.onPacket(packets.last())
        runCurrent()

        assertEquals(1, received.size)
        assertArrayEquals(expected, received.single())
        collect.cancelAndJoin()
    }

    private fun buildReliablePackets(
        data: ByteArray,
        mtu: Int,
        streamId: Int,
        windowSize: Int = 6,
    ): List<ByteArray> {
        val startPayloadSize = mtu - FluxProtocol.ATT_HEADER_SIZE - FluxProtocol.START_HEADER_SIZE
        val fragmentPayloadSize = mtu - FluxProtocol.ATT_HEADER_SIZE - FluxProtocol.FRAGMENT_HEADER_SIZE
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        val firstSize = minOf(startPayloadSize, data.size)
        chunks += data.copyOfRange(0, firstSize)
        offset += firstSize
        while (offset < data.size) {
            val end = minOf(offset + fragmentPayloadSize, data.size)
            chunks += data.copyOfRange(offset, end)
            offset = end
        }
        return buildList {
            add(
                FluxProtocol.buildStartPacket(
                    streamId = streamId,
                    windowSize = windowSize,
                    windowThresholdPercent = 50,
                    startPayload = chunks[0],
                    totalFrags = chunks.size,
                    totalSize = data.size.toLong(),
                ),
            )
            chunks.drop(1).forEachIndexed { index, chunk ->
                add(FluxProtocol.buildFragmentPacket(streamId, index + 1, chunk))
            }
        }
    }

    private fun parsed(bytes: ByteArray): FluxProtocol.FluxPacket =
        requireNotNull(FluxProtocol.parsePacket(bytes))

    private fun sequenceNumber(bytes: ByteArray): Int =
        when (val packet = parsed(bytes)) {
            is FluxProtocol.FluxPacket.Start -> 0
            is FluxProtocol.FluxPacket.Fragment -> packet.seqNum
            else -> error("Expected data packet, got $packet")
        }

    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }
}
