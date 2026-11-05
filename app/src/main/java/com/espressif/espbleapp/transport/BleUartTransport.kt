package com.espressif.espbleapp.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Nordic UART Service transport over native BluetoothGatt.
 */
@SuppressLint("MissingPermission")
class BleUartTransport(
    context: Context,
    private val namePrefixes: List<String> = listOf(
        BleUartUuids.DEFAULT_NAME_PREFIX,
        BleUartUuids.FALLBACK_NAME_PREFIX,
    ),
    private val autoReconnect: Boolean = true,
) : ApTransport {
    companion object {
        private const val TAG = "BleUartTransport"
        private const val GATT_OP_TIMEOUT_MS = 5_000L
        private const val WWR_CREDIT_PROBE_MS = 80L
        private const val WWR_CREDIT_MS = 25L
        private const val WWR_BUSY_DELAY_MS = 2L
        private const val WWR_BUSY_RETRIES = 400
    }
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rxJob = SupervisorJob()
    private val rxScope = CoroutineScope(rxJob + Dispatchers.Default.limitedParallelism(1))
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(ApConnectionState.Idle)
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 128)
    private val _scanned = MutableStateFlow<List<BleDeviceInfo>>(emptyList())

    private val framer = JsonlFramer()
    private val writeMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20
    private var lastAddress: String? = null
    private var reconnectJob: Job? = null
    private var userDisconnect = false
    private var connectCont: CancellableContinuation<Unit>? = null
    private var mtuCont: CancellableContinuation<Int>? = null
    private var descCont: CancellableContinuation<Unit>? = null
    private var writeCont: CancellableContinuation<Unit>? = null
    private var writeEpoch = 0
    private var writeWaitEpoch = 0

    override val connectionState: StateFlow<ApConnectionState> = _connectionState.asStateFlow()
    override val incomingLines: SharedFlow<String> = _incoming.asSharedFlow()
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scanned.asStateFlow()
    override val attPayloadBytes: Int
        get() = mtuPayload

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name
            val address = device.address ?: return
            if (!nameMatches(name) && result.scanRecord?.serviceUuids?.none {
                    it.uuid == BleUartUuids.SERVICE
                } == true
            ) {
                return
            }
            val updated = _scanned.value
                .filterNot { it.address.equals(address, ignoreCase = true) }
                .toMutableList()
            updated += BleDeviceInfo(name = name, address = address, rssi = result.rssi)
            _scanned.value = updated.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _connectionState.value = ApConnectionState.Failed
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "conn state change status=$status new=$newState")
                failConnect(IllegalStateException("GATT status $status"))
                g.close()
                if (gatt === g) gatt = null
                _connectionState.value = ApConnectionState.Disconnected
                scheduleReconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected")
                    _connectionState.value = ApConnectionState.Discovering
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected")
                    g.close()
                    if (gatt === g) gatt = null
                    rxChar = null
                    txChar = null
                    rxJob.cancelChildren()
                    framer.reset()
                    _connectionState.value = ApConnectionState.Disconnected
                    failConnect(IllegalStateException("disconnected"))
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect(IllegalStateException("service discover $status"))
                return
            }
            val service = g.getService(BleUartUuids.SERVICE)
            if (service == null) {
                failConnect(IllegalStateException("NUS service missing"))
                return
            }
            rxChar = service.getCharacteristic(BleUartUuids.RX)
            txChar = service.getCharacteristic(BleUartUuids.TX)
            if (rxChar == null || txChar == null) {
                failConnect(IllegalStateException("NUS chars missing"))
                return
            }
            _connectionState.value = ApConnectionState.Subscribing
            scope.launch {
                try {
                    val mtu = requestMtuInternal(g, BleUartUuids.PREFERRED_MTU)
                    Log.i(TAG, "MTU negotiated mtu=$mtu payload=$mtuPayload")
                    runCatching {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching {
                            g.setPreferredPhy(
                                BluetoothDevice.PHY_LE_2M,
                                BluetoothDevice.PHY_LE_2M,
                                BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                            )
                        }
                    }
                    enableNotify(g, txChar!!)
                    Log.i(TAG, "TX notify enabled (CCCD written)")
                    _connectionState.value = ApConnectionState.Ready
                    connectCont?.takeIf { it.isActive }?.resume(Unit)
                    connectCont = null
                } catch (t: Throwable) {
                    failConnect(t)
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != BleUartUuids.TX) return
            val chunk = value.copyOf()
            rxScope.launch {
                Log.i(TAG, "TX notify ${chunk.size} bytes")
                framer.feed(chunk).forEach { line ->
                    Log.i(TAG, "RX line: $line")
                    _incoming.emit(line)
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: return
            onCharacteristicChanged(g, characteristic, value)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuPayload = maxOf(20, min(mtu - 3, BleUartUuids.GATT_MAX_ATTR_LEN))
                Log.i(TAG, "onMtuChanged mtu=$mtu payload=$mtuPayload")
            } else {
                Log.w(TAG, "onMtuChanged failed status=$status")
            }
            mtuCont?.takeIf { it.isActive }?.resume(mtu)
            mtuCont = null
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.i(TAG, "onDescriptorWrite uuid=${descriptor.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descCont?.takeIf { it.isActive }?.resume(Unit)
            } else {
                descCont?.takeIf { it.isActive }?.resumeWithException(
                    IllegalStateException("CCCD write failed $status"),
                )
            }
            descCont = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (writeWaitEpoch != writeEpoch) {
                return
            }
            Log.d(TAG, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeCont?.takeIf { it.isActive }?.resume(Unit)
            } else {
                writeCont?.takeIf { it.isActive }?.resumeWithException(
                    IllegalStateException("RX write failed $status"),
                )
            }
            writeCont = null
        }
    }

    override suspend fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth unavailable")
        userDisconnect = false
        _scanned.value = emptyList()
        _connectionState.value = ApConnectionState.Scanning
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUartUuids.SERVICE)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        // Unfiltered scan helps when advertisers omit the service UUID.
    }

    override suspend fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_connectionState.value == ApConnectionState.Scanning) {
            _connectionState.value = ApConnectionState.Idle
        }
    }

    override suspend fun connect(address: String) {
        reconnectJob?.cancel()
        userDisconnect = false
        lastAddress = address
        stopScan()
        _connectionState.value = ApConnectionState.Connecting
        val device = adapter?.getRemoteDevice(address)
            ?: throw IllegalStateException("Device not found: $address")
        suspendCancellableCoroutine { cont ->
            connectCont = cont
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            cont.invokeOnCancellation {
                gatt?.disconnect()
            }
        }
    }

    override suspend fun disconnect() {
        userDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        _connectionState.value = ApConnectionState.Disconnecting
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
        txChar = null
        rxJob.cancelChildren()
        framer.reset()
        writeEpoch++
        writeCont = null
        _connectionState.value = ApConnectionState.Disconnected
    }

    override suspend fun sendLine(json: String) {
        val payload = if (json.endsWith("\n")) json else "$json\n"
        Log.d(TAG, "sendLine ${payload.length} chars")
        writeGatt(payload.toByteArray(Charsets.UTF_8), writeCommand = false)
    }

    override suspend fun sendBytes(bytes: ByteArray, onProgress: ((Int) -> Unit)?) {
        withContext(Dispatchers.IO) {
            val g = gatt ?: throw IllegalStateException("not connected")
            runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "sendBytes ${bytes.size} attPayload=$mtuPayload write=command")
            writeGatt(bytes, writeCommand = true, onProgress = onProgress)
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            val bytesPerSec = bytes.size * 1000L / elapsedMs
            Log.i(TAG, "sendBytes done ${bytes.size} in ${elapsedMs}ms ${bytesPerSec} B/s")
        }
    }

    private suspend fun writeGatt(
        bytes: ByteArray,
        writeCommand: Boolean,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        val g = gatt ?: throw IllegalStateException("not connected")
        val rx = rxChar ?: throw IllegalStateException("RX missing")
        writeMutex.withLock {
            var offset = 0
            var waitForCredit = writeCommand
            var creditModeLogged = false
            while (offset < bytes.size) {
                val chunkLen = min(mtuPayload, BleUartUuids.GATT_MAX_ATTR_LEN)
                val end = min(offset + chunkLen, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)
                if (writeCommand) {
                    val acked = writeCommandChunk(
                        g,
                        rx,
                        chunk,
                        waitForCredit = waitForCredit,
                        creditTimeoutMs = if (offset == 0) WWR_CREDIT_PROBE_MS else WWR_CREDIT_MS,
                    )
                    if (waitForCredit && !acked) {
                        waitForCredit = false
                        Log.w(TAG, "WWR stack credit missing; pace by GATT busy")
                    } else if (waitForCredit && !creditModeLogged) {
                        creditModeLogged = true
                        Log.i(TAG, "WWR stack credit via onCharacteristicWrite")
                    }
                } else {
                    writeAwaitingCallback(
                        g,
                        rx,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                }
                val previous = offset
                offset = end
                if (writeCommand && (offset / 16384 != previous / 16384 || offset == bytes.size)) {
                    Log.i(TAG, "sendBytes progress $offset/${bytes.size}")
                    onProgress?.invoke(offset)
                }
            }
        }
    }

    private suspend fun writeCommandChunk(
        g: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        chunk: ByteArray,
        waitForCredit: Boolean,
        creditTimeoutMs: Long,
    ): Boolean {
        var spins = 0
        while (true) {
            if (!waitForCredit) {
                when (submitWrite(g, rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                    WriteSubmit.Ok -> return false
                    WriteSubmit.Busy -> {
                        if (++spins > WWR_BUSY_RETRIES) {
                            throw IllegalStateException("GATT write busy")
                        }
                        delay(WWR_BUSY_DELAY_MS)
                    }
                    WriteSubmit.Failed -> throw IllegalStateException("writeCharacteristic rejected")
                }
                continue
            }
            writeEpoch++
            val epoch = writeEpoch
            try {
                val acked = withTimeoutOrNull(creditTimeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        writeWaitEpoch = epoch
                        writeCont = cont
                        cont.invokeOnCancellation {
                            if (writeCont === cont) {
                                writeCont = null
                            }
                        }
                        when (submitWrite(g, rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                            WriteSubmit.Ok -> Unit
                            WriteSubmit.Busy -> {
                                writeCont = null
                                cont.resumeWithException(WriteBusyException)
                            }
                            WriteSubmit.Failed -> {
                                writeCont = null
                                cont.resumeWithException(IllegalStateException("writeCharacteristic rejected"))
                            }
                        }
                    }
                }
                if (acked != null) {
                    return true
                }
                writeEpoch++
                writeCont = null
                return false
            } catch (_: WriteBusyException) {
                if (++spins > WWR_BUSY_RETRIES) {
                    throw IllegalStateException("GATT write busy")
                }
                delay(WWR_BUSY_DELAY_MS)
            }
        }
    }

    private suspend fun writeAwaitingCallback(
        g: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int,
    ) {
        var spins = 0
        while (true) {
            writeEpoch++
            try {
                withTimeout(GATT_OP_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        writeWaitEpoch = writeEpoch
                        writeCont = cont
                        cont.invokeOnCancellation {
                            if (writeCont === cont) {
                                writeCont = null
                            }
                        }
                        when (submitWrite(g, rx, chunk, writeType)) {
                            WriteSubmit.Ok -> Unit
                            WriteSubmit.Busy -> {
                                writeCont = null
                                cont.resumeWithException(WriteBusyException)
                            }
                            WriteSubmit.Failed -> {
                                writeCont = null
                                cont.resumeWithException(IllegalStateException("writeCharacteristic rejected"))
                            }
                        }
                    }
                }
                return
            } catch (_: WriteBusyException) {
                if (++spins > WWR_BUSY_RETRIES) {
                    throw IllegalStateException("GATT write busy")
                }
                delay(WWR_BUSY_DELAY_MS)
            }
        }
    }

    private enum class WriteSubmit { Ok, Busy, Failed }

    private object WriteBusyException : RuntimeException("gatt busy")

    private fun submitWrite(
        g: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int,
    ): WriteSubmit {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (val code = g.writeCharacteristic(rx, chunk, writeType)) {
                BluetoothStatusCodes.SUCCESS -> WriteSubmit.Ok
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteSubmit.Busy
                else -> {
                    Log.w(TAG, "writeCharacteristic code=$code")
                    WriteSubmit.Failed
                }
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                rx.writeType = writeType
                rx.value = chunk
                if (g.writeCharacteristic(rx)) WriteSubmit.Ok else WriteSubmit.Busy
            }
        }
    }

    private suspend fun requestMtuInternal(g: BluetoothGatt, mtu: Int): Int =
        withTimeout(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                mtuCont = cont
                cont.invokeOnCancellation {
                    if (mtuCont === cont) {
                        mtuCont = null
                    }
                }
                if (!g.requestMtu(mtu)) {
                    mtuCont = null
                    cont.resume(23)
                }
            }
        }

    private suspend fun enableNotify(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(tx, true)) {
            throw IllegalStateException("setCharacteristicNotification failed")
        }
        val cccd = tx.getDescriptor(BleUartUuids.CCCD)
            ?: throw IllegalStateException("CCCD missing")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        withTimeout(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                descCont = cont
                cont.invokeOnCancellation {
                    if (descCont === cont) {
                        descCont = null
                    }
                }
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = value
                        g.writeDescriptor(cccd)
                    }
                }
                if (!ok) {
                    descCont = null
                    cont.resumeWithException(IllegalStateException("writeDescriptor rejected"))
                }
            }
        }
    }

    private fun nameMatches(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return namePrefixes.any { name.startsWith(it, ignoreCase = true) }
    }

    private fun failConnect(t: Throwable) {
        connectCont?.takeIf { it.isActive }?.resumeWithException(t)
        connectCont = null
        if (_connectionState.value != ApConnectionState.Disconnected) {
            _connectionState.value = ApConnectionState.Failed
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || userDisconnect) return
        val address = lastAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 1_000L
            repeat(8) {
                delay(delayMs)
                try {
                    Log.i(TAG, "auto-reconnect $address attempt=${it + 1}")
                    connect(address)
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "reconnect failed: ${t.message}")
                    delayMs = min(delayMs * 2, 30_000L)
                }
            }
        }
    }

}
