package com.espressif.espbleapp.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.espressif.espbleapp.flux.FluxDiscoveredDevice
import com.espressif.espbleapp.flux.GmpFileTransferUuids
import com.espressif.espbleapp.gmp.ContentUriFileSource
import com.espressif.espbleapp.gmp.GmpBleTransport

class GmpFileTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val transport = GmpBleTransport(application)
    val scannedDevices = transport.scannedDevices
    val isScanning = transport.isScanning
    val connectionState = transport.connectionState
    val connectedDevice = transport.connectedDevice
    val transferState = transport.transferState
    val logs = transport.logs

    fun startScan(filtered: Boolean) =
        transport.startScan(if (filtered) GmpFileTransferUuids.SERVICE else null)

    fun stopScan() = transport.stopScan()

    fun connect(device: FluxDiscoveredDevice) = transport.connect(device)

    fun disconnect() = transport.disconnect()

    fun send(uri: Uri) = transport.send(ContentUriFileSource(getApplication(), uri))

    fun cancelTransfer() = transport.cancelTransfer()

    fun clearLogs() = transport.clearLogs()

    override fun onCleared() {
        transport.close()
        super.onCleared()
    }
}
