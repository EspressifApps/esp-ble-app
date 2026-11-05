package com.espressif.espbleapp.gmp

import com.espressif.espbleapp.flux.FluxMessageLink
import com.espressif.espbleapp.flux.FluxLinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

interface GmpMessageLink {
    val maxGmpPayload: Int
    /** Returns only after Flux has received all acknowledgements for this GMP frame. */
    suspend fun send(frame: ByteArray)
    suspend fun send(frame: ByteArray, onFirstPacketSent: (() -> Unit)?) {
        onFirstPacketSent?.invoke()
        send(frame)
    }
}

interface GmpRequestLink : GmpMessageLink {
    suspend fun request(frame: GmpFrame, onFirstPacketSent: (() -> Unit)? = null): GmpFrame
}

internal fun gmpMaxPayloadForFluxMessage(maxMessageBytes: Int): Int =
    minOf(4096, maxMessageBytes - 10)

/** Shared GMP framing and response matching above the reliable Flux message transport. */
class GmpClient(
    scope: CoroutineScope,
    private val flux: FluxMessageLink,
    private val log: (String) -> Unit = {},
) : GmpRequestLink {
    override val maxGmpPayload: Int get() = gmpMaxPayloadForFluxMessage(flux.maxMessageBytes)
    private data class ResponseKey(val op: GmpOperation, val group: Int, val sequence: Int, val command: Int)
    private val pending = ConcurrentHashMap<ResponseKey, CompletableDeferred<GmpFrame>>()
    private val frames = MutableSharedFlow<GmpFrame>(extraBufferCapacity = 16)
    val incomingFrames = frames.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flux.incomingMessages.collect { bytes ->
                val frame = try {
                    GmpFrameCodec.decode(bytes)
                } catch (e: IllegalArgumentException) {
                    log("Invalid GMP frame: ${e.message}")
                    return@collect
                }
                pending[ResponseKey(frame.operation, frame.groupId, frame.sequence, frame.commandId)]?.complete(frame)
                frames.emit(frame)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flux.linkState.collect { state ->
                if (state !is FluxLinkState.Ready) {
                    pending.values.forEach { it.completeExceptionally(IllegalStateException("GMP link lost")) }
                }
            }
        }
    }

    override suspend fun send(frame: ByteArray) = send(frame, null)

    override suspend fun send(frame: ByteArray, onFirstPacketSent: (() -> Unit)?) {
        val decoded = GmpFrameCodec.decode(frame)
        require(decoded.payload.size <= maxGmpPayload) { "GMP payload exceeds Flux message capacity" }
        flux.sendMessage(frame, onFirstPacketSent)
    }

    override suspend fun request(frame: GmpFrame, onFirstPacketSent: (() -> Unit)?): GmpFrame {
        val responseOp = when (frame.operation) {
            GmpOperation.READ_REQUEST -> GmpOperation.READ_RESPONSE
            GmpOperation.WRITE_REQUEST -> GmpOperation.WRITE_RESPONSE
            else -> error("GMP request must use a request operation")
        }
        check(flux.linkState.value is FluxLinkState.Ready) { "GMP link is not ready" }
        val key = ResponseKey(responseOp, frame.groupId, frame.sequence, frame.commandId)
        val response = CompletableDeferred<GmpFrame>()
        check(pending.putIfAbsent(key, response) == null) { "Duplicate GMP request sequence" }
        try {
            return withTimeout(20_000) {
                send(GmpFrameCodec.encode(frame), onFirstPacketSent)
                response.await().also {
                    check(it.status == GmpStatus.OK.code) {
                        "设备返回 GMP 错误状态 ${it.status}（seq=${frame.sequence}）"
                    }
                }
            }
        } finally {
            pending.remove(key, response)
            response.cancel()
        }
    }
}
