package com.espressif.espbleapp.gmp

import com.espressif.espbleapp.flux.FluxLinkState
import com.espressif.espbleapp.flux.FluxMessageLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GmpClientTest {
    private class FakeFlux : FluxMessageLink {
        override val maxMessageBytes = 4106
        override val incomingMessages = MutableSharedFlow<ByteArray>()
        override val linkState = MutableStateFlow<FluxLinkState>(FluxLinkState.Ready)
        val submitted = CompletableDeferred<Unit>()
        val allFluxAcks = CompletableDeferred<Unit>()
        override suspend fun sendMessage(bytes: ByteArray, onFirstPacketSent: (() -> Unit)?, onAcknowledged: ((Int, Long) -> Unit)?) {
            onFirstPacketSent?.invoke()
            submitted.complete(Unit)
            allFluxAcks.await()
        }
    }

    @Test
    fun requestNeedsBothAllFluxAcksAndMatchingGmpResponse() = runTest {
        val flux = FakeFlux()
        val client = GmpClient(backgroundScope, flux)
        val request = GmpFrame(GmpOperation.WRITE_REQUEST, 1, 42, 1, payload = byteArrayOf(7))
        val operation = async { client.request(request) }
        flux.submitted.await()
        val response = request.copy(operation = GmpOperation.WRITE_RESPONSE)
        flux.incomingMessages.emit(GmpFrameCodec.encode(response))
        runCurrent()
        assertFalse("GMP response alone cannot replace all Flux ACKs", operation.isCompleted)
        flux.allFluxAcks.complete(Unit)
        assertEquals(response, operation.await())
    }

    @Test
    fun responseMatchingRejectsWrongGroupSequenceAndOperation() = runTest {
        val flux = FakeFlux().also { it.allFluxAcks.complete(Unit) }
        val client = GmpClient(backgroundScope, flux)
        val request = GmpFrame(GmpOperation.WRITE_REQUEST, 1, 42, 1, payload = byteArrayOf())
        val operation = async { client.request(request) }
        flux.submitted.await()
        val expected = request.copy(operation = GmpOperation.WRITE_RESPONSE)
        for (other in listOf(expected.copy(groupId = 8), expected.copy(sequence = 41), expected.copy(commandId = 2), request)) {
            flux.incomingMessages.emit(GmpFrameCodec.encode(other))
            runCurrent()
            assertFalse(operation.isCompleted)
        }
        flux.incomingMessages.emit(GmpFrameCodec.encode(expected))
        assertEquals(expected, operation.await())
    }

    @Test
    fun disconnectFailsPendingGmpRequestWithoutWaitingForTimeout() = runTest {
        val flux = FakeFlux().also { it.allFluxAcks.complete(Unit) }
        val client = GmpClient(backgroundScope, flux)
        val operation = async {
            runCatching { client.request(GmpFrame(GmpOperation.READ_REQUEST, 0, 1, 1, payload = byteArrayOf())) }
        }
        flux.submitted.await()
        flux.linkState.value = FluxLinkState.Failed("Disconnected")
        runCurrent()
        assertTrue(operation.isCompleted)
        assertTrue(operation.await().isFailure)
        assertEquals(0L, testScheduler.currentTime)
    }
}
