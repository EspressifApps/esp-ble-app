package com.espressif.espbleapp.flux

import android.bluetooth.BluetoothDevice

data class FluxDiscoveredDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
)

data class FluxReceivedMessage(
    val timestampMs: Long = System.currentTimeMillis(),
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FluxReceivedMessage
        return timestampMs == other.timestampMs && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

enum class FluxConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

data class FluxTransferProgress(
    val isSending: Boolean = false,
    val isWaitingForAck: Boolean = false,
    val ackReceived: Boolean = false,
    val transferredBytes: Int = 0,
    val confirmedBytes: Int = 0,
    val totalBytes: Int = 0,
    val progressPercent: Int = 0,
    val speedBps: Double = 0.0,
    val elapsedTimeMs: Long = 0L,
    val startedAtMs: Long = 0L,
    val failureMessage: String? = null,
)

