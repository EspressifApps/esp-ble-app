package com.espressif.espbleapp.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ApConnectionState {
    Idle,
    Scanning,
    Connecting,
    Discovering,
    Subscribing,
    Ready,
    ProtocolNotReady,
    Disconnecting,
    Disconnected,
    Failed,
}

data class BleDeviceInfo(
    val name: String?,
    val address: String,
    val rssi: Int = 0,
)

interface ApTransport {
    val connectionState: StateFlow<ApConnectionState>
    val incomingLines: Flow<String>
    val scannedDevices: StateFlow<List<BleDeviceInfo>>

    val attPayloadBytes: Int
        get() = 20

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(address: String)
    suspend fun disconnect()
    suspend fun sendLine(json: String)
    suspend fun sendBytes(bytes: ByteArray, onProgress: ((Int) -> Unit)? = null)
}
