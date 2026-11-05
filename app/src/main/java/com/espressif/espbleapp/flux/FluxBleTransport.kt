package com.espressif.espbleapp.flux

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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

@SuppressLint("MissingPermission")
class FluxBleTransport(
    context: Context,
) : FluxMessageLink {
    private val appContext = context.applicationContext
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val monitor = Any()
    private val identity = FluxGattClientGuard()
    private var writePermits = Semaphore(GATT_WRITE_WINDOW)
    private var writeWakeups = Channel<Unit>(Channel.CONFLATED)
    private var writeBackpressure = BleWriteBackpressure(SystemClock::elapsedRealtime)
    private val messageMutex = Mutex()
    private var requiredProfile: FluxGattProfile? = null
    private var cacheRefreshAttempted = false

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _scannedDevices = MutableStateFlow<List<FluxDiscoveredDevice>>(emptyList())
    val scannedDevices: StateFlow<List<FluxDiscoveredDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _linkState = MutableStateFlow<FluxLinkState>(FluxLinkState.Idle)
    override val linkState: StateFlow<FluxLinkState> = _linkState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<FluxDiscoveredDevice?>(null)
    val connectedDevice: StateFlow<FluxDiscoveredDevice?> = _connectedDevice.asStateFlow()

    private val _connectionState = MutableStateFlow(FluxConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FluxConnectionState> = _connectionState.asStateFlow()
    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()
    private val _transferProgress = MutableStateFlow(FluxTransferProgress())
    val transferProgress: StateFlow<FluxTransferProgress> = _transferProgress.asStateFlow()
    private val _receivedData = MutableStateFlow<List<FluxReceivedMessage>>(emptyList())
    val receivedData: StateFlow<List<FluxReceivedMessage>> = _receivedData.asStateFlow()
    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val incomingMessages = _incomingMessages.asSharedFlow()
    override val maxMessageBytes: Int get() = fluxMaxMessageBytes(negotiatedMtu)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var fluxSession: ReliableFluxSession? = null
    private var inboundQueue: FluxInboundPacketQueue? = null
    private var inboundJob: Job? = null
    private var incomingJob: Job? = null
    private var transferJob: Job? = null
    private var sessionJob: CompletableJob = SupervisorJob(supervisor)
    private var inFlightGattWrites = 0
    private var activeFilterUuid: UUID? = null
    private var negotiatedMtu: Int = 23
    private var stage: FluxConnectionStage = FluxConnectionStage.IDLE

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown Device"
            val address = device.address ?: return
            val uuids = result.scanRecord?.serviceUuids
            if (!matchesScan(result, name)) {
                return
            }
            val item = FluxDiscoveredDevice(device, name, address, result.rssi)
            val current = _scannedDevices.value.toMutableList()
            val existing = current.indexOfFirst { it.address == address }
            if (existing >= 0) current[existing] = item else current.add(item)
            _scannedDevices.value = current.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            log("Scan failed with error code $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val event = when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    FluxGattLinkEvent.CONNECTED
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> FluxGattLinkEvent.DISCONNECTED
                else -> FluxGattLinkEvent.FAILED
            }
            val decision = synchronized(monitor) { identity.observe(gatt, event, status) }
            if (decision != FluxGattObserveDecision.LIVE) return
            applyLinkEvent(gatt, event, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(gatt) || stage != FluxConnectionStage.REQUESTING_MTU) return
            negotiatedMtu = mtu
            _mtu.value = mtu
            log("MTU changed to $mtu (status $status)")
            if (!isFluxMtuAcceptable(status, mtu, requiredProfile?.minimumMtu ?: 23)) {
                fail(fluxMtuFailureMessage(status, mtu, requiredProfile?.minimumMtu ?: 23))
                return
            }
            enterStage(FluxConnectionStage.DISCOVERING_SERVICES, FluxLinkState.DiscoveringServices)
            if (!gatt.discoverServices()) {
                fail("Failed to start service discovery")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt) || stage != FluxConnectionStage.DISCOVERING_SERVICES) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed (status $status)")
                return
            }
            val profile = requiredProfile ?: findFluxProfile(gatt)
            if (profile == null) {
                fail("No writable/notifiable Flux service found")
                return
            }
            val service = gatt.getService(profile.service)
            if (service == null && !cacheRefreshAttempted) {
                cacheRefreshAttempted = true
                val refreshed = runCatching {
                    gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean == true
                }.getOrDefault(false)
                if (refreshed) {
                    log("Service ${profile.service} missing; refreshing GATT cache")
                    scope.launch {
                        delay(500)
                        if (isCurrentGatt(gatt) && !gatt.discoverServices()) fail("Failed to rediscover services")
                    }
                    return
                }
            }
            val rx = service?.getCharacteristic(profile.rx)
            val tx = service?.getCharacteristic(profile.tx)
            val cccd = tx?.getDescriptor(FluxUuids.CCCD)
            val check = evaluateFluxGattProfile(
                serviceFound = service != null,
                rxFound = rx != null,
                rxProperties = rx?.properties ?: 0,
                txFound = tx != null,
                txProperties = tx?.properties ?: 0,
                cccdFound = cccd != null,
                profile = profile,
            )
            val failure = check.failureMessage
            if (failure != null) {
                fail(failure)
                return
            }
            synchronized(monitor) {
                if (identity.tornDown) return
                rxCharacteristic = rx
                txCharacteristic = tx
            }
            enterStage(
                FluxConnectionStage.ENABLING_NOTIFICATIONS,
                FluxLinkState.EnablingNotifications,
            )
            if (!gatt.setCharacteristicNotification(tx, true)) {
                fail("Failed to enable TX notifications")
                return
            }
            if (!writeCccd(gatt, requireNotNull(cccd))) {
                fail("Failed to write TX CCCD")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt) || stage != FluxConnectionStage.ENABLING_NOTIFICATIONS) return
            if (descriptor.uuid != FluxUuids.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("CCCD write failed (status $status)")
                return
            }
            becomeReady(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrentGatt(gatt)) return
            deliverTxNotification(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (!isCurrentGatt(gatt)) return
            val value = characteristic.value ?: return
            deliverTxNotification(characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(monitor) {
                if (!identity.isCurrent(gatt) || characteristic.uuid != rxCharacteristic?.uuid) return
                if (inFlightGattWrites > 0) {
                    inFlightGattWrites--
                    writePermits.release()
                    writeWakeups.trySend(Unit)
                }
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> writeBackpressure.onWriteSucceeded()
                    BluetoothGatt.GATT_CONNECTION_CONGESTED -> {
                        if (writeBackpressure.onCongested()) {
                            log("BLE congested; backing off ${writeBackpressure.remainingDelayMs()} ms. Flux will retry unacknowledged fragments.")
                        } else {
                            fail("BLE congestion persisted after 20 callbacks")
                        }
                    }
                    else -> fail("BLE write callback failed (status=$status)")
                }
            }
        }
    }

    fun startScan(filterServiceUuid: UUID? = null) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth LE scanner not available")
            return
        }
        activeFilterUuid = filterServiceUuid
        _scannedDevices.value = emptyList()
        _isScanning.value = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        log("Starting BLE scan (unfiltered OS scan + software filter UUID=$filterServiceUuid)")
        scanner.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        if (!_isScanning.value) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
        log("Stopped BLE scan")
    }

    fun startScan(filterFluxServiceOnly: Boolean) =
        startScan(if (filterFluxServiceOnly) FluxUuids.SERVICE else null)

    private fun matchesScan(result: ScanResult, name: String): Boolean {
        val target = activeFilterUuid ?: return true
        if (shouldIncludeBleScanResult(result.scanRecord?.serviceUuids?.map { it.uuid }, target)) return true
        val raw = result.scanRecord?.bytes ?: return false
        val bigEndian = java.nio.ByteBuffer.allocate(16)
            .putLong(target.mostSignificantBits).putLong(target.leastSignificantBits).array()
        if ((0..raw.size - 16).any { offset ->
                (0 until 16).all { raw[offset + it] == bigEndian[15 - it] }
            }) return true
        return target == FluxUuids.SERVICE &&
            (name.contains("ESP_FLUX", true) || name.contains("ESP-FLUX", true))
    }

    private fun findFluxProfile(gatt: BluetoothGatt): FluxGattProfile? {
        gatt.getService(FluxUuids.SERVICE)?.getCharacteristic(FluxUuids.CHARACTERISTIC)?.let {
            return FluxGattProfile(FluxUuids.SERVICE, it.uuid, it.uuid)
        }
        // Keep UART-style Flux devices usable, but never mix characteristics from different services.
        for (service in gatt.services) {
            val rx = service.characteristics.firstOrNull {
                it.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            val tx = service.characteristics.firstOrNull { it.properties and PROPERTY_NOTIFY != 0 }
            if (rx != null && tx != null && tx.getDescriptor(FluxUuids.CCCD) != null) {
                return FluxGattProfile(service.uuid, rx.uuid, tx.uuid)
            }
        }
        return null
    }

    fun connect(device: FluxDiscoveredDevice, profile: FluxGattProfile? = null) {
        stopScan()
        teardown(failureMessage = null)
        synchronized(monitor) {
            identity.beginConnect()
            requiredProfile = profile
            cacheRefreshAttempted = false
            writePermits = Semaphore(GATT_WRITE_WINDOW)
            writeWakeups = Channel(Channel.CONFLATED)
            writeBackpressure = BleWriteBackpressure(SystemClock::elapsedRealtime)
            sessionJob = SupervisorJob(supervisor)
            stage = identity.stage
            gatt = null
        }
        _linkState.value = identity.published
        _connectionState.value = FluxConnectionState.CONNECTING
        _connectedDevice.value = device
        _transferProgress.value = FluxTransferProgress()
        log("Connecting to ${device.name} (${device.address})")
        val opened = device.device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
        val attach = synchronized(monitor) {
            val result = identity.attachOpened(opened)
            if (result.accepted) gatt = opened
            result
        }
        attach.close.forEach { closeGatt(it) }
        if (!attach.accepted) {
            closeGatt(opened)
            if (!identity.tornDown) {
                fail("Failed to open GATT")
            }
            return
        }
        when (attach.replay) {
            FluxGattLinkEvent.CONNECTED -> applyLinkEvent(opened!!, FluxGattLinkEvent.CONNECTED)
            FluxGattLinkEvent.DISCONNECTED -> fail(fluxDisconnectFailureMessage(identity.stage))
            FluxGattLinkEvent.FAILED -> fail("Connection failed (status ${attach.replayStatus})")
            null -> Unit
        }
    }

    fun disconnect() {
        fail(fluxDisconnectFailureMessage(stage))
    }

    fun close() {
        stopScan()
        teardown("Closed")
        supervisor.cancel()
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun clearReceivedData() {
        _receivedData.value = emptyList()
    }

    /** One Flux message at a time; completion means every fragment has a peer SACK. */
    override suspend fun sendMessage(
        bytes: ByteArray,
        onFirstPacketSent: (() -> Unit)?,
        onAcknowledged: ((Int, Long) -> Unit)?,
    ) {
        val session = fluxSession ?: throw FluxTransferException("Flux link is not ready")
        messageMutex.withLock {
            check(session === fluxSession) { "Flux connection changed before sending" }
            session.send(bytes, onFirstPacketSent, onAcknowledged)
        }
    }

    fun sendFluxData(data: ByteArray) {
        if (data.isEmpty() || transferJob?.isActive == true) return
        if (fluxSession == null) {
            log("Cannot send: Flux link is not ready")
            return
        }
        val payload = data.copyOf()
        _transferProgress.value = FluxTransferProgress(isSending = true, totalBytes = payload.size)
        transferJob = CoroutineScope(sessionJob + Dispatchers.IO).launch {
            var startedAt = 0L
            var offset = 0
            try {
                // Each segment stays within the peer's reassembly and fragment-count limits.
                val segmentSize = minOf(maxMessageBytes, (negotiatedMtu - 7).coerceAtLeast(1) * 80)
                while (offset < payload.size) {
                    val length = minOf(segmentSize, payload.size - offset)
                    val base = offset
                    sendMessage(
                        payload.copyOfRange(base, base + length),
                        onFirstPacketSent = {
                            if (startedAt == 0L) startedAt = System.currentTimeMillis()
                            _transferProgress.update { it.copy(startedAtMs = startedAt, isWaitingForAck = true) }
                        },
                        onAcknowledged = { bytes, ackAt ->
                            val confirmed = base + bytes
                            val elapsed = (ackAt - startedAt).coerceAtLeast(0)
                            _transferProgress.update {
                                it.copy(
                                    confirmedBytes = confirmed,
                                    transferredBytes = confirmed,
                                    progressPercent = (confirmed * 100L / payload.size).toInt(),
                                    elapsedTimeMs = elapsed,
                                    speedBps = if (elapsed > 0) confirmed * 1000.0 / elapsed else 0.0,
                                )
                            }
                        },
                    )
                    offset += length
                }
                _transferProgress.update { it.copy(isSending = false, isWaitingForAck = false, ackReceived = true) }
                log("Complete Flux transfer ACK received: ${payload.size} bytes, ${_transferProgress.value.elapsedTimeMs} ms")
            } catch (e: Exception) {
                _transferProgress.update {
                    it.copy(isSending = false, isWaitingForAck = false, failureMessage = e.message ?: "Transfer cancelled")
                }
                if (e is CancellationException) throw e
                log("Flux transfer failed: ${e.message}")
            }
        }
    }

    private fun becomeReady(client: BluetoothGatt) {
        val sessionScope = CoroutineScope(sessionJob + Dispatchers.IO)
        val session = ReliableFluxSession(
            scope = sessionScope,
            writer = object : FluxPacketWriter {
                override suspend fun write(packet: ByteArray) = writeRxPacket(packet)
                override suspend fun write(packet: ByteArray, onSubmitted: (() -> Unit)?) =
                    writeRxPacket(packet, onSubmitted)
            },
            mtu = negotiatedMtu,
        )
        val inbound = FluxInboundPacketQueue()
        val inboundConsumer = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            inbound.collect { bytes ->
                try {
                    session.onPacket(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("Flux packet rejected: ${e.message}")
                }
            }
        }
        val incoming = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.incomingMessages.collect { bytes ->
                _receivedData.update { (it + FluxReceivedMessage(data = bytes)).takeLast(200) }
                _incomingMessages.emit(bytes)
            }
        }
        val bound = synchronized(monitor) {
            if (!identity.isCurrent(client) || stage != FluxConnectionStage.ENABLING_NOTIFICATIONS) {
                false
            } else {
                fluxSession = session
                inboundQueue = inbound
                inboundJob = inboundConsumer
                incomingJob = incoming
                true
            }
        }
        if (!bound) {
            inbound.close()
            inboundConsumer.cancel()
            incoming.cancel()
            session.close(FluxTransferException("GATT closed before ready"))
            return
        }
        enterStage(FluxConnectionStage.READY, FluxLinkState.Ready)
        log("Flux GATT ready (MTU=$negotiatedMtu, maxMessageBytes=$maxMessageBytes)")
    }

    private suspend fun writeRxPacket(packet: ByteArray, onSubmitted: (() -> Unit)? = null) {
        var busyRetries = 0
        val permits: Semaphore
        val wakeups: Channel<Unit>
        val pressure: BleWriteBackpressure
        val client: BluetoothGatt
        synchronized(monitor) {
            client = gatt ?: throw FluxTransferException("Not connected")
            permits = writePermits
            wakeups = writeWakeups
            pressure = writeBackpressure
        }
        while (true) {
            pressure.awaitReady()
            try {
                withTimeout(FLUX_GATT_WRITE_TIMEOUT_MS) { permits.acquire() }
            } catch (e: TimeoutCancellationException) {
                if (isCurrentGatt(client)) teardown("Timed out waiting for BLE write capacity")
                throw FluxTransferException("Timed out waiting for BLE write capacity", e)
            }
            val result = synchronized(monitor) {
                val gattClient = gatt
                val rx = rxCharacteristic
                if (gattClient !== client || rx == null || identity.tornDown) {
                    WriteSubmit.Failed
                } else if (pressure.remainingDelayMs() > 0L) {
                    // Congestion may have arrived while this writer waited for a slot.
                    WriteSubmit.Backoff
                } else {
                    submitWrite(
                        gattClient,
                        rx,
                        packet,
                        preferredRxWriteType(rx.properties),
                    ).also { submitted ->
                        if (submitted == WriteSubmit.Accepted) {
                            inFlightGattWrites++
                            onSubmitted?.invoke()
                        }
                    }
                }
            }
            when (result) {
                WriteSubmit.Accepted -> return
                WriteSubmit.Backoff -> permits.release()
                WriteSubmit.Busy -> {
                    permits.release()
                    if (++busyRetries > GATT_BUSY_RETRIES) {
                        throw FluxTransferException("GATT remained busy after $GATT_BUSY_RETRIES retries")
                    }
                    // A completed write can free Android's stack before the backoff expires.
                    withTimeoutOrNull(minOf(50L, 2L shl busyRetries.coerceAtMost(4))) {
                        wakeups.receive()
                    }
                }
                WriteSubmit.Failed -> {
                    permits.release()
                    throw FluxTransferException("GATT write failed: not connected or rejected")
                }
            }
        }
    }

    private fun submitWrite(
        gatt: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        packet: ByteArray,
        writeType: Int,
    ): WriteSubmit {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (gatt.writeCharacteristic(rx, packet, writeType)) {
                BluetoothStatusCodes.SUCCESS -> WriteSubmit.Accepted
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteSubmit.Busy
                else -> WriteSubmit.Failed
            }
        } else {
            @Suppress("DEPRECATION")
            rx.value = packet
            @Suppress("DEPRECATION")
            rx.writeType = writeType
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(rx)) WriteSubmit.Accepted else WriteSubmit.Busy
        }
    }

    private enum class WriteSubmit { Accepted, Busy, Backoff, Failed }

    private fun writeCccd(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun deliverTxNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid != txCharacteristic?.uuid) return
        inboundQueue?.enqueue(value)
    }

    private fun applyLinkEvent(gatt: BluetoothGatt, event: FluxGattLinkEvent, status: Int = 0) {
        when (event) {
            FluxGattLinkEvent.CONNECTED -> {
                enterStage(FluxConnectionStage.REQUESTING_MTU, FluxLinkState.RequestingMtu)
                log("GATT connected. Requesting MTU ${FluxUuids.PREFERRED_MTU}")
                if (!gatt.requestMtu(FluxUuids.PREFERRED_MTU)) {
                    fail("Failed to request MTU")
                }
            }
            FluxGattLinkEvent.DISCONNECTED -> fail(fluxDisconnectFailureMessage(stage))
            FluxGattLinkEvent.FAILED -> fail("Connection failed (status $status)")
        }
    }

    private fun closeGatt(client: Any?) {
        val gattClient = client as? BluetoothGatt ?: return
        try {
            gattClient.disconnect()
            gattClient.close()
        } catch (_: Exception) {
        }
    }

    private fun fail(message: String) {
        teardown(message)
    }

    private fun teardown(failureMessage: String?) {
        val gattToClose: BluetoothGatt?
        val leftover: List<Any>
        val sessionToClose: ReliableFluxSession?
        val inboundToClose: FluxInboundPacketQueue?
        val sessionJobToCancel: CompletableJob
        val inboundToCancel: Job?
        val incomingToCancel: Job?
        val transferToCancel: Job?
        val writesInFlight: Int
        val permitsToRelease: Semaphore
        val alreadyTornDown: Boolean
        synchronized(monitor) {
            permitsToRelease = writePermits
            alreadyTornDown = identity.tornDown
            leftover = identity.teardown(failureMessage)
            stage = identity.stage
            if (alreadyTornDown) {
                if (failureMessage == "Closed") {
                    _linkState.value = identity.published
                }
                gattToClose = null
                sessionToClose = null
                inboundToClose = null
                sessionJobToCancel = sessionJob
                inboundToCancel = null
                incomingToCancel = null
                transferToCancel = null
                writesInFlight = 0
            } else {
                gattToClose = gatt
                sessionToClose = fluxSession
                inboundToClose = inboundQueue
                writesInFlight = inFlightGattWrites
                inFlightGattWrites = 0
                writeWakeups.close()
                sessionJobToCancel = sessionJob
                inboundToCancel = inboundJob
                incomingToCancel = incomingJob
                transferToCancel = transferJob
                gatt = null
                fluxSession = null
                inboundQueue = null
                inboundJob = null
                incomingJob = null
                transferJob = null
                rxCharacteristic = null
                txCharacteristic = null
            }
        }
        leftover.forEach { if (it !== gattToClose) closeGatt(it) }
        if (alreadyTornDown) return
        repeat(writesInFlight) { permitsToRelease.release() }
        inboundToClose?.close()
        inboundToCancel?.cancel()
        incomingToCancel?.cancel()
        sessionToClose?.close(FluxTransferException(failureMessage ?: "GATT closed"))
        if (_transferProgress.value.isSending) {
            _transferProgress.update { it.copy(isSending = false, isWaitingForAck = false, failureMessage = failureMessage ?: "Link closed") }
        }
        transferToCancel?.cancel()
        sessionJobToCancel.cancel()
        closeGatt(gattToClose)
        _connectionState.value = FluxConnectionState.DISCONNECTED
        _mtu.value = 23
        if (failureMessage != null) {
            _connectedDevice.value = null
            _linkState.value = identity.published
            log(failureMessage)
        }
    }

    private fun enterStage(next: FluxConnectionStage, state: FluxLinkState) {
        synchronized(monitor) {
            if (!identity.enterStage(next, state)) return
            stage = next
            _linkState.value = state
            _connectionState.value = if (state is FluxLinkState.Ready) FluxConnectionState.CONNECTED else FluxConnectionState.CONNECTING
        }
    }

    private fun isCurrentGatt(callbackGatt: BluetoothGatt): Boolean =
        synchronized(monitor) { identity.isCurrent(callbackGatt) }

    fun log(message: String) {
        _logs.update { (it + message).takeLast(MAX_LOGS) }
    }
}

private const val GATT_WRITE_WINDOW = 4
private const val GATT_BUSY_RETRIES = 20

private const val MAX_LOGS = 200
internal const val FLUX_GATT_WRITE_TIMEOUT_MS = 5_000L
