package com.espressif.espbleapp.gmp

import com.espressif.espbleapp.flux.FluxPacketWriter
import com.espressif.espbleapp.flux.FluxProtocol
import com.espressif.espbleapp.flux.ReliableFluxSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class GmpFileTransferSenderTest {
    @Test
    fun acceptedMetaSendsOneDataBlockAtATimeAndValidatesFinal() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        assertEquals(1, link.unansweredDataFrames.size)
        assertEquals(1, source.openCount)
        assertEquals(0L, readU32(link.unansweredDataFrames.single().payload, 4))
        advanceTimeBy(100)
        runCurrent()
        assertEquals("No later block before the current block ACK", 1, link.unansweredDataFrames.size)

        link.respondToData(sender, link.unansweredDataFrames.first())
        runCurrent()
        assertEquals(1, link.unansweredDataFrames.size)
        assertEquals(1L, readU32(link.unansweredDataFrames.single().payload, 4))

        link.respondToAllData(sender)
        runCurrent()
        link.sendMatchingFinal(sender, source)
        job.join()
        assertTrue(sender.state.value.toString(), sender.state.value is FtTransferState.Completed)
        assertEquals(1, source.openCount)
    }

    @Test
    fun transferTimingEndsAtLastDataAckBeforeFinalVerification() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(5000))
        val sender = GmpFileTransferSender(this, link, clockMs = { testScheduler.currentTime + 1 })
        val job = launch { sender.send(source) }
        runCurrent()
        advanceTimeBy(2_000)
        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        assertEquals(2_001L, (sender.state.value as FtTransferState.Sending).startedAtMs)
        advanceTimeBy(100)
        link.respondToData(sender, link.unansweredDataFrames.single())
        runCurrent()
        advanceTimeBy(150)
        link.respondToData(sender, link.unansweredDataFrames.single())
        runCurrent()
        assertTrue(sender.state.value is FtTransferState.Verifying)
        advanceTimeBy(4_000)
        link.sendMatchingFinal(sender, source)
        job.join()
        assertEquals(250L, (sender.state.value as FtTransferState.Completed).elapsedMs)
    }

    @Test
    fun finalDataTimingWaitsForFluxAckWhenGmpResponseArrivesFirst() = runTest {
        val peer = FakeGmpLink()
        lateinit var sender: GmpFileTransferSender
        val link = object : GmpMessageLink {
            override val maxGmpPayload = 4096
            override suspend fun send(frame: ByteArray) {
                peer.send(frame)
                val decoded = GmpFrameCodec.decode(frame)
                if (decoded.commandId == FtCommand.DATA) {
                    delay(50)
                    peer.respondToData(sender, decoded)
                    delay(150) // Last Flux ACK arrives after the GMP response.
                }
            }
        }
        val source = ByteArrayFileSource("test.bin", ByteArray(100))
        sender = GmpFileTransferSender(this, link, clockMs = { testScheduler.currentTime + 1 })
        val job = launch { sender.send(source) }
        peer.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(sender.state.value is FtTransferState.Verifying)
        peer.sendMatchingFinal(sender, source)
        job.join()
        assertEquals(200L, (sender.state.value as FtTransferState.Completed).elapsedMs)
    }

    @Test
    fun fileBlocksStayOrderedOverFluxWithALostFragment() = runTest {
        lateinit var phone: ReliableFluxSession
        lateinit var device: ReliableFluxSession
        var dropped = false
        phone = ReliableFluxSession(backgroundScope, FluxPacketWriter { packet ->
            val parsed = FluxProtocol.parsePacket(packet)
            if (!dropped && parsed is FluxProtocol.FluxPacket.Fragment && parsed.seqNum == 1) {
                dropped = true
            } else {
                device.onPacket(packet)
            }
        }, mtu = 247)
        device = ReliableFluxSession(backgroundScope, FluxPacketWriter { phone.onPacket(it) }, mtu = 247)
        val link = object : GmpMessageLink {
            override val maxGmpPayload = 4096
            override suspend fun send(frame: ByteArray) { phone.send(frame) }
        }
        val data = ByteArray(9000) { it.toByte() }
        val source = ByteArrayFileSource("test.bin", data)
        val sender = GmpFileTransferSender(this, link)
        val received = java.io.ByteArrayOutputStream()
        var expectedBlock = 0L
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            phone.incomingMessages.collect { sender.onIncoming(it) }
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            device.incomingMessages.collect { bytes ->
                val frame = GmpFrameCodec.decode(bytes)
                val transferId = readU32(frame.payload, 0)
                when (frame.commandId) {
                    FtCommand.META -> device.send(encodeResponse(frame, encodeMetaResponse(transferId, 0, 0, 4084)))
                    FtCommand.DATA -> {
                        assertEquals("Receiver requires consecutive block indices", expectedBlock, readU32(frame.payload, 4))
                        expectedBlock++
                        received.write(frame.payload, 12, frame.payload.size - 12)
                        device.send(encodeResponse(frame, encodeDataResponse(transferId, expectedBlock - 1)))
                        if (received.size() == data.size) {
                            device.send(GmpFrameCodec.encode(GmpFrame(
                                GmpOperation.WRITE_REQUEST, 8, 0x7FFE, FtCommand.FINAL,
                                payload = encodeFinalConfirm(transferId, 0, 0, data.size.toLong(), source.sha256Sync(), source.displayName),
                            )))
                        }
                    }
                }
            }
        }
        try {
            sender.send(source)
            assertTrue(sender.state.value.toString(), sender.state.value is FtTransferState.Completed)
            assertTrue(dropped)
            assertEquals(3L, expectedBlock)
            assertArrayEquals(data, received.toByteArray())
        } finally {
            phone.close()
            device.close()
        }
    }

    @Test
    fun metaRejectionReportsPeerReasonAndLeavesLinkWithoutAbort() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("big.bin", ByteArray(32) { it.toByte() })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(
            sender,
            acceptedBlockSize = 0,
            status = FtWireStatus.REJECTED,
            reasonCode = FtReason.FILE_TOO_LARGE,
        )
        job.join()

        val failed = sender.state.value as FtTransferState.Failed
        assertEquals(FtReason.FILE_TOO_LARGE, failed.peerReasonCode)
        assertTrue(link.sent.none { it.commandId == FtCommand.ABORT })
    }

    @Test
    fun zeroByteTransferSkipsDataAndCompletesOnMatchingFinal() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("empty.bin", ByteArray(0))
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        assertTrue(link.unansweredDataFrames.isEmpty())
        assertEquals(0, source.openCount)

        link.sendMatchingFinal(sender, source)
        job.join()
        assertTrue(sender.state.value.toString(), sender.state.value is FtTransferState.Completed)
        assertEquals(0L, (sender.state.value as FtTransferState.Completed).bytes)
    }

    @Test
    fun gmpBusyResendsSameBlockBytesUnderNewSequence() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        val first = link.unansweredDataFrames.first()
        val firstIndex = readU32(first.payload, 4)
        val firstBytes = first.payload.copyOfRange(12, first.payload.size)

        link.respondToData(sender, first, busy = true)
        runCurrent()

        val resent = link.unansweredDataFrames.first { readU32(it.payload, 4) == firstIndex }
        assertTrue(resent.sequence != first.sequence)
        assertArrayEquals(firstBytes, resent.payload.copyOfRange(12, resent.payload.size))
        assertEquals(1, link.unansweredDataFrames.size)

        link.respondToAllData(sender)
        runCurrent()
        link.sendMatchingFinal(sender, source)
        job.join()
        assertTrue(sender.state.value.toString(), sender.state.value is FtTransferState.Completed)
    }

    @Test
    fun progressCountsOnlyConfirmedBytes() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        val sending = sender.state.value as FtTransferState.Sending
        assertEquals(0L, sending.confirmedBytes)
        assertEquals(9000L, sending.totalBytes)

        val first = link.unansweredDataFrames.first()
        val firstLen = first.payload.size - 12L
        link.respondToData(sender, first)
        runCurrent()
        assertEquals(firstLen, (sender.state.value as FtTransferState.Sending).confirmedBytes)

        link.respondToAllData(sender)
        runCurrent()
        val afterData = sender.state.value
        if (afterData is FtTransferState.Sending) {
            assertEquals(9000L, afterData.confirmedBytes)
        } else {
            assertTrue(afterData is FtTransferState.Verifying)
        }

        link.sendMatchingFinal(sender, source)
        job.join()
        assertEquals(9000L, (sender.state.value as FtTransferState.Completed).bytes)
    }

    @Test
    fun metaTimesOutAfterTenSeconds() = runTest {
        val link = FakeGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(8))) }

        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(job.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        job.join()
        val failed = sender.state.value as FtTransferState.Failed
        assertEquals(FtReason.TIMEOUT, failed.peerReasonCode)
        assertTrue(link.sent.any { it.commandId == FtCommand.ABORT })
    }

    @Test
    fun dataTimesOutAfterTenSeconds() = runTest {
        val link = FakeGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(job.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertEquals(FtReason.TIMEOUT, (sender.state.value as FtTransferState.Failed).peerReasonCode)
    }

    @Test
    fun finalTimesOutAfterTwentySeconds() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("empty.bin", ByteArray(0))
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        advanceTimeBy(19_999)
        runCurrent()
        assertFalse(job.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertEquals(FtReason.TIMEOUT, (sender.state.value as FtTransferState.Failed).peerReasonCode)
    }

    @Test
    fun mismatchedFinalSizeFailsTransfer() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("empty.bin", ByteArray(0))
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        link.sendFinal(sender, source, receivedSize = 1, sha256 = source.sha256Sync())
        job.join()
        assertTrue(sender.state.value is FtTransferState.Failed)
    }

    @Test
    fun mismatchedFinalHashFailsTransfer() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("empty.bin", ByteArray(0))
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        link.sendFinal(sender, source, receivedSize = 0, sha256 = ByteArray(32) { 0x5A })
        job.join()
        assertEquals(FtReason.HASH_MISMATCH, (sender.state.value as FtTransferState.Failed).peerReasonCode)
    }

    @Test
    fun userCancelSendsAbortReasonAborted() = runTest {
        val link = FakeGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        sender.cancel()
        job.join()

        assertEquals(FtTransferState.Cancelled, sender.state.value)
        val abort = link.sent.last { it.commandId == FtCommand.ABORT }
        assertEquals(GmpOperation.WRITE_REQUEST, abort.operation)
        assertEquals(FtReason.ABORTED, GmpFileTransferCodec.decodeAbort(abort.payload).reasonCode)
    }

    @Test
    fun linkLossFailsWithoutSendingAbort() = runTest {
        val link = FakeGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        sender.onLinkLost()
        job.join()

        val failed = sender.state.value as FtTransferState.Failed
        assertEquals(FtReason.LINK_ERROR, failed.peerReasonCode)
        assertTrue(link.sent.none { it.commandId == FtCommand.ABORT })
    }

    @Test
    fun lateIncomingAfterCancelDoesNotRestartTransfer() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        val first = link.unansweredDataFrames.first()
        sender.cancel()
        job.join()
        link.respondToData(sender, first)
        link.sendMatchingFinal(sender, source)
        runCurrent()

        assertEquals(FtTransferState.Cancelled, sender.state.value)
    }

    @Test
    fun secondTransferRunsAfterAnyTerminalState() = runTest {
        val link = FakeGmpLink()
        val sender = GmpFileTransferSender(this, link)

        val cancelled = ByteArrayFileSource("a.bin", ByteArray(64) { 1 })
        val cancelJob = launch { sender.send(cancelled) }
        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        sender.cancel()
        cancelJob.join()
        assertEquals(FtTransferState.Cancelled, sender.state.value)

        val failed = ByteArrayFileSource("b.bin", ByteArray(64) { 2 })
        val failJob = launch { sender.send(failed) }
        link.respondToMeta(
            sender,
            acceptedBlockSize = 0,
            status = FtWireStatus.REJECTED,
            reasonCode = FtReason.NO_SPACE,
        )
        failJob.join()
        assertTrue(sender.state.value is FtTransferState.Failed)

        val ok = ByteArrayFileSource("c.bin", ByteArray(8) { 3 })
        val okJob = launch { sender.send(ok) }
        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        link.respondToAllData(sender)
        runCurrent()
        link.sendMatchingFinal(sender, ok)
        okJob.join()
        assertTrue(sender.state.value.toString(), sender.state.value is FtTransferState.Completed)
        assertEquals(1, ok.openCount)
    }

    @Test
    fun acceptedSmallerBlockSizeIsRecomputedBeforeData() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(9000) { it.toByte() })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 2042)
        runCurrent()
        val first = link.unansweredDataFrames.first()
        assertEquals(2042, first.payload.size - 12)
        assertEquals(1, link.unansweredDataFrames.size)

        link.respondToAllData(sender)
        runCurrent()
        link.sendMatchingFinal(sender, source)
        job.join()
        assertTrue(sender.state.value.toString(), sender.state.value is FtTransferState.Completed)
        assertEquals(5, link.sent.count { it.commandId == FtCommand.DATA })
    }

    @Test
    fun cancelDuringSlowHashDoesNotSendMeta() = runTest {
        val link = FakeGmpLink()
        val source = SlowHashFileSource("test.bin", ByteArray(8), hashDelayMs = 5_000)
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        runCurrent()
        sender.cancel()
        runCurrent()

        assertTrue(job.isCompleted)
        job.join()
        assertEquals(FtTransferState.Cancelled, sender.state.value)
        assertTrue(link.sent.none { it.commandId == FtCommand.META })
        assertTrue(link.sent.none { it.commandId == FtCommand.ABORT })
    }

    @Test
    fun linkLossDuringSlowHashDoesNotSendMeta() = runTest {
        val link = FakeGmpLink()
        val source = SlowHashFileSource("test.bin", ByteArray(8), hashDelayMs = 5_000)
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        runCurrent()
        sender.onLinkLost()
        runCurrent()

        assertTrue(job.isCompleted)
        job.join()
        val failed = sender.state.value as FtTransferState.Failed
        assertEquals(FtReason.LINK_ERROR, failed.peerReasonCode)
        assertTrue(link.sent.none { it.commandId == FtCommand.META })
        assertTrue(link.sent.none { it.commandId == FtCommand.ABORT })
    }

    @Test
    fun openCancellationIsRethrownInsteadOfFileOpenFailed() = runTest {
        val link = FakeGmpLink()
        val source = object : ByteArrayFileSource("test.bin", ByteArray(8)) {
            override suspend fun open(): InputStream {
                throw CancellationException("open cancelled")
            }
        }
        val sender = GmpFileTransferSender(this, link)
        val send = async { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        val result = runCatching { send.await() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(FtTransferState.Cancelled, sender.state.value)
        assertTrue(link.sent.none { it.commandId == FtCommand.ABORT && it.payload.size == 8 &&
            GmpFileTransferCodec.decodeAbort(it.payload).reasonCode == FtReason.FILE_OPEN_FAILED })
    }

    @Test
    fun peerAbortWriteRequestFailsWithoutOutgoingAbort() = runTest {
        val link = FakeGmpLink()
        val source = ByteArrayFileSource("test.bin", ByteArray(64) { 1 })
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(source) }

        link.respondToMeta(sender, acceptedBlockSize = 4084)
        runCurrent()
        val transferId = readU32(link.sent.last { it.commandId == FtCommand.META }.payload, 0)
        sender.onIncoming(
            GmpFrameCodec.encode(
                GmpFrame(
                    operation = GmpOperation.WRITE_REQUEST,
                    groupId = GmpFrameCodec.FILE_TRANSFER_GROUP_ID,
                    sequence = 0x1234,
                    commandId = FtCommand.ABORT,
                    payload = GmpFileTransferCodec.encodeAbort(FtAbort(transferId, FtReason.ABORTED)),
                ),
            ),
        )
        job.join()

        val failed = sender.state.value as FtTransferState.Failed
        assertEquals(FtReason.ABORTED, failed.peerReasonCode)
        assertTrue(link.sent.none { it.commandId == FtCommand.ABORT })
    }

    @Test
    fun cancelInterruptsInFlightLinkSend() = runTest {
        val link = HangingGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(8))) }

        link.firstSend.await()
        runCurrent()
        sender.cancel()
        runCurrent()

        assertTrue(job.isCompleted)
        job.join()
        assertEquals(FtTransferState.Cancelled, sender.state.value)
    }

    @Test
    fun metaTimeoutInterruptsInFlightLinkSend() = runTest {
        val link = HangingGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(8))) }

        link.firstSend.await()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(job.isCompleted)
        job.join()
        val failed = sender.state.value as FtTransferState.Failed
        assertEquals("Transfer timed out", failed.message)
        assertEquals(FtReason.TIMEOUT, failed.peerReasonCode)
    }

    @Test
    fun linkLossInterruptsInFlightLinkSend() = runTest {
        val link = HangingGmpLink()
        val sender = GmpFileTransferSender(this, link)
        val job = launch { sender.send(ByteArrayFileSource("test.bin", ByteArray(8))) }

        link.firstSend.await()
        runCurrent()
        sender.onLinkLost()
        runCurrent()

        assertTrue(job.isCompleted)
        job.join()
        val failed = sender.state.value as FtTransferState.Failed
        assertEquals("Link lost", failed.message)
        assertEquals(FtReason.LINK_ERROR, failed.peerReasonCode)
    }
}

