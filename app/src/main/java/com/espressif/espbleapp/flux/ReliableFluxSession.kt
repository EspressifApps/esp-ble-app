package com.espressif.espbleapp.flux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

fun interface FluxPacketWriter {
    suspend fun write(packet: ByteArray)

    suspend fun write(packet: ByteArray, onSubmitted: (() -> Unit)?) {
        onSubmitted?.invoke()
        write(packet)
    }
}

class FluxTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ReliableFluxSession(
    private val scope: CoroutineScope,
    private val writer: FluxPacketWriter,
    private val mtu: Int,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val thresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT,
) {
    private val monitor = Any()
    private val writerLock = Mutex()
    private val slots = Semaphore(MAX_CONCURRENT_SENDS)
    private val sends = mutableMapOf<Int, SendState>()
    private val receives = mutableMapOf<Int, ReceiveState>()
    private val controlJobs = mutableSetOf<Job>()
    private val deliveryJobs = mutableSetOf<Job>()
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = MAX_CONCURRENT_RECEIVES)
    private var nextStreamId = 0
    private var closedCause: Throwable? = null

    val incomingMessages: Flow<ByteArray> = incoming.asSharedFlow()

    internal val ownedControlJobCountForTest: Int
        get() = synchronized(monitor) { controlJobs.size }

    init {
        require(mtu >= MINIMUM_ATT_MTU) { "MTU must be at least $MINIMUM_ATT_MTU" }
        require(windowSize in 1..UNLIMITED_WINDOW) { "Window size must be in 1..255" }
        require(thresholdPercent in 1..100) { "Window threshold must be in 1..100" }
        scope.coroutineContext[Job]?.invokeOnCompletion { cause ->
            close(cause ?: FluxTransferException("Flux session scope completed"))
        }
    }

    suspend fun send(
        message: ByteArray,
        onFirstPacketSent: (() -> Unit)? = null,
        onAcknowledged: ((Int, Long) -> Unit)? = null,
    ): ByteArray = slots.withPermit {
        require(message.isNotEmpty()) { "Flux messages must not be empty" }
        val messageCopy = message.copyOf()
        val state = synchronized(monitor) {
            closedCause?.let { throw FluxTransferException("Flux session is closed", it) }
            val streamId = allocateStreamIdLocked()
            val packets = buildReliablePackets(messageCopy, streamId)
            SendState(
                streamId = streamId,
                packets = packets,
                completion = CompletableDeferred(),
                acknowledged = BooleanArray(packets.size),
                dispatched = BooleanArray(packets.size),
                retryCounts = IntArray(packets.size),
                timeoutJobs = arrayOfNulls(packets.size),
                writeQueue = Channel(Channel.UNLIMITED),
                onFirstPacketSent = onFirstPacketSent,
                onAcknowledged = onAcknowledged,
            ).also { sends[it.streamId] = it }
        }

        try {
            startDataWriter(state)
            dispatchAvailable(state)
            state.completion.await()
            message
        } finally {
            synchronized(monitor) {
                if (sends[state.streamId] === state) {
                    sends.remove(state.streamId)
                }
                state.active = false
                state.timeoutJobs.forEach { it?.cancel() }
                state.writeQueue.close()
                state.writerJob?.cancel()
                if (!state.completion.isCompleted) {
                    state.completion.cancel(CancellationException("Flux send cancelled"))
                }
            }
        }
    }

    suspend fun onPacket(bytes: ByteArray) {
        validatePacketLength(bytes)
        when (val packet = FluxProtocol.parsePacket(bytes)
            ?: throw FluxTransferException("Malformed Flux packet")) {
            is FluxProtocol.FluxPacket.Sack -> handleSack(packet)
            is FluxProtocol.FluxPacket.Start -> handleStart(packet)
            is FluxProtocol.FluxPacket.Fragment -> handleFragment(packet)
            is FluxProtocol.FluxPacket.Single -> scheduleDelivery(packet.payload)
        }
    }

    fun close(cause: Throwable = FluxTransferException("Flux session closed")) {
        val sendStates: List<SendState>
        val receiveStates: List<ReceiveState>
        val controls: List<Job>
        val deliveries: List<Job>
        synchronized(monitor) {
            if (closedCause != null) return
            closedCause = cause
            sendStates = sends.values.toList()
            receiveStates = receives.values.toList()
            sends.clear()
            receives.clear()
            sendStates.forEach { it.active = false }
            sendStates.forEach { it.completion.completeExceptionally(cause) }
            controls = controlJobs.toList()
            deliveries = deliveryJobs.toList()
            controlJobs.clear()
            deliveryJobs.clear()
        }
        sendStates.forEach { state ->
            state.timeoutJobs.forEach { it?.cancel() }
            state.writeQueue.close()
            state.writerJob?.cancel()
        }
        receiveStates.forEach { state ->
            state.expiryJob.cancel()
            state.ackJob?.cancel()
            state.ackWriteJob?.cancel()
        }
        controls.forEach { it.cancel() }
        deliveries.forEach { it.cancel() }
    }

    private suspend fun handleSack(packet: FluxProtocol.FluxPacket.Sack) {
        val writes = mutableListOf<PacketWrite>()
        var failure: FluxTransferException? = null
        val state = synchronized(monitor) {
            val current = sends[packet.streamId]
            if (current == null || !current.active) return

            val upper = minOf(packet.ackNum, current.packets.lastIndex)
            for (sequence in 0..upper) {
                val missing = packet.bitmap?.let { bitmap ->
                    bitmap[sequence / 8].toInt() and (1 shl (sequence % 8)) != 0
                } ?: false
                if (!missing) {
                    acknowledgeLocked(current, sequence)
                }
            }

            packet.bitmap?.let { bitmap ->
                for (sequence in 0..upper) {
                    val missing = bitmap[sequence / 8].toInt() and (1 shl (sequence % 8)) != 0
                    if (missing && current.dispatched[sequence] && !current.acknowledged[sequence]) {
                        if (current.retryCounts[sequence] >= MAX_RETRIES) {
                            failure = failSendLocked(
                                current,
                                "Fragment $sequence exceeded $MAX_RETRIES retries",
                            )
                            break
                        }
                        current.retryCounts[sequence]++
                        current.timeoutJobs[sequence]?.cancel()
                        writes += PacketWrite(current, sequence)
                    }
                }
            }

            if (failure == null && current.active) {
                val confirmedBytes = current.packets.indices.sumOf { sequence ->
                    if (!current.acknowledged[sequence]) 0 else current.packets[sequence].size -
                        if (sequence == 0) FluxProtocol.START_HEADER_SIZE else FluxProtocol.FRAGMENT_HEADER_SIZE
                }
                current.onAcknowledged?.invoke(confirmedBytes, System.currentTimeMillis())
                if (current.acknowledged.all { it }) {
                    current.active = false
                    current.timeoutJobs.forEach { it?.cancel() }
                    current.writeQueue.close()
                    current.writerJob?.cancel()
                    current.completion.complete(Unit)
                } else {
                    writes += reserveWindowLocked(current)
                }
            }
            current
        }

        if (failure != null) return
        enqueueWrites(writes)
        if (!state.active && state.completion.isCompleted) return
    }

    private suspend fun handleStart(packet: FluxProtocol.FluxPacket.Start) {
        if (packet.totalFrags !in 1..MAX_FRAGMENTS ||
            packet.totalSize !in 1..MAX_REASSEMBLY_SIZE.toLong() ||
            packet.payload.isEmpty() ||
            packet.payload.size.toLong() > packet.totalSize
        ) {
            throw FluxTransferException("Invalid Flux START fields")
        }

        val state = synchronized(monitor) {
            closedCause?.let { throw FluxTransferException("Flux session is closed", it) }
            receives.remove(packet.streamId)?.also {
                it.expiryJob.cancel()
                it.ackJob?.cancel()
                it.ackWriteJob?.cancel()
            }
            if (receives.size >= MAX_CONCURRENT_RECEIVES) return

            ReceiveState(
                streamId = packet.streamId,
                totalFragments = packet.totalFrags,
                totalSize = packet.totalSize.toInt(),
                windowSize = packet.windowSize,
                thresholdPercent = packet.windowThresholdPercent.takeIf { it in 1..100 }
                    ?: DEFAULT_THRESHOLD_PERCENT,
                fragments = mutableMapOf(0 to packet.payload.copyOf()),
                expiryJob = inactiveJob(),
            ).also {
                receives[packet.streamId] = it
                armReceiveExpiryLocked(it)
                armAckCooldownLocked(it)
            }
        }

        if (state.totalFragments == 1) {
            completeReceive(state)
        }
    }

    private suspend fun handleFragment(packet: FluxProtocol.FluxPacket.Fragment) {
        var ackRequest: AckRequest? = null
        var complete: ReceiveState? = null
        synchronized(monitor) {
            val state = receives[packet.streamId] ?: return
            if (packet.seqNum !in 1 until state.totalFragments) return
            if (state.fragments.containsKey(packet.seqNum)) return

            state.fragments[packet.seqNum] = packet.payload.copyOf()
            state.expiryJob.cancel()
            armReceiveExpiryLocked(state)

            if (state.fragments.size == state.totalFragments) {
                complete = state
            } else {
                val maximum = state.fragments.keys.max()
                val missing = missingSequences(state, maximum)
                val unacknowledged = state.fragments.size - state.acknowledgedFragments
                val windowTriggered = state.windowSize != UNLIMITED_WINDOW &&
                    state.windowSize > 0 &&
                    (unacknowledged * 100 / state.windowSize) >= state.thresholdPercent
                if (missing.isNotEmpty()) {
                    state.pendingAck = true
                    state.ackGeneration++
                    if ((windowTriggered || state.ackAllowed) && !state.ackInFlight) {
                        ackRequest = prepareMissingAckLocked(state, maximum, missing)
                    }
                } else if (windowTriggered) {
                    state.pendingAck = true
                    state.ackGeneration++
                    if (!state.ackInFlight) {
                        ackRequest = preparePlainAckLocked(state, maximum)
                    }
                }
            }
        }

        ackRequest?.let { scheduleReceiveAck(it) }
        complete?.let { completeReceive(it) }
    }

    private fun completeReceive(state: ReceiveState) {
        val message: ByteArray
        val finalSack: ByteArray
        synchronized(monitor) {
            if (receives[state.streamId] !== state) return
            val ordered = (0 until state.totalFragments).map { sequence ->
                state.fragments[sequence]
                    ?: throw FluxTransferException("Flux receive completed with a missing fragment")
            }
            val assembledSize = ordered.sumOf { it.size }
            if (assembledSize != state.totalSize) {
                receives.remove(state.streamId)
                state.expiryJob.cancel()
                state.ackJob?.cancel()
                state.ackWriteJob?.cancel()
                throw FluxTransferException(
                    "Reassembled size $assembledSize does not match declared size ${state.totalSize}",
                )
            }
            message = ByteArray(assembledSize)
            var offset = 0
            ordered.forEach { fragment ->
                fragment.copyInto(message, offset)
                offset += fragment.size
            }
            receives.remove(state.streamId)
            state.expiryJob.cancel()
            state.ackJob?.cancel()
            state.ackWriteJob?.cancel()
            finalSack = FluxProtocol.buildSackPacket(state.streamId, state.totalFragments - 1)
        }

        launchOwned(controlJobs) {
            try {
                writerLock.withLock { writer.write(finalSack) }
            } catch (_: FluxTransferException) {
                // Final SACK is best effort once reassembly is complete.
            } catch (_: CancellationException) {
                if (coroutineContext[Job]?.isActive == false) return@launchOwned
            } catch (_: Throwable) {
                // Transport failure does not discard a complete inbound message.
            }
            if (coroutineContext[Job]?.isActive == true) {
                incoming.emit(message)
            }
        }
    }

    private fun dispatchAvailable(state: SendState) {
        val writes = synchronized(monitor) {
            if (!state.active) emptyList() else reserveWindowLocked(state)
        }
        enqueueWrites(writes)
    }

    private fun reserveWindowLocked(state: SendState): List<PacketWrite> {
        val writes = mutableListOf<PacketWrite>()
        val capacity = if (windowSize == UNLIMITED_WINDOW) state.packets.size else windowSize
        var inFlight = state.packets.indices.count {
            state.dispatched[it] && !state.acknowledged[it]
        }
        for (sequence in state.packets.indices) {
            if (inFlight >= capacity) break
            if (!state.dispatched[sequence] && !state.acknowledged[sequence]) {
                state.dispatched[sequence] = true
                writes += PacketWrite(state, sequence)
                inFlight++
            }
        }
        return writes
    }

    private fun startDataWriter(state: SendState) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (write in state.writeQueue) {
                    if (!writeDataPacket(write)) return@launch
                }
            } catch (cancellation: CancellationException) {
                synchronized(monitor) {
                    if (state.active) cancelSendLocked(state, cancellation)
                }
            }
        }
        val start = synchronized(monitor) {
            if (state.active && sends[state.streamId] === state) {
                state.writerJob = job
                true
            } else {
                false
            }
        }
        if (start) job.start() else job.cancel()
    }

    private suspend fun writeDataPacket(write: PacketWrite): Boolean {
        val shouldWrite = synchronized(monitor) {
            write.state.active && !write.state.acknowledged[write.sequence]
        }
        if (!shouldWrite) return true
        try {
            writerLock.withLock {
                val current = synchronized(monitor) {
                    write.state.active && !write.state.acknowledged[write.sequence]
                }
                if (current) {
                    writer.write(write.state.packets[write.sequence]) {
                        if (!write.state.started) {
                            write.state.started = true
                            write.state.onFirstPacketSent?.invoke()
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            synchronized(monitor) {
                if (write.state.active) cancelSendLocked(write.state, cancellation)
            }
            return false
        } catch (throwable: Throwable) {
            synchronized(monitor) {
                failSendLocked(
                    write.state,
                    "Failed to write Flux fragment ${write.sequence}",
                    throwable,
                )
            }
            return false
        }
        val (active, needsTimeout) = synchronized(monitor) {
            write.state.active to
                (write.state.active && !write.state.acknowledged[write.sequence])
        }
        if (needsTimeout) armFragmentTimeout(write.state, write.sequence)
        return active
    }

    private fun enqueueWrites(writes: List<PacketWrite>) {
        writes.forEach { write ->
            synchronized(monitor) {
                if (write.state.active && !write.state.acknowledged[write.sequence]) {
                    write.state.writeQueue.trySend(write)
                }
            }
        }
    }

    private fun scheduleDelivery(message: ByteArray) {
        launchOwned(deliveryJobs) {
            incoming.emit(message)
        }
    }

    private fun launchOwned(
        registry: MutableSet<Job>,
        startImmediately: Boolean = true,
        block: suspend CoroutineScope.() -> Unit,
    ): Job? {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            block()
        }
        val registered = synchronized(monitor) {
            if (closedCause == null) {
                registry += job
                true
            } else {
                false
            }
        }
        job.invokeOnCompletion {
            synchronized(monitor) { registry.remove(job) }
        }
        if (registered) {
            if (startImmediately) job.start()
            return job
        }
        job.cancel()
        return null
    }

    private fun armFragmentTimeout(state: SendState, sequence: Int) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(RETRANSMIT_TIMEOUT_MS)
            val write = synchronized(monitor) {
                if (!state.active || state.acknowledged[sequence]) return@launch
                if (state.retryCounts[sequence] >= MAX_RETRIES) {
                    failSendLocked(state, "Fragment $sequence exceeded $MAX_RETRIES retries")
                    null
                } else {
                    state.retryCounts[sequence]++
                    PacketWrite(state, sequence)
                }
            }
            if (write != null) {
                enqueueWrites(listOf(write))
            }
        }
        synchronized(monitor) {
            if (state.active && !state.acknowledged[sequence]) {
                state.timeoutJobs[sequence]?.cancel()
                state.timeoutJobs[sequence] = job
                job.start()
            } else {
                job.cancel()
            }
        }
    }

    private fun acknowledgeLocked(state: SendState, sequence: Int) {
        if (!state.acknowledged[sequence]) {
            state.acknowledged[sequence] = true
            state.timeoutJobs[sequence]?.cancel()
            state.timeoutJobs[sequence] = null
        }
    }

    private fun failSendLocked(
        state: SendState,
        message: String,
        cause: Throwable? = null,
    ): FluxTransferException {
        val failure = cause as? FluxTransferException ?: FluxTransferException(message, cause)
        if (state.active) {
            state.active = false
            state.timeoutJobs.forEach { it?.cancel() }
            state.writeQueue.close()
            state.writerJob?.cancel()
            state.completion.completeExceptionally(failure)
        }
        return failure
    }

    private fun cancelSendLocked(state: SendState, cancellation: CancellationException) {
        if (state.active) {
            state.active = false
            state.timeoutJobs.forEach { it?.cancel() }
            state.writeQueue.close()
            state.writerJob?.cancel()
            state.completion.completeExceptionally(cancellation)
        }
    }

    private fun armReceiveExpiryLocked(state: ReceiveState) {
        state.expiryJob = scope.launch {
            delay(RECEIVE_TIMEOUT_MS)
            synchronized(monitor) {
                if (receives[state.streamId] === state) {
                    receives.remove(state.streamId)
                    state.ackJob?.cancel()
                    state.ackWriteJob?.cancel()
                }
            }
        }
    }

    private fun armAckCooldownLocked(state: ReceiveState, afterSuccessfulAck: Boolean = true) {
        if (afterSuccessfulAck) {
            state.ackAllowed = false
            state.ackJob?.cancel()
        } else if (state.ackJob != null) {
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job] ?: return@launch
            delay(ACK_MIN_INTERVAL_MS)
            val request = synchronized(monitor) {
                if (receives[state.streamId] !== state) return@launch
                if (state.ackJob === ownJob) state.ackJob = null
                state.ackAllowed = true
                if (state.pendingAck && !state.ackInFlight) {
                    val maximum = state.fragments.keys.max()
                    val missing = missingSequences(state, maximum)
                    if (missing.isNotEmpty()) {
                        prepareMissingAckLocked(state, maximum, missing)
                    } else {
                        preparePlainAckLocked(state, maximum)
                    }
                } else {
                    null
                }
            }
            request?.let(::scheduleReceiveAck)
        }
        state.ackJob = job
        job.start()
    }

    private fun prepareMissingAckLocked(
        state: ReceiveState,
        maximum: Int,
        missing: List<Int>,
    ): AckRequest {
        state.ackInFlight = true
        return AckRequest(
            state = state,
            maximum = maximum,
            generation = state.ackGeneration,
            packet = buildSack(state, maximum, missing),
            retryOnFailure = true,
        )
    }

    private fun preparePlainAckLocked(state: ReceiveState, maximum: Int): AckRequest {
        state.ackInFlight = true
        return AckRequest(
            state = state,
            maximum = maximum,
            generation = state.ackGeneration,
            packet = FluxProtocol.buildSackPacket(state.streamId, maximum),
            retryOnFailure = false,
        )
    }

    private fun scheduleReceiveAck(request: AckRequest) {
        val job = launchOwned(controlJobs, startImmediately = false) {
            val ownJob = coroutineContext[Job] ?: return@launchOwned
            var succeeded = false
            try {
                succeeded = writerLock.withLock {
                    val current = synchronized(monitor) {
                        receives[request.state.streamId] === request.state &&
                            request.state.ackWriteJob === ownJob &&
                            request.state.ackInFlight
                    }
                    if (!current) return@withLock false
                    writer.write(request.packet)
                    true
                }
            } catch (_: Throwable) {
                // State below keeps failed missing SACKs eligible for retry.
            }
            synchronized(monitor) {
                val state = request.state
                if (receives[state.streamId] !== state) return@synchronized
                if (state.ackWriteJob !== ownJob) return@synchronized
                state.ackWriteJob = null
                state.ackInFlight = false
                if (succeeded) {
                    state.acknowledgedFragments =
                        (0..request.maximum).count { state.fragments.containsKey(it) }
                    if (state.ackGeneration == request.generation) {
                        state.pendingAck = false
                    }
                    armAckCooldownLocked(state, afterSuccessfulAck = true)
                } else {
                    if (request.retryOnFailure) state.pendingAck = true
                    armAckCooldownLocked(state, afterSuccessfulAck = false)
                }
            }
        }
        val start = synchronized(monitor) {
            if (receives[request.state.streamId] === request.state &&
                request.state.ackInFlight &&
                request.state.ackWriteJob == null
            ) {
                request.state.ackWriteJob = job
                true
            } else {
                false
            }
        }
        if (start) job?.start() else job?.cancel()
    }

    private fun buildSack(state: ReceiveState, maximum: Int, missing: List<Int>): ByteArray {
        val bitmap = ByteArray(FluxProtocol.SACK_BITMAP_SIZE)
        missing.forEach { sequence ->
            bitmap[sequence / 8] =
                (bitmap[sequence / 8].toInt() or (1 shl (sequence % 8))).toByte()
        }
        return FluxProtocol.buildSackPacket(state.streamId, maximum, bitmap)
    }

    private fun missingSequences(state: ReceiveState, maximum: Int): List<Int> =
        (0..maximum).filterNot { state.fragments.containsKey(it) }

    private fun buildReliablePackets(message: ByteArray, streamId: Int): List<ByteArray> {
        val startPayloadSize = mtu - FluxProtocol.ATT_HEADER_SIZE - FluxProtocol.START_HEADER_SIZE
        val fragmentPayloadSize = mtu - FluxProtocol.ATT_HEADER_SIZE - FluxProtocol.FRAGMENT_HEADER_SIZE
        require(startPayloadSize > 0 && fragmentPayloadSize > 0) { "MTU is too small for Flux headers" }

        val remainingAfterStart = (message.size - startPayloadSize).coerceAtLeast(0)
        val fragmentCount = 1 +
            if (remainingAfterStart == 0) 0
            else (remainingAfterStart + fragmentPayloadSize - 1) / fragmentPayloadSize
        require(fragmentCount <= MAX_FRAGMENTS) {
            "Message requires $fragmentCount fragments; maximum is $MAX_FRAGMENTS"
        }
        val advertisedWindow = if (windowSize == UNLIMITED_WINDOW) {
            UNLIMITED_WINDOW
        } else {
            minOf(windowSize, fragmentCount)
        }

        val packets = ArrayList<ByteArray>(fragmentCount)
        val firstEnd = minOf(startPayloadSize, message.size)
        packets += FluxProtocol.buildStartPacket(
            streamId = streamId,
            windowSize = advertisedWindow,
            windowThresholdPercent = thresholdPercent,
            startPayload = message.copyOfRange(0, firstEnd),
            totalFrags = fragmentCount,
            totalSize = message.size.toLong(),
        )
        var offset = firstEnd
        var sequence = 1
        while (offset < message.size) {
            val end = minOf(offset + fragmentPayloadSize, message.size)
            packets += FluxProtocol.buildFragmentPacket(
                streamId = streamId,
                seqNum = sequence,
                fragPayload = message.copyOfRange(offset, end),
            )
            offset = end
            sequence++
        }
        return packets
    }

    private fun allocateStreamIdLocked(): Int {
        repeat(STREAM_ID_COUNT) {
            val candidate = nextStreamId
            nextStreamId = (nextStreamId + 1) and STREAM_ID_MASK
            if (candidate !in sends) return candidate
        }
        error("No Flux stream ID available")
    }

    private fun validatePacketLength(bytes: ByteArray) {
        if (bytes.isEmpty()) throw FluxTransferException("Empty Flux packet")
        val type = bytes[0].toInt() and PACKET_TYPE_MASK
        val expected = when (type) {
            FluxProtocol.PACKET_TYPE_SINGLE,
            FluxProtocol.PACKET_TYPE_FRAGMENT,
            -> {
                if (bytes.size < FluxProtocol.FRAGMENT_HEADER_SIZE) {
                    throw FluxTransferException("Truncated Flux data packet")
                }
                FluxProtocol.FRAGMENT_HEADER_SIZE + readUInt16LE(bytes, 2)
            }

            FluxProtocol.PACKET_TYPE_START -> {
                if (bytes.size < FluxProtocol.START_HEADER_SIZE) {
                    throw FluxTransferException("Truncated Flux START packet")
                }
                if (bytes[2].toInt() and 0xff != 0) {
                    throw FluxTransferException("Flux START sequence must be zero")
                }
                FluxProtocol.START_HEADER_SIZE + readUInt16LE(bytes, 5)
            }

            FluxProtocol.PACKET_TYPE_SACK -> {
                if (bytes.size < FluxProtocol.SACK_HEADER_SIZE) {
                    throw FluxTransferException("Truncated Flux SACK packet")
                }
                val bitmapSize = readUInt16LE(bytes, 2)
                if (bitmapSize != 0 && bitmapSize != FluxProtocol.SACK_BITMAP_SIZE) {
                    throw FluxTransferException("Unsupported Flux SACK bitmap size $bitmapSize")
                }
                FluxProtocol.SACK_HEADER_SIZE + bitmapSize
            }

            else -> throw FluxTransferException("Unknown Flux packet type $type")
        }
        if (bytes.size != expected) {
            throw FluxTransferException(
                "Flux packet length ${bytes.size} does not match declared length $expected",
            )
        }
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun inactiveJob(): Job = Job().also { it.cancel() }

    private data class PacketWrite(
        val state: SendState,
        val sequence: Int,
    )

    private data class AckRequest(
        val state: ReceiveState,
        val maximum: Int,
        val generation: Int,
        val packet: ByteArray,
        val retryOnFailure: Boolean,
    )

    private class SendState(
        val streamId: Int,
        val packets: List<ByteArray>,
        val completion: CompletableDeferred<Unit>,
        val acknowledged: BooleanArray,
        val dispatched: BooleanArray,
        val retryCounts: IntArray,
        val timeoutJobs: Array<Job?>,
        val writeQueue: Channel<PacketWrite>,
        val onFirstPacketSent: (() -> Unit)?,
        val onAcknowledged: ((Int, Long) -> Unit)?,
        var started: Boolean = false,
        var writerJob: Job? = null,
        var active: Boolean = true,
    )

    private class ReceiveState(
        val streamId: Int,
        val totalFragments: Int,
        val totalSize: Int,
        val windowSize: Int,
        val thresholdPercent: Int,
        val fragments: MutableMap<Int, ByteArray>,
        var expiryJob: Job,
        var ackJob: Job? = null,
        var ackWriteJob: Job? = null,
        var acknowledgedFragments: Int = 0,
        var ackAllowed: Boolean = false,
        var pendingAck: Boolean = false,
        var ackInFlight: Boolean = false,
        var ackGeneration: Int = 0,
    )

    private companion object {
        const val MINIMUM_ATT_MTU = 23
        const val DEFAULT_WINDOW_SIZE = 6
        const val DEFAULT_THRESHOLD_PERCENT = 50
        const val UNLIMITED_WINDOW = 0xff
        const val MAX_FRAGMENTS = 128
        const val MAX_REASSEMBLY_SIZE = 64 * 1024
        const val MAX_CONCURRENT_SENDS = 2
        const val MAX_CONCURRENT_RECEIVES = 2
        const val STREAM_ID_MASK = 0x07
        const val STREAM_ID_COUNT = 8
        const val PACKET_TYPE_MASK = 0x07
        const val RETRANSMIT_TIMEOUT_MS = 500L
        const val RECEIVE_TIMEOUT_MS = 10_000L
        const val ACK_MIN_INTERVAL_MS = 100L
        const val MAX_RETRIES = 3
    }
}
