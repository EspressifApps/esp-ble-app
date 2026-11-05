package com.espressif.espbleapp.flux

import kotlinx.coroutines.channels.Channel

sealed interface FluxLinkState {
    data object Idle : FluxLinkState
    data object Connecting : FluxLinkState
    data object RequestingMtu : FluxLinkState
    data object DiscoveringServices : FluxLinkState
    data object EnablingNotifications : FluxLinkState
    data object Ready : FluxLinkState
    data class Failed(val message: String) : FluxLinkState
}

enum class FluxConnectionStage {
    IDLE,
    CONNECTING,
    REQUESTING_MTU,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    READY,
    CLOSED,
}

internal data class FluxGattProfileCheck(
    val failureMessage: String?,
)

internal fun isFluxMtuAcceptable(status: Int, mtu: Int, minimumMtu: Int = 23): Boolean =
    status == GATT_SUCCESS && mtu >= minimumMtu

internal fun fluxMtuFailureMessage(status: Int, mtu: Int, minimumMtu: Int = 23): String =
    if (status != GATT_SUCCESS) {
        "MTU negotiation failed (status $status, mtu $mtu)"
    } else {
        "MTU $mtu is below the required minimum ${minimumMtu}"
    }

internal fun evaluateFluxGattProfile(
    serviceFound: Boolean,
    rxFound: Boolean,
    rxProperties: Int,
    txFound: Boolean,
    txProperties: Int,
    cccdFound: Boolean,
    profile: FluxGattProfile,
): FluxGattProfileCheck {
    val writable = rxProperties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0
    val notifiable = txProperties and PROPERTY_NOTIFY != 0
    val message = when {
        !serviceFound -> "Service ${profile.service} not found"
        !rxFound -> "RX characteristic ${profile.rx} not found"
        !writable -> "RX characteristic is not writable"
        !txFound -> "TX characteristic ${profile.tx} not found"
        !notifiable -> "TX characteristic does not support notify"
        !cccdFound -> "TX CCCD ${FluxUuids.CCCD} not found"
        else -> null
    }
    return FluxGattProfileCheck(message)
}

internal fun preferredRxWriteType(rxProperties: Int): Int =
    if (rxProperties and PROPERTY_WRITE_NO_RESPONSE != 0) {
        WRITE_TYPE_NO_RESPONSE
    } else {
        WRITE_TYPE_DEFAULT
    }

internal class FluxInboundPacketQueue {
    private val packets = Channel<ByteArray>(Channel.UNLIMITED)

    fun enqueue(bytes: ByteArray): Boolean = packets.trySend(bytes.copyOf()).isSuccess

    suspend fun collect(consume: suspend (ByteArray) -> Unit) {
        for (packet in packets) {
            consume(packet)
        }
    }

    fun close() {
        packets.close()
    }
}

internal fun fluxDisconnectFailureMessage(stage: FluxConnectionStage): String = when (stage) {
    FluxConnectionStage.CONNECTING -> "Disconnected while connecting"
    FluxConnectionStage.REQUESTING_MTU -> "Disconnected while requesting MTU"
    FluxConnectionStage.DISCOVERING_SERVICES -> "Disconnected while discovering services"
    FluxConnectionStage.ENABLING_NOTIFICATIONS -> "Disconnected while enabling notifications"
    FluxConnectionStage.CLOSED -> "Closed"
    FluxConnectionStage.IDLE,
    FluxConnectionStage.READY,
    -> "Disconnected"
}

internal enum class FluxGattLinkEvent {
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

internal enum class FluxGattObserveDecision {
    LIVE,
    BUFFERED,
    REJECTED,
}

internal data class FluxGattAttachResult(
    val accepted: Boolean,
    val replay: FluxGattLinkEvent? = null,
    val replayStatus: Int = 0,
    val close: List<Any> = emptyList(),
)

internal class FluxGattClientGuard {
    private val retired = java.util.IdentityHashMap<Any, Boolean>()
    private val buffer = mutableListOf<BufferedLinkEvent>()
    private var generation = 0

    var tornDown: Boolean = true
        private set
    var currentClient: Any? = null
        private set
    var stage: FluxConnectionStage = FluxConnectionStage.IDLE
        private set
    var published: FluxLinkState = FluxLinkState.Idle
        private set