private class HangingGmpLink : GmpMessageLink {
    override val maxGmpPayload: Int = 4096
    val firstSend = CompletableDeferred<Unit>()

    override suspend fun send(frame: ByteArray) {
        firstSend.complete(Unit)
        awaitCancellation()
    }
}

private class SlowHashFileSource(
    displayName: String,
    bytes: ByteArray,
    private val hashDelayMs: Long,
) : ByteArrayFileSource(displayName, bytes) {
    override suspend fun sha256(): ByteArray {
        delay(hashDelayMs)
        return sha256Sync()
    }
}

open class ByteArrayFileSource(
    override val displayName: String,
    val bytes: ByteArray,
) : FtFileSource {
    override val size: Long = bytes.size.toLong()
    var openCount = 0
        private set

    override suspend fun sha256(): ByteArray = sha256Sync()

    override suspend fun open(): InputStream {
        openCount++
        return ByteArrayInputStream(bytes)
    }

    fun sha256Sync(): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

class FakeGmpLink(
    override val maxGmpPayload: Int = 4096,
) : GmpMessageLink {
    val sent = mutableListOf<GmpFrame>()
    private val answered = mutableSetOf<Int>()
    private var pendingMeta: (() -> Unit)? = null
    private var autoAckData = false
    private var autoAckSender: GmpFileTransferSender? = null

    val unansweredDataFrames: List<GmpFrame>
        get() = sent.filter { it.commandId == FtCommand.DATA && it.sequence !in answered }

    override suspend fun send(frame: ByteArray) {
        val decoded = GmpFrameCodec.decode(frame)
        sent += decoded
        if (decoded.commandId == FtCommand.META) {
            autoAckData = false
            autoAckSender = null
            pendingMeta?.also {
                pendingMeta = null
                it()
            }
        }
        if (autoAckData && decoded.commandId == FtCommand.DATA) {
            autoAckSender?.let { respondToData(it, decoded) }
        }
    }

    fun respondToMeta(
        sender: GmpFileTransferSender,
        acceptedBlockSize: Int,
        status: Int = FtWireStatus.OK,
        reasonCode: Int = FtReason.OK,
    ) {
        val apply = {
            val meta = sent.last { it.commandId == FtCommand.META && it.sequence !in answered }
            answered += meta.sequence
            sender.onIncoming(
                encodeResponse(
                    request = meta,
                    payload = encodeMetaResponse(
                        transferId = readU32(meta.payload, 0),
                        status = status,
                        reasonCode = reasonCode,
                        acceptedBlockSize = acceptedBlockSize,
                    ),
                ),
            )
        }
        if (sent.any { it.commandId == FtCommand.META && it.sequence !in answered }) {
            apply()
        } else {
            pendingMeta = apply
        }
    }

    fun respondToData(
        sender: GmpFileTransferSender,
        frame: GmpFrame,
        busy: Boolean = false,
    ) {
        answered += frame.sequence
        val payload = if (busy) {
            ByteArray(0)
        } else {
            encodeDataResponse(
                transferId = readU32(frame.payload, 0),
                blockIndex = readU32(frame.payload, 4),
            )
        }
        sender.onIncoming(
            encodeResponse(
                request = frame,
                payload = payload,
                gmpStatus = if (busy) GmpStatus.BUSY.code else GmpStatus.OK.code,
            ),
        )
    }

    fun respondToAllData(sender: GmpFileTransferSender) {
        autoAckData = true
        autoAckSender = sender
        while (unansweredDataFrames.isNotEmpty()) {
            respondToData(sender, unansweredDataFrames.first())
        }
    }

    fun sendMatchingFinal(sender: GmpFileTransferSender, source: ByteArrayFileSource) {
        sendFinal(sender, source, source.size, source.sha256Sync())
    }

    fun sendFinal(
        sender: GmpFileTransferSender,
        source: ByteArrayFileSource,
        receivedSize: Long,
        sha256: ByteArray,
    ) {
        val transferId = readU32(sent.last { it.commandId == FtCommand.META }.payload, 0)
        sender.onIncoming(
            GmpFrameCodec.encode(
                GmpFrame(
                    operation = GmpOperation.WRITE_REQUEST,
                    groupId = GmpFrameCodec.FILE_TRANSFER_GROUP_ID,
                    sequence = 0x7FFE,
                    commandId = FtCommand.FINAL,
                    payload = encodeFinalConfirm(
                        transferId = transferId,
                        status = FtWireStatus.OK,
                        reasonCode = FtReason.OK,
                        receivedSize = receivedSize,
                        sha256 = sha256,
                        savedName = source.displayName,
                    ),
                ),
            ),
        )
    }
}

private fun encodeResponse(
    request: GmpFrame,
    payload: ByteArray,
    gmpStatus: Int = GmpStatus.OK.code,
): ByteArray = GmpFrameCodec.encode(
    GmpFrame(
        operation = GmpOperation.WRITE_RESPONSE,
        groupId = request.groupId,
        sequence = request.sequence,
        commandId = request.commandId,
        status = gmpStatus,
        payload = payload,
    ),
)

private fun encodeMetaResponse(
    transferId: Long,
    status: Int,
    reasonCode: Int,
    acceptedBlockSize: Int,
): ByteArray {
    val out = ByteArray(12)
    putU32(out, 0, transferId)
    out[4] = status.toByte()
    putU16(out, 6, reasonCode)
    putU32(out, 8, acceptedBlockSize.toLong())
    return out
}

private fun encodeDataResponse(transferId: Long, blockIndex: Long): ByteArray {
    val out = ByteArray(12)
    putU32(out, 0, transferId)
    putU32(out, 4, blockIndex)
    return out
}

private fun encodeFinalConfirm(
    transferId: Long,
    status: Int,
    reasonCode: Int,
    receivedSize: Long,
    sha256: ByteArray,
    savedName: String,
): ByteArray {
    val name = savedName.toByteArray(Charsets.UTF_8)
    val out = ByteArray(48 + name.size)
    putU32(out, 0, transferId)
    out[4] = status.toByte()
    out[5] = name.size.toByte()
    putU16(out, 6, reasonCode)
    putU32(out, 8, receivedSize ushr 32)
    putU32(out, 12, receivedSize and 0xFFFF_FFFFL)
    sha256.copyInto(out, 16)
    name.copyInto(out, 48)
    return out
}

private fun putU16(out: ByteArray, at: Int, value: Int) {
    out[at] = (value ushr 8).toByte()
    out[at + 1] = value.toByte()
}

private fun putU32(out: ByteArray, at: Int, value: Long) {
    out[at] = (value ushr 24).toByte()
    out[at + 1] = (value ushr 16).toByte()
    out[at + 2] = (value ushr 8).toByte()
    out[at + 3] = value.toByte()
}

private fun readU32(input: ByteArray, at: Int): Long =
    ((input[at].toLong() and 0xFF) shl 24) or
        ((input[at + 1].toLong() and 0xFF) shl 16) or
        ((input[at + 2].toLong() and 0xFF) shl 8) or
        (input[at + 3].toLong() and 0xFF)
