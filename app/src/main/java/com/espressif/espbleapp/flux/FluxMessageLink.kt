package com.espressif.espbleapp.flux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A reliable Flux message channel, independent of BLE addressing and application profiles. */
interface FluxMessageLink {
    val maxMessageBytes: Int
    val incomingMessages: Flow<ByteArray>
    val linkState: StateFlow<FluxLinkState>
    suspend fun sendMessage(
        bytes: ByteArray,
        onFirstPacketSent: (() -> Unit)? = null,
        onAcknowledged: ((Int, Long) -> Unit)? = null,
    )
}

internal fun fluxMaxMessageBytes(mtu: Int): Int {
    val startPayload = mtu - FluxProtocol.ATT_HEADER_SIZE - FluxProtocol.START_HEADER_SIZE
    val fragmentPayload = mtu - FluxProtocol.ATT_HEADER_SIZE - FluxProtocol.FRAGMENT_HEADER_SIZE
    val byFragments = startPayload + (128 - 1) * fragmentPayload
    return minOf(64 * 1024, byFragments)
}