    fun beginConnect() {
        generation++
        tornDown = false
        currentClient = null
        buffer.clear()
        stage = FluxConnectionStage.CONNECTING
        published = FluxLinkState.Connecting
    }

    fun observe(callbackClient: Any, event: FluxGattLinkEvent, status: Int = 0): FluxGattObserveDecision {
        if (tornDown || retired.containsKey(callbackClient)) return FluxGattObserveDecision.REJECTED
        val current = currentClient
        if (current != null) {
            return if (current === callbackClient) {
                FluxGattObserveDecision.LIVE
            } else {
                FluxGattObserveDecision.REJECTED
            }
        }
        buffer += BufferedLinkEvent(generation, callbackClient, event, status)
        return FluxGattObserveDecision.BUFFERED
    }

    fun attachOpened(opened: Any?): FluxGattAttachResult {
        val pending = buffer.toList()
        buffer.clear()
        if (tornDown || opened == null || retired.containsKey(opened)) {
            val close = distinctClients(pending)
            retireAll(close)
            return FluxGattAttachResult(accepted = false, close = close)
        }
        val current = currentClient
        if (current != null && current !== opened) {
            val close = buildList {
                add(opened)
                addAll(distinctClients(pending))
            }
            retireAll(close)
            return FluxGattAttachResult(accepted = false, close = close)
        }
        currentClient = opened
        val mine = pending.filter { it.generation == generation && it.client === opened }
        val foreign = distinctClients(pending.filter { it.client !== opened })
        retireAll(foreign)
        return FluxGattAttachResult(
            accepted = true,
            replay = replayEvent(mine),
            replayStatus = replayStatus(mine),
            close = foreign,
        )
    }

    fun isCurrent(callbackClient: Any): Boolean {
        if (tornDown || retired.containsKey(callbackClient)) return false
        val current = currentClient
        return current != null && current === callbackClient
    }

    fun acceptIo(callbackClient: Any): Boolean = isCurrent(callbackClient)

    fun enterStage(next: FluxConnectionStage, state: FluxLinkState): Boolean {
        if (tornDown) return false
        stage = next
        published = state
        return true
    }

    fun teardown(failureMessage: String?): List<Any> {
        val leftover = distinctClients(buffer)
        buffer.clear()
        leftover.forEach { retired[it] = true }
        if (tornDown) {
            if (failureMessage == "Closed") {
                stage = FluxConnectionStage.CLOSED
                published = FluxLinkState.Failed(failureMessage)
            }
            return leftover
        }
        tornDown = true
        currentClient?.let { retired[it] = true }
        currentClient = null
        if (failureMessage == "Closed") {
            stage = FluxConnectionStage.CLOSED
        }
        if (failureMessage != null) {
            published = FluxLinkState.Failed(failureMessage)
        }
        return leftover
    }

    private fun replayEvent(mine: List<BufferedLinkEvent>): FluxGattLinkEvent? = when {
        mine.any { it.event == FluxGattLinkEvent.DISCONNECTED } -> FluxGattLinkEvent.DISCONNECTED
        mine.any { it.event == FluxGattLinkEvent.FAILED } -> FluxGattLinkEvent.FAILED
        mine.any { it.event == FluxGattLinkEvent.CONNECTED } -> FluxGattLinkEvent.CONNECTED
        else -> null
    }

    private fun replayStatus(mine: List<BufferedLinkEvent>): Int =
        mine.lastOrNull { it.event == replayEvent(mine) }?.status ?: 0

    private fun retireAll(clients: Iterable<Any>) {
        clients.forEach { retired[it] = true }
    }

    private fun distinctClients(events: List<BufferedLinkEvent>): List<Any> =
        events.map { it.client }.distinct()

    private data class BufferedLinkEvent(
        val generation: Int,
        val client: Any,
        val event: FluxGattLinkEvent,
        val status: Int,
    )
}

internal const val GATT_SUCCESS = 0
internal const val PROPERTY_WRITE_NO_RESPONSE = 0x04
internal const val PROPERTY_WRITE = 0x08
internal const val PROPERTY_NOTIFY = 0x10
internal const val WRITE_TYPE_NO_RESPONSE = 1
internal const val WRITE_TYPE_DEFAULT = 2
