package com.espressif.espbleapp.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.espressif.espbleapp.flux.FluxBleTransport
import com.espressif.espbleapp.flux.FluxConnectionState
import com.espressif.espbleapp.flux.FluxDiscoveredDevice
import com.espressif.espbleapp.flux.FluxTransferProgress
import com.espressif.espbleapp.flux.FluxReceivedMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.espressif.espbleapp.gmp.GmpClient
import com.espressif.espbleapp.gmp.GmpGattProfiles
import com.espressif.espbleapp.gmp.GmpOtaSender
import java.util.UUID

class FluxViewModel(application: Application) : AndroidViewModel(application) {
    val transport = FluxBleTransport(application)

    val scannedDevices: StateFlow<List<FluxDiscoveredDevice>> = transport.scannedDevices
    val isScanning: StateFlow<Boolean> = transport.isScanning
    val connectionState: StateFlow<FluxConnectionState> = transport.connectionState
    val connectedDevice: StateFlow<FluxDiscoveredDevice?> = transport.connectedDevice
    val mtu: StateFlow<Int> = transport.mtu
    val transferProgress: StateFlow<FluxTransferProgress> = transport.transferProgress
    val logs: StateFlow<List<String>> = transport.logs
    val receivedData: StateFlow<List<FluxReceivedMessage>> = transport.receivedData

    private val gmpClient by lazy {
        GmpClient(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO), transport, transport::log)
    }
    private val otaSender by lazy { GmpOtaSender(gmpClient, transport::log) }
    val gmpOtaProgress get() = otaSender.progress
    private var otaJob: Job? = null

    fun startScan(filterFluxServiceOnly: Boolean) {
        transport.startScan(filterFluxServiceOnly)
    }

    fun startScan(filterServiceUuid: UUID? = null) {
        transport.startScan(filterServiceUuid)
    }

    fun stopScan() {
        transport.stopScan()
    }

    fun connect(device: FluxDiscoveredDevice) {
        transport.connect(device)
    }

    fun connectGmpOta(device: FluxDiscoveredDevice) {
        transport.connect(device, GmpGattProfiles.OTA)
    }

    fun disconnect() {
        transport.disconnect()
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        transport.sendFluxData(bytes)
    }

    fun sendTestBuffer(sizeBytes: Int) {
        val baseText = "The quick brown fox jumps over the lazy dog. ESP Flux Reliable BLE Protocol Test. "
        val baseBytes = baseText.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(sizeBytes)
        var written = 0
        while (written < sizeBytes) {
            val toCopy = minOf(sizeBytes - written, baseBytes.size)
            System.arraycopy(baseBytes, 0, buf, written, toCopy)
            written += toCopy
        }
        transport.sendFluxData(buf)
    }

    fun sendFile(context: Context, uri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null && bytes.isNotEmpty()) {
                transport.sendFluxData(bytes)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun sendGmpOtaFirmware(context: Context, uri: Uri) {
        if (otaJob?.isActive == true) return
        otaJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                otaSender.upload(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transport.log("Unable to read OTA firmware: ${e.message}")
            }
        }
    }

    fun clearLogs() {
        transport.clearLogs()
    }

    fun clearReceivedData() {
        transport.clearReceivedData()
    }

    override fun onCleared() {
        super.onCleared()
        transport.close()
    }
}
