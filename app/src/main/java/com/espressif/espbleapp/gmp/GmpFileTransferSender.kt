package com.espressif.espbleapp.gmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.min

interface FtFileSource {
    val displayName: String
    val size: Long
    suspend fun sha256(): ByteArray
    suspend fun open(): InputStream
}

sealed interface FtTransferState {
    data object Idle : FtTransferState
    data class Hashing(val bytesRead: Long, val totalBytes: Long) : FtTransferState
    data object Negotiating : FtTransferState
    data class Sending(
        val confirmedBytes: Long,
        val totalBytes: Long,
        val startedAtMs: Long,
    ) : FtTransferState
    data object Verifying : FtTransferState
    data class Completed(val bytes: Long, val elapsedMs: Long) : FtTransferState
    data object Cancelled : FtTransferState
    data class Failed(val message: String, val peerReasonCode: Int? = null) : FtTransferState
}

class GmpFileTransferSender(
    private val scope: CoroutineScope,
    private val link: GmpMessageLink,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val monitor = Any()
    private val _state = MutableStateFlow<FtTransferState>(FtTransferState.Idle)
    private var nextSequence = 1
    private var nextTransferId = 1L

    @Volatile
    private var active: Session? = null

    val state: StateFlow<FtTransferState> = _state.asStateFlow()

    suspend fun send(source: FtFileSource) {
        check(scope.isActive) { "Sender scope is not active" }
        val session = Session(nextTransferId())
        synchronized(monitor) {
            check(active == null) { "A file transfer is already active" }
            active = session
        }
        try {
            coroutineScope { runTransfer(source, session) }
        } catch (e: CancellationException) {
            terminate(
                session,
                FtTransferState.Cancelled,
                FtReason.ABORTED,
                notifyPeer = session.metadataSent && session.linkUsable,
            )
            throw e
        } finally {
            session.timeoutJob?.cancel()
            session.timeoutJob = null
            session.stream.closeQuietly()
            session.stream = null
            session.inFlight.clear()
            session.events.close()
            synchronized(monitor) {
                if (active === session) active = null
            }
        }
    }

    fun onIncoming(bytes: ByteArray) {
        val frame = try {
            GmpFrameCodec.decode(bytes)
        } catch (_: IllegalArgumentException) {
            return
        }
        onIncoming(frame)
    }

    fun onIncoming(frame: GmpFrame) {
        if (frame.groupId != GmpFrameCodec.FILE_TRANSFER_GROUP_ID || frame.flags != 0) return
        active?.events?.trySend(SenderEvent.Incoming(frame, clockMs()))
    }

    fun cancel() {
        active?.events?.trySend(SenderEvent.Cancel)
    }

    fun onLinkLost() {
        val session = active ?: return
        session.linkUsable = false
        session.events.trySend(SenderEvent.LinkLost)
    }

    private suspend fun CoroutineScope.runTransfer(source: FtFileSource, session: Session) {
        session.loopScope = this
        session.fileSize = source.size
        _state.value = FtTransferState.Hashing(0, source.size)
        val digest = hashUntilInterrupted(source, session) ?: return
        if (!drainPreMeta(session)) return
        session.expectedSha = digest
        _state.value = FtTransferState.Hashing(source.size, source.size)

        val proposedBlockSize = minOf(link.maxGmpPayload, MAX_EFFECTIVE_GMP_PAYLOAD) - DATA_HEADER_SIZE
        if (proposedBlockSize <= 0) {
            terminate(
                session,
                FtTransferState.Failed("GMP payload is too small for a DATA block", FtReason.CAPABILITY_MISMATCH),
                FtReason.CAPABILITY_MISMATCH,
                notifyPeer = false,
            )
            return
        }
        session.blockSize = proposedBlockSize
        session.totalBlocks = GmpFileTransferCodec.blocksFor(source.size, proposedBlockSize)
        if (!drainPreMeta(session)) return
        _state.value = FtTransferState.Negotiating

        val metaPayload = try {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(
                    transferId = session.transferId,
                    fileSize = source.size,
                    blockSize = proposedBlockSize,
                    totalBlocks = session.totalBlocks,
                    sha256 = session.expectedSha,
                    fileName = source.displayName,
                ),
            )
        } catch (e: IllegalArgumentException) {
            terminate(
                session,
                FtTransferState.Failed("Invalid META request: ${e.message}", FtReason.INVALID_FILE_NAME),
                FtReason.INVALID_FILE_NAME,
                notifyPeer = false,
            )
            return
        }
        if (!drainPreMeta(session)) return
        session.pendingMetaSeq = nextSequence()
        if (session.finished) return
        armTimer(session, TimerKind.META, META_TIMEOUT_MS)
        if (!sendCommandOrFail(session, FtCommand.META, metaPayload, session.pendingMetaSeq!!)) return
        session.metadataSent = true

        while (!session.finished) {
            val event = session.events.receiveCatching().getOrNull() ?: break
            dispatchEvent(source, session, event)
        }
    }

    private suspend fun hashUntilInterrupted(source: FtFileSource, session: Session): ByteArray? =
        coroutineScope {
            val hashJob = async {
                try {
                    source.sha256()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    terminate(
                        session,
                        FtTransferState.Failed("Hash failed: ${e.message}", FtReason.FILE_READ_FAILED),
                        FtReason.FILE_READ_FAILED,
                        notifyPeer = false,
                    )
                    null
                }
            }
            val watchJob = launch {
                while (!session.finished && isActive) {
                    val event = session.events.receiveCatching().getOrNull() ?: break
                    handlePreMetaEvent(session, event)
                    if (session.finished) hashJob.cancel()
                }
            }
            try {
                val digest = hashJob.await()
                watchJob.cancel()
                if (digest == null || !drainPreMeta(session)) null else digest
            } catch (e: CancellationException) {
                watchJob.cancel()
                if (session.finished) null else throw e
            } finally {
                watchJob.cancel()
                if (session.finished) hashJob.cancel()
            }
        }

    private suspend fun drainPreMeta(session: Session): Boolean {
        while (!session.finished) {
            val event = session.events.tryReceive().getOrNull() ?: break
            handlePreMetaEvent(session, event)
        }
        return !session.finished
    }

    private suspend fun handlePreMetaEvent(session: Session, event: SenderEvent) {
        when (event) {
            SenderEvent.Cancel,
            SenderEvent.LinkLost,
            is SenderEvent.Timeout,
            -> dispatchEvent(null, session, event)
            is SenderEvent.Incoming -> {
                if (event.frame.operation == GmpOperation.WRITE_REQUEST &&
                    event.frame.commandId == FtCommand.ABORT
                ) {
                    handlePeerAbort(session, event.frame)
                }
            }
        }
    }

    private suspend fun dispatchEvent(
        source: FtFileSource?,
        session: Session,
        event: SenderEvent,
    ) {
        when (event) {
            SenderEvent.Cancel -> terminate(
                session,
                FtTransferState.Cancelled,
                FtReason.ABORTED,
                notifyPeer = session.metadataSent && session.linkUsable,
            )
            SenderEvent.LinkLost -> terminate(
                session,
                FtTransferState.Failed("Link lost", FtReason.LINK_ERROR),
                FtReason.LINK_ERROR,
                notifyPeer = false,
            )
            is SenderEvent.Timeout -> {
                if (event.generation == session.timeoutGeneration && isTimeoutExpected(session, event.kind)) {
                    terminate(
                        session,
                        FtTransferState.Failed("Transfer timed out", FtReason.TIMEOUT),
                        FtReason.TIMEOUT,
                        notifyPeer = session.metadataSent && session.linkUsable,
                    )
                }
            }
            is SenderEvent.Incoming -> if (source != null) handleIncoming(source, session, event.frame, event.receivedAtMs)
        }
    }

    private suspend fun handleIncoming(
        source: FtFileSource,
        session: Session,
        frame: GmpFrame,
        receivedAtMs: Long,
    ) {
        if (session.finished) return
        when {
            frame.operation == GmpOperation.WRITE_RESPONSE &&
                frame.groupId == GmpFrameCodec.FILE_TRANSFER_GROUP_ID &&
                frame.commandId == FtCommand.META &&
                frame.sequence == session.pendingMetaSeq -> {
                handleMetaResponse(source, session, frame)
            }
            frame.operation == GmpOperation.WRITE_RESPONSE &&
                frame.groupId == GmpFrameCodec.FILE_TRANSFER_GROUP_ID &&
                frame.commandId == FtCommand.DATA -> {
                handleDataResponse(session, frame, receivedAtMs)
            }
            frame.operation == GmpOperation.WRITE_REQUEST &&
                frame.commandId == FtCommand.FINAL -> {
                handleFinalPacket(session, frame)
            }
            frame.operation == GmpOperation.WRITE_REQUEST &&
                frame.commandId == FtCommand.ABORT -> {
                handlePeerAbort(session, frame)
            }
        }
    }

    private suspend fun handleMetaResponse(
        source: FtFileSource,
        session: Session,
        frame: GmpFrame,
    ) {
        disarmTimer(session)
        session.pendingMetaSeq = null
        if (frame.status != GmpStatus.OK.code) {
            val reason = if (frame.status == GmpStatus.NOT_SUPPORTED.code) {
                FtReason.CAPABILITY_MISMATCH
            } else {
                FtReason.DATA_SEND_FAILED
            }
            terminate(
                session,
                FtTransferState.Failed("META GMP status ${frame.status}", reason),
                reason,
                notifyPeer = true,
            )
            return
        }
        val response = try {
            GmpFileTransferCodec.decodeMetaResponse(frame.payload)
        } catch (_: IllegalArgumentException) {
            terminate(
                session,
                FtTransferState.Failed("Invalid META response", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        if (response.transferId != session.transferId) {
            terminate(
                session,
                FtTransferState.Failed("META transfer ID mismatch", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        if (response.status == FtWireStatus.REJECTED) {
            terminate(
                session,
                FtTransferState.Failed("Peer rejected transfer", response.reasonCode),
                response.reasonCode,
                notifyPeer = false,
            )
            return
        }
        if (response.acceptedBlockSize == 0 || response.acceptedBlockSize > session.blockSize) {
            terminate(
                session,
                FtTransferState.Failed("Accepted block size is not usable", FtReason.CAPABILITY_MISMATCH),
                FtReason.CAPABILITY_MISMATCH,
                notifyPeer = true,
            )
            return
        }
        if (response.acceptedBlockSize < session.blockSize) {
            session.blockSize = response.acceptedBlockSize
            val blocks = GmpFileTransferCodec.blocksFor(session.fileSize, session.blockSize)
            if (blocks > U32_MAX) {
                terminate(
                    session,
                    FtTransferState.Failed("Recomputed block count exceeds u32", FtReason.FILE_TOO_LARGE),
                    FtReason.FILE_TOO_LARGE,
                    notifyPeer = true,
                )
                return
            }
            session.totalBlocks = blocks
        }
        if (session.totalBlocks == 0L) {
            enterWaitFinal(session)
            return
        }
        try {
            session.stream = source.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(
                session,
                FtTransferState.Failed("Failed to reopen source: ${e.message}", FtReason.FILE_OPEN_FAILED),
                FtReason.FILE_OPEN_FAILED,
                notifyPeer = true,
            )
            return
        }
        fillPipeline(session)
    }

    private suspend fun handleDataResponse(session: Session, frame: GmpFrame, receivedAtMs: Long) {
        val slot = session.inFlight.indexOfFirst { it.sequence == frame.sequence }
        if (slot < 0) return
        val done = session.inFlight[slot]
        if (frame.status == GmpStatus.BUSY.code) {
            val sequence = nextSequence()
            session.inFlight[slot] = done.copy(sequence = sequence)
            armTimer(session, TimerKind.DATA, DATA_TIMEOUT_MS)
            if (!sendCommandOrFail(
                    session,
                    FtCommand.DATA,
                    GmpFileTransferCodec.encodeDataRequest(session.transferId, done.index, done.bytes),
                    sequence,
                )
            ) {
                return
            }
            return
        }
        session.inFlight.removeAt(slot)
        if (frame.status != GmpStatus.OK.code) {
            terminate(
                session,
                FtTransferState.Failed("DATA GMP status ${frame.status}", FtReason.DATA_SEND_FAILED),
                FtReason.DATA_SEND_FAILED,
                notifyPeer = true,
            )
            return
        }
        val response = try {
            GmpFileTransferCodec.decodeDataResponse(frame.payload)
        } catch (_: IllegalArgumentException) {
            terminate(
                session,
                FtTransferState.Failed("Invalid DATA response", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        if (response.transferId != session.transferId || response.blockIndex != done.index) {
            terminate(
                session,
                FtTransferState.Failed("DATA response mismatch", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        if (response.status != FtWireStatus.OK) {
            terminate(
                session,
                FtTransferState.Failed("Peer failed DATA block", response.reasonCode),
                response.reasonCode,
                notifyPeer = true,
            )
            return
        }
        session.confirmedBytes = min(session.fileSize, session.confirmedBytes + done.bytes.size)
        if (session.confirmedBytes == session.fileSize) {
            session.lastDataAckAtMs = maxOf(receivedAtMs, session.lastDataFluxAckAtMs)
        }
        emitSending(session)
        if (session.inFlight.isNotEmpty()) {
            armTimer(session, TimerKind.DATA, DATA_TIMEOUT_MS)
        } else {
            disarmTimer(session)
        }
        if (allBlocksAcked(session)) {
            enterWaitFinal(session)
        } else {
            fillPipeline(session)
        }
    }

    private suspend fun handleFinalPacket(session: Session, frame: GmpFrame) {
        if (frame.payload.size < 4) return
        val transferId = readU32(frame.payload, 0)
        if (transferId != session.transferId) return
        if (frame.status != GmpStatus.OK.code) {
            terminate(
                session,
                FtTransferState.Failed("Invalid FINAL GMP status", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        val final = try {
            GmpFileTransferCodec.decodeFinalConfirm(frame.payload)
        } catch (_: IllegalArgumentException) {
            terminate(
                session,
                FtTransferState.Failed("Invalid FINAL confirm", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        if (final.transferId != session.transferId) {
            terminate(
                session,
                FtTransferState.Failed("FINAL transfer ID mismatch", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        val waiting = session.waitingFinal && session.inFlight.isEmpty()
        val earlyOk = (session.pendingMetaSeq != null && session.fileSize == 0L) ||
            (!session.waitingFinal && session.nextSendBlock >= session.totalBlocks)
        when {
            waiting -> handleFinalConfirm(session, final)
            earlyOk -> if (session.earlyFinal == null) session.earlyFinal = final
            session.earlyFinal == null -> terminate(
                session,
                FtTransferState.Failed("FINAL arrived before DATA completed", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
        }
    }

    private suspend fun handleFinalConfirm(session: Session, final: FtFinalConfirm) {
        disarmTimer(session)
        if (final.status != FtWireStatus.OK) {
            terminate(
                session,
                FtTransferState.Failed("Peer failed FINAL", final.reasonCode),
                final.reasonCode,
                notifyPeer = false,
            )
            return
        }
        if (final.receivedSize != session.fileSize) {
            terminate(
                session,
                FtTransferState.Failed("FINAL size mismatch", FtReason.INVALID_MESSAGE),
                FtReason.INVALID_MESSAGE,
                notifyPeer = true,
            )
            return
        }
        if (!final.receivedSha256.contentEquals(session.expectedSha)) {
            terminate(
                session,
                FtTransferState.Failed("FINAL SHA-256 mismatch", FtReason.HASH_MISMATCH),
                FtReason.HASH_MISMATCH,
                notifyPeer = true,
            )
            return
        }
        val elapsed = if (session.startedAtMs > 0L) {
            (session.lastDataAckAtMs - session.startedAtMs).coerceAtLeast(0)
        } else {
            0L
        }
        terminate(
            session,
            FtTransferState.Completed(session.fileSize, elapsed),
            FtReason.OK,
            notifyPeer = false,
        )
    }

    private suspend fun handlePeerAbort(session: Session, frame: GmpFrame) {
        val abort = try {
            GmpFileTransferCodec.decodeAbort(frame.payload)
        } catch (_: IllegalArgumentException) {
            return
        }
        if (abort.transferId != session.transferId) return
        terminate(
            session,
            FtTransferState.Failed("Peer aborted transfer", abort.reasonCode),
            abort.reasonCode,
            notifyPeer = false,
        )
    }

    private suspend fun fillPipeline(session: Session) {
        while (
            !session.finished &&
            session.nextSendBlock < session.totalBlocks &&
            session.inFlight.size < PIPELINE_DEPTH
        ) {
            val offset = session.nextSendBlock * session.blockSize
            val remaining = session.fileSize - offset
            val want = min(remaining, session.blockSize.toLong()).toInt()
            val bytes = ByteArray(want)
            val stream = session.stream
            if (stream == null || !stream.readFully(bytes)) {
                terminate(
                    session,
                    FtTransferState.Failed("File read failed", FtReason.FILE_READ_FAILED),
                    FtReason.FILE_READ_FAILED,
                    notifyPeer = true,
                )
                return
            }
            val sequence = nextSequence()
            val block = InFlightBlock(sequence, session.nextSendBlock, bytes)
            session.inFlight += block
            armTimer(session, TimerKind.DATA, DATA_TIMEOUT_MS)
            if (!sendCommandOrFail(
                    session,
                    FtCommand.DATA,
                    GmpFileTransferCodec.encodeDataRequest(session.transferId, block.index, block.bytes),
                    sequence,
                )
            ) {
                return
            }
            session.nextSendBlock++
        }
        if (!session.finished && session.nextSendBlock >= session.totalBlocks) {
            session.stream.closeQuietly()
            session.stream = null
        }
        if (allBlocksAcked(session)) {
            enterWaitFinal(session)
        }
    }

    private suspend fun enterWaitFinal(session: Session) {
        session.waitingFinal = true
        _state.value = FtTransferState.Verifying
        val early = session.earlyFinal
        if (early != null) {
            session.earlyFinal = null
            handleFinalConfirm(session, early)
            return
        }
        armTimer(session, TimerKind.FINAL, FINAL_TIMEOUT_MS)
    }

    private fun emitSending(session: Session) {
        _state.value = FtTransferState.Sending(
            confirmedBytes = session.confirmedBytes,
            totalBytes = session.fileSize,
            startedAtMs = session.startedAtMs,
        )
    }

    private fun allBlocksAcked(session: Session): Boolean =
        session.nextSendBlock >= session.totalBlocks && session.inFlight.isEmpty()

    private fun isTimeoutExpected(session: Session, kind: TimerKind): Boolean = when (kind) {
        TimerKind.META -> session.pendingMetaSeq != null
        TimerKind.DATA -> session.inFlight.isNotEmpty()
        TimerKind.FINAL -> session.waitingFinal
    }

    private fun armTimer(session: Session, kind: TimerKind, timeoutMs: Long) {
        session.timeoutJob?.cancel()
        val generation = ++session.timeoutGeneration
        session.timeoutJob = session.loopScope.launch {
            delay(timeoutMs)
            session.events.trySend(SenderEvent.Timeout(generation, kind))
        }
    }

    private fun disarmTimer(session: Session) {
        session.timeoutGeneration++
        session.timeoutJob?.cancel()
        session.timeoutJob = null
    }

    private suspend fun terminate(
        session: Session,
        state: FtTransferState,
        reason: Int,
        notifyPeer: Boolean,
    ) {
        if (session.finished) return
        session.finished = true
        disarmTimer(session)
        if (notifyPeer && session.metadataSent && session.linkUsable && reason != FtReason.OK) {
            withContext(NonCancellable) {
                runCatching { sendAbort(session.transferId, reason) }
            }
        }
        session.stream.closeQuietly()
        session.stream = null
        session.inFlight.clear()
        _state.value = state
        session.events.close()
    }

    private suspend fun sendCommandOrFail(
        session: Session,
        command: Int,
        payload: ByteArray,
        sequence: Int,
    ): Boolean = coroutineScope {
        val deferredIncoming = mutableListOf<SenderEvent.Incoming>()
        val sendJob = async {
            sendCommand(command, payload, sequence) {
                if (command == FtCommand.DATA && session.startedAtMs == 0L) {
                    session.startedAtMs = clockMs()
                    emitSending(session)
                }
            }
        }
        val watchJob = launch {
            while (!session.finished && isActive) {
                val event = session.events.receiveCatching().getOrNull() ?: break
                when (event) {
                    SenderEvent.Cancel,
                    SenderEvent.LinkLost,
                    is SenderEvent.Timeout,
                    -> {
                        dispatchEvent(null, session, event)
                        sendJob.cancel()
                    }
                    is SenderEvent.Incoming -> deferredIncoming += event
                }
            }
        }
        try {
            sendJob.await()
            if (command == FtCommand.DATA) session.lastDataFluxAckAtMs = clockMs()
            !session.finished
        } catch (e: CancellationException) {
            when {
                session.finished -> false
                !session.linkUsable -> {
                    terminate(
                        session,
                        FtTransferState.Failed("Link lost", FtReason.LINK_ERROR),
                        FtReason.LINK_ERROR,
                        notifyPeer = false,
                    )
                    false
                }
                else -> throw e
            }
        } catch (e: Exception) {
            terminate(
                session,
                FtTransferState.Failed("Link send failed: ${e.message}", FtReason.DATA_SEND_FAILED),
                FtReason.DATA_SEND_FAILED,
                notifyPeer = session.metadataSent && session.linkUsable,
            )
            false
        } finally {
            watchJob.cancel()
            if (!session.finished) {
                deferredIncoming.forEach { session.events.trySend(it) }
            }
        }
    }

    private suspend fun sendCommand(
        command: Int,
        payload: ByteArray,
        sequence: Int,
        onFirstPacketSent: (() -> Unit)? = null,
    ) {
        val frame = GmpFrame(
            operation = GmpOperation.WRITE_REQUEST,
            groupId = GmpFrameCodec.FILE_TRANSFER_GROUP_ID,
            sequence = sequence,
            commandId = command,
            flags = 0,
            status = 0,
            payload = payload,
        )
        link.send(GmpFrameCodec.encode(frame), onFirstPacketSent)
    }

    private suspend fun sendAbort(transferId: Long, reason: Int) {
        sendCommand(
            FtCommand.ABORT,
            GmpFileTransferCodec.encodeAbort(FtAbort(transferId, reason)),
            nextSequence(),
        )
    }

    private fun nextSequence(): Int = synchronized(monitor) {
        val value = nextSequence
        nextSequence = if (nextSequence == 0xFFFF) 1 else nextSequence + 1
        value
    }

    private fun nextTransferId(): Long = synchronized(monitor) {
        val value = nextTransferId
        nextTransferId = if (nextTransferId == U32_MAX) 1L else nextTransferId + 1
        value
    }

    private data class InFlightBlock(
        val sequence: Int,
        val index: Long,
        val bytes: ByteArray,
    )

    private class Session(
        val transferId: Long,
    ) {
        val events = Channel<SenderEvent>(Channel.UNLIMITED)
        val inFlight = mutableListOf<InFlightBlock>()
        lateinit var loopScope: CoroutineScope
        var finished = false
        var metadataSent = false
        @Volatile var linkUsable = true
        var stream: InputStream? = null
        var timeoutJob: Job? = null
        var timeoutGeneration = 0
        var pendingMetaSeq: Int? = null
        var nextSendBlock = 0L
        var totalBlocks = 0L
        var blockSize = 0
        var fileSize = 0L
        var confirmedBytes = 0L
        var expectedSha = ByteArray(0)
        var startedAtMs = 0L
        var lastDataAckAtMs = 0L
        var lastDataFluxAckAtMs = 0L
        var earlyFinal: FtFinalConfirm? = null
        var waitingFinal = false
    }

    private sealed interface SenderEvent {
        data class Incoming(val frame: GmpFrame, val receivedAtMs: Long) : SenderEvent
        data object Cancel : SenderEvent
        data object LinkLost : SenderEvent
        data class Timeout(val generation: Int, val kind: TimerKind) : SenderEvent
    }

    private enum class TimerKind { META, DATA, FINAL }

    companion object {
        private const val DATA_HEADER_SIZE = 12
        private const val MAX_EFFECTIVE_GMP_PAYLOAD = 4096
        // The current ESP file-transfer receiver accepts DATA blocks strictly in order.
        // Keep one GMP block outstanding; Flux fragments inside that block are still
        // streamed through the bounded BLE write window.
        private const val PIPELINE_DEPTH = 1
        private const val META_TIMEOUT_MS = 10_000L
        private const val DATA_TIMEOUT_MS = 10_000L
        private const val FINAL_TIMEOUT_MS = 20_000L
        private const val U32_MAX = 0xFFFF_FFFFL
    }
}

private fun InputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count <= 0) return false
        offset += count
    }
    return true
}

private fun InputStream?.closeQuietly() {
    try {
        this?.close()
    } catch (_: Exception) {
    }
}

private fun readU32(input: ByteArray, at: Int): Long =
    ((input[at].toLong() and 0xFF) shl 24) or
        ((input[at + 1].toLong() and 0xFF) shl 16) or
        ((input[at + 2].toLong() and 0xFF) shl 8) or
        (input[at + 3].toLong() and 0xFF)
