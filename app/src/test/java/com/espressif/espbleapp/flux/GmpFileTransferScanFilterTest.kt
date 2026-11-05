package com.espressif.espbleapp.flux

import com.espressif.espbleapp.gmp.GmpGattProfiles
import com.espressif.espbleapp.gmp.FtReason
import com.espressif.espbleapp.gmp.FtTransferState
import com.espressif.espbleapp.gmp.gmpMaxPayloadForFluxMessage
import com.espressif.espbleapp.gmp.transferStateAfterLinkTeardown
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmpFileTransferScanFilterTest {
    private val referenceServiceUuid =
        UUID.fromString("01000008-5e5c-6fa8-7a46-43d210598d7a")
    private val oldIncorrectUuid =
        UUID.fromString("0100085c-5e6f-6fa8-7a46-43d210598d7a")

    @Test
    fun serviceUuidMatchesReferenceReceiver() {
        assertEquals(referenceServiceUuid, GmpFileTransferUuids.SERVICE)
    }

    @Test
    fun filteredScanRequiresExactServiceUuid() {
        assertTrue(
            shouldIncludeBleScanResult(
                advertisedServiceUuids = listOf(referenceServiceUuid),
                targetServiceUuid = GmpFileTransferUuids.SERVICE,
            ),
        )
        assertFalse(
            shouldIncludeBleScanResult(
                advertisedServiceUuids = listOf(oldIncorrectUuid),
                targetServiceUuid = GmpFileTransferUuids.SERVICE,
            ),
        )
        assertFalse(
            shouldIncludeBleScanResult(
                advertisedServiceUuids = null,
                targetServiceUuid = GmpFileTransferUuids.SERVICE,
            ),
        )
    }

    @Test
    fun unfilteredScanAcceptsResultWithoutServiceUuid() {
        assertTrue(
            shouldIncludeBleScanResult(
                advertisedServiceUuids = null,
                targetServiceUuid = null,
            ),
        )
    }

    @Test
    fun gattUuidsMatchReceiverDemo() {
        assertEquals(UUID.fromString("01000008-5e5c-6fa8-7a46-43d210598d7a"), GmpFileTransferUuids.SERVICE)
        assertEquals(UUID.fromString("02000008-5e5c-6fa8-7a46-43d210598d7a"), GmpFileTransferUuids.RX)
        assertEquals(UUID.fromString("03000008-5e5c-6fa8-7a46-43d210598d7a"), GmpFileTransferUuids.TX)
    }

    @Test
    fun mtuMustSucceedAndMeetMinimum() {
        assertTrue(isFluxMtuAcceptable(minimumMtu = 185, status = 0, mtu = 185))
        assertTrue(isFluxMtuAcceptable(minimumMtu = 185, status = 0, mtu = 512))
        assertFalse(isFluxMtuAcceptable(minimumMtu = 185, status = 0, mtu = 184))
        assertFalse(isFluxMtuAcceptable(minimumMtu = 185, status = 133, mtu = 512))
    }

    @Test
    fun discoveryRequiresExactWritableRxAndNotifiableTx() {
        val accepted = evaluateFluxGattProfile(
                profile = GmpGattProfiles.FILE_TRANSFER,
            serviceFound = true,
            rxFound = true,
            rxProperties = 0x04 or 0x08,
            txFound = true,
            txProperties = 0x10,
            cccdFound = true,
        )
        assertEquals(null, accepted.failureMessage)

        assertTrue(
            evaluateFluxGattProfile(
                profile = GmpGattProfiles.FILE_TRANSFER,
                serviceFound = false,
                rxFound = true,
                rxProperties = 0x08,
                txFound = true,
                txProperties = 0x10,
                cccdFound = true,
            ).failureMessage!!.contains("Service"),
        )
        assertTrue(
            evaluateFluxGattProfile(
                profile = GmpGattProfiles.FILE_TRANSFER,
                serviceFound = true,
                rxFound = true,
                rxProperties = 0x02,
                txFound = true,
                txProperties = 0x10,
                cccdFound = true,
            ).failureMessage!!.contains("writable"),
        )
        assertTrue(
            evaluateFluxGattProfile(
                profile = GmpGattProfiles.FILE_TRANSFER,
                serviceFound = true,
                rxFound = true,
                rxProperties = 0x08,
                txFound = true,
                txProperties = 0x02,
                cccdFound = true,
            ).failureMessage!!.contains("notify"),
        )
        assertTrue(
            evaluateFluxGattProfile(
                profile = GmpGattProfiles.FILE_TRANSFER,
                serviceFound = true,
                rxFound = true,
                rxProperties = 0x08,
                txFound = true,
                txProperties = 0x10,
                cccdFound = false,
            ).failureMessage!!.contains("CCCD"),
        )
    }

    @Test
    fun maxGmpPayloadUsesFluxCapacityMinusHeader() {
        assertEquals(4096, gmpMaxPayloadForFluxMessage(5000))
        assertEquals(90, gmpMaxPayloadForFluxMessage(100))
    }

    @Test
    fun rxWritePrefersNoResponseWhenAvailable() {
        assertEquals(1, preferredRxWriteType(0x04))
        assertEquals(1, preferredRxWriteType(0x04 or 0x08))
        assertEquals(2, preferredRxWriteType(0x08))
    }

    @Test
    fun disconnectFailureMentionsCurrentStage() {
        assertEquals(
            "Disconnected while requesting MTU",
            fluxDisconnectFailureMessage(FluxConnectionStage.REQUESTING_MTU),
        )
        assertEquals(
            "Disconnected while discovering services",
            fluxDisconnectFailureMessage(FluxConnectionStage.DISCOVERING_SERVICES),
        )
        assertEquals(
            "Closed",
            fluxDisconnectFailureMessage(FluxConnectionStage.CLOSED),
        )
    }

    @Test
    fun staleCallbackIsNotAdoptedAfterReconnect() {
        val first = Any()
        val second = Any()
        val guard = FluxGattClientGuard()

        guard.beginConnect()
        assertTrue(guard.attachOpened(first).accepted)
        assertTrue(guard.isCurrent(first))

        guard.teardown("Disconnected")
        guard.beginConnect()

        assertFalse(guard.isCurrent(first))
        assertNull(guard.currentClient)
        assertTrue(guard.attachOpened(second).accepted)
        assertTrue(guard.isCurrent(second))
        assertFalse(guard.isCurrent(first))
    }

    @Test
    fun leftoverOpenedAfterStaleWindowIsClosed() {
        val stale = Any()
        val opened = Any()
        val extra = Any()
        val guard = FluxGattClientGuard()

        guard.beginConnect()
        assertTrue(guard.attachOpened(stale).accepted)
        guard.teardown("Disconnected")
        guard.beginConnect()
        assertFalse(guard.attachOpened(stale).accepted)
        assertTrue(guard.attachOpened(opened).accepted)
        assertFalse(guard.attachOpened(extra).accepted)
        assertTrue(guard.isCurrent(opened))
        assertFalse(guard.isCurrent(stale))
    }

    @Test
    fun lateWriteAndNotifyFromOldGattAreIgnored() {
        val old = Any()
        val current = Any()
        val guard = FluxGattClientGuard()

        guard.beginConnect()
        assertTrue(guard.attachOpened(old).accepted)
        guard.teardown("Disconnected")
        guard.beginConnect()
        assertTrue(guard.attachOpened(current).accepted)

        assertFalse(guard.acceptIo(old))
        assertTrue(guard.acceptIo(current))
    }

    @Test
    fun enterStageAfterFailOrCloseDoesNotReplaceFailed() {
        val guard = FluxGattClientGuard()
        guard.beginConnect()
        assertTrue(guard.enterStage(FluxConnectionStage.REQUESTING_MTU, FluxLinkState.RequestingMtu))

        guard.teardown("MTU 23 is below the required minimum 185")
        val failed = guard.published as FluxLinkState.Failed
        assertEquals("MTU 23 is below the required minimum 185", failed.message)

        assertFalse(guard.enterStage(FluxConnectionStage.DISCOVERING_SERVICES, FluxLinkState.DiscoveringServices))
        assertEquals(failed, guard.published)

        assertFalse(guard.enterStage(FluxConnectionStage.READY, FluxLinkState.Ready))
        assertEquals(failed.message, (guard.published as FluxLinkState.Failed).message)

        guard.teardown("Closed")
        assertFalse(guard.enterStage(FluxConnectionStage.READY, FluxLinkState.Ready))
        assertEquals("Closed", (guard.published as FluxLinkState.Failed).message)
    }

    @Test
    fun earlySameInstanceConnectedIsReplayedAfterAttach() {
        val opened = Any()
        val guard = FluxGattClientGuard()
        guard.beginConnect()

        assertEquals(
            FluxGattObserveDecision.BUFFERED,
            guard.observe(opened, FluxGattLinkEvent.CONNECTED),
        )
        assertNull(guard.currentClient)
        assertFalse(guard.isCurrent(opened))

        val attach = guard.attachOpened(opened)
        assertTrue(attach.accepted)
        assertEquals(FluxGattLinkEvent.CONNECTED, attach.replay)
        assertTrue(attach.close.isEmpty())
        assertTrue(guard.isCurrent(opened))
    }

    @Test
    fun earlyForeignGattIsDiscardedOnAttach() {
        val foreign = Any()
        val opened = Any()
        val guard = FluxGattClientGuard()
        guard.beginConnect()

        assertEquals(
            FluxGattObserveDecision.BUFFERED,
            guard.observe(foreign, FluxGattLinkEvent.CONNECTED),
        )

        val attach = guard.attachOpened(opened)
        assertTrue(attach.accepted)
        assertEquals(null, attach.replay)
        assertEquals(listOf(foreign), attach.close)
        assertFalse(guard.isCurrent(foreign))
        assertTrue(guard.isCurrent(opened))
        assertEquals(
            FluxGattObserveDecision.REJECTED,
            guard.observe(foreign, FluxGattLinkEvent.CONNECTED),
        )
    }

    @Test
    fun earlySameInstanceDisconnectedFailsConnect() {
        val opened = Any()
        val guard = FluxGattClientGuard()
        guard.beginConnect()

        assertEquals(
            FluxGattObserveDecision.BUFFERED,
            guard.observe(opened, FluxGattLinkEvent.DISCONNECTED),
        )

        val attach = guard.attachOpened(opened)
        assertTrue(attach.accepted)
        assertEquals(FluxGattLinkEvent.DISCONNECTED, attach.replay)
        assertEquals(
            "Disconnected while connecting",
            fluxDisconnectFailureMessage(guard.stage),
        )
    }

    @Test
    fun teardownPreservesTerminalTransferStateAndSurfacesLinkLoss() {
        assertEquals(
            FtTransferState.Idle,
            transferStateAfterLinkTeardown(FtTransferState.Idle),
        )
        val completed = FtTransferState.Completed(32, 10)
        assertEquals(completed, transferStateAfterLinkTeardown(completed))
        assertEquals(
            FtTransferState.Cancelled,
            transferStateAfterLinkTeardown(FtTransferState.Cancelled),
        )
        val failed = FtTransferState.Failed("Peer rejected transfer", FtReason.FILE_TOO_LARGE)
        assertEquals(failed, transferStateAfterLinkTeardown(failed))

        val lost = transferStateAfterLinkTeardown(FtTransferState.Sending(4, 32, 1)) as FtTransferState.Failed
        assertEquals("Link lost", lost.message)
        assertEquals(FtReason.LINK_ERROR, lost.peerReasonCode)
        assertTrue(transferStateAfterLinkTeardown(FtTransferState.Hashing(0, 8)) is FtTransferState.Failed)
        assertTrue(transferStateAfterLinkTeardown(FtTransferState.Negotiating) is FtTransferState.Failed)
        assertTrue(transferStateAfterLinkTeardown(FtTransferState.Verifying) is FtTransferState.Failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inboundQueueDeliversPacketsInOfferOrderEvenIfConsumerSuspends() = runTest {
        val seen = mutableListOf<Int>()
        val queue = FluxInboundPacketQueue()
        val gate = CompletableDeferred<Unit>()
        val consumer = launch {
            queue.collect { bytes ->
                seen += bytes[0].toInt()
                if (seen.size == 1) gate.await()
            }
        }

        queue.enqueue(byteArrayOf(1))
        queue.enqueue(byteArrayOf(2))
        runCurrent()
        assertEquals(listOf(1), seen)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2), seen)

        queue.close()
        consumer.cancel()
    }
}
