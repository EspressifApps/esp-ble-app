package com.espressif.espbleapp.gmp

import android.content.Context
import com.espressif.espbleapp.flux.FluxBleTransport
import com.espressif.espbleapp.flux.FluxDiscoveredDevice
import com.espressif.espbleapp.flux.FluxLinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal fun transferStateAfterLinkTeardown(current: FtTransferState): FtTransferState = when (current) {
    is FtTransferState.Idle, is FtTransferState.Completed, is FtTransferState.Cancelled, is FtTransferState.Failed -> current
    else -> FtTransferState.Failed("Link lost", FtReason.LINK_ERROR)
}

/** File-transfer facade: all BLE and Flux work is owned by the shared Flux transport. */
class GmpBleTransport(context: Context) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val flux = FluxBleTransport(context)
    private val gmp = GmpClient(scope, flux, flux::log)
    private val sender = GmpFileTransferSender(scope, gmp)
    private var sendJob: Job? = null
    private val _transferState = MutableStateFlow<FtTransferState>(FtTransferState.Idle)
    val transferState = _transferState.asStateFlow()
    val scannedDevices = flux.scannedDevices
    val isScanning = flux.isScanning
    val connectionState = flux.linkState
    val connectedDevice = flux.connectedDevice
    val logs = flux.logs

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gmp.incomingFrames.collect { sender.onIncoming(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sender.state.collect { _transferState.value = it }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flux.linkState.collect {
                if (it !is FluxLinkState.Ready) {
                    sender.onLinkLost()
                    _transferState.value = transferStateAfterLinkTeardown(_transferState.value)
                }
            }
        }
    }

    fun startScan(filterServiceUuid: UUID? = null) = flux.startScan(filterServiceUuid)
    fun stopScan() = flux.stopScan()
    fun connect(device: FluxDiscoveredDevice) = flux.connect(device, GmpGattProfiles.FILE_TRANSFER)
    fun disconnect() = flux.disconnect()
    fun clearLogs() = flux.clearLogs()
    fun cancelTransfer() = sender.cancel()

    fun send(source: FtFileSource) {
        if (connectionState.value !is FluxLinkState.Ready || sendJob?.isActive == true) return
        sendJob = scope.launch {
            try {
                sender.send(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flux.log("File transfer failed: ${e.message}")
            }
        }
    }

    fun close() {
        sender.onLinkLost()
        flux.close()
        supervisor.cancel()
    }
}
