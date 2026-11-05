package com.espressif.espbleapp.gmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GmpOtaUploadProgress(
    val isUploading: Boolean = false,
    val confirmedBytes: Int = 0,
    val totalBytes: Int = 0,
    val status: String = "",
    val dataTransferStartedAtMs: Long = 0L,
    val dataTransferElapsedMs: Long = 0L,
)

/** OTA profile above GMP: capability negotiation, DATA acknowledgements, then FINISH/reboot. */
class GmpOtaSender(
    private val client: GmpRequestLink,
    private val log: (String) -> Unit,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _gmpOtaProgress = MutableStateFlow(GmpOtaUploadProgress())
    val progress: StateFlow<GmpOtaUploadProgress> = _gmpOtaProgress
    private var nextGmpOtaSequence = 1

    suspend fun upload(firmwareBytes: ByteArray) {
        if (_gmpOtaProgress.value.isUploading || firmwareBytes.isEmpty()) return
        try {
            val totalSize = firmwareBytes.size
            _gmpOtaProgress.value = GmpOtaUploadProgress(
                isUploading = true,
                totalBytes = totalSize,
                status = "正在查询设备 OTA 能力…",
            )
            log("Starting GMP OTA Update for firmware image (${totalSize} bytes)...")

            // Device responses confirm GMP-level processing, not merely BLE transmission.
            // Query capabilities first, matching the reference OTA host implementation.
            val capSeq = nextSequence()
            val capFrame = GmpOtaProtocol.buildGmpFrame(
                op = GmpOtaProtocol.OP_READ_REQ,
                groupId = GmpOtaProtocol.GRP_OS,
                seq = capSeq,
                commandId = GmpOtaProtocol.OS_CAP_QUERY,
            )
            val capResponse = sendAndAwaitResponse(
                capFrame,
                requestSeq = capSeq,
                expectedOp = GmpOtaProtocol.OP_READ_RSP,
                groupId = GmpOtaProtocol.GRP_OS,
                commandId = GmpOtaProtocol.OS_CAP_QUERY,
            )
            if (capResponse.payload.size < 12) error("设备返回的 OTA 能力数据不完整")
            if ((capResponse.payload[10].toInt() and 0xFF) == 0) error("设备未声明支持 GMP OTA")
            val maxGmpPayload = minOf(client.maxGmpPayload, capResponse.payload.readUInt32BE(6))
            val maxChunkByPayload = (maxGmpPayload - 8).coerceAtLeast(0)
            val capChunkSize = (maxChunkByPayload / 256) * 256
            if (capChunkSize <= 0) error("设备支持的 GMP 数据包容量不足以传输 OTA 固件")

            _gmpOtaProgress.value = _gmpOtaProgress.value.copy(status = "正在开始 OTA…")
            val startPl = GmpOtaProtocol.buildStartPayload(totalSize)
            val startSeq = nextSequence()
            val startFrame = GmpOtaProtocol.buildGmpFrame(
                op = GmpOtaProtocol.OP_WRITE_REQ,
                groupId = GmpOtaProtocol.GRP_OTA,
                seq = startSeq,
                commandId = GmpOtaProtocol.OTA_UPLOAD_CONTROL,
                payload = startPl,
            )
            val startResponse = sendAndAwaitResponse(
                startFrame,
                requestSeq = startSeq,
                expectedOp = GmpOtaProtocol.OP_WRITE_RSP,
                groupId = GmpOtaProtocol.GRP_OTA,
                commandId = GmpOtaProtocol.OTA_UPLOAD_CONTROL,
            )
            if (startResponse.payload.size < 3) error("OTA START 响应缺少 session 信息")
            val sessionId = startResponse.payload[0].toInt() and 0xFF
            val chunkHint = startResponse.payload.readUInt16BE(1)
            val chunkSize = minOf(capChunkSize, chunkHint.takeIf { it > 0 } ?: capChunkSize)
                .let { (it / 256) * 256 }
            if (chunkSize <= 0) error("设备建议的 OTA 分块大小无效")

            var offset = 0
            while (offset < totalSize) {
                val len = minOf(chunkSize, totalSize - offset)
                val chunkBytes = firmwareBytes.copyOfRange(offset, offset + len)
                val isLast = (offset + len >= totalSize)

                val dataPl = GmpOtaProtocol.buildDataPayload(
                    sessionId = sessionId,
                    offset = offset,
                    chunkData = chunkBytes,
                    isLastChunk = isLast,
                )
                val dataSeq = nextSequence()
                val dataFrame = GmpOtaProtocol.buildGmpFrame(
                    op = GmpOtaProtocol.OP_WRITE_REQ,
                    groupId = GmpOtaProtocol.GRP_OTA,
                    seq = dataSeq,
                    commandId = GmpOtaProtocol.OTA_UPLOAD_DATA,
                    payload = dataPl,
                )

                sendAndAwaitResponse(
                    dataFrame,
                    requestSeq = dataSeq,
                    expectedOp = GmpOtaProtocol.OP_WRITE_RSP,
                    groupId = GmpOtaProtocol.GRP_OTA,
                    commandId = GmpOtaProtocol.OTA_UPLOAD_DATA,
                    onRequestSending = {
                        if (_gmpOtaProgress.value.dataTransferStartedAtMs == 0L) {
                            val startedAtMs = clockMs()
                            _gmpOtaProgress.value = _gmpOtaProgress.value.copy(
                                dataTransferStartedAtMs = startedAtMs,
                            )
                        }
                    },
                )
                offset += len
                val dataTransferElapsedMs = if (offset == totalSize) {
                    (clockMs() - _gmpOtaProgress.value.dataTransferStartedAtMs).coerceAtLeast(0L)
                } else {
                    _gmpOtaProgress.value.dataTransferElapsedMs
                }
                _gmpOtaProgress.value = _gmpOtaProgress.value.copy(
                    confirmedBytes = offset,
                    dataTransferElapsedMs = dataTransferElapsedMs,
                    status = "设备已确认接收 $offset / $totalSize 字节",
                )
            }

            log("All OTA DATA chunks sent ($totalSize bytes). Calculating SHA-256 digest...")

            val sha256Digest = GmpOtaProtocol.calculateSha256(firmwareBytes)
            val finishPl = GmpOtaProtocol.buildFinishPayload(
                sessionId = sessionId,
                imageSize = totalSize,
                sha256Digest = sha256Digest,
            )
            val finishSeq = nextSequence()
            val finishFrame = GmpOtaProtocol.buildGmpFrame(
                op = GmpOtaProtocol.OP_WRITE_REQ,
                groupId = GmpOtaProtocol.GRP_OTA,
                seq = finishSeq,
                commandId = GmpOtaProtocol.OTA_UPLOAD_CONTROL,
                payload = finishPl,
            )

            _gmpOtaProgress.value = _gmpOtaProgress.value.copy(status = "数据已全部确认，等待设备校验并应用固件…")
            sendAndAwaitResponse(
                finishFrame,
                requestSeq = finishSeq,
                expectedOp = GmpOtaProtocol.OP_WRITE_RSP,
                groupId = GmpOtaProtocol.GRP_OTA,
                commandId = GmpOtaProtocol.OTA_UPLOAD_CONTROL,
            )

            val elapsed = _gmpOtaProgress.value.dataTransferElapsedMs.coerceAtLeast(1L)
            val avgSpeed = (totalSize * 1000.0) / elapsed
            val done = "设备已确认固件完整，正在应用并重启。耗时 ${elapsed} ms (${avgSpeed / 1024.0} KB/s)"
            _gmpOtaProgress.value = _gmpOtaProgress.value.copy(
                isUploading = false,
                confirmedBytes = totalSize,
                status = done,
            )
            log("GMP OTA completed: $done")

        } catch (e: Exception) {
            _gmpOtaProgress.value = _gmpOtaProgress.value.copy(
                isUploading = false,
                status = "OTA 失败：${e.message ?: "等待设备响应超时"}",
            )
            log("GMP OTA Upload failed: ${e.message}")
            if (e is CancellationException) throw e
        }
    }

    private fun nextSequence(): Int {
        val current = nextGmpOtaSequence
        nextGmpOtaSequence = if (current >= 0xFFFF) 1 else current + 1
        return current
    }

    private suspend fun sendAndAwaitResponse(
        frame: ByteArray,
        requestSeq: Int,
        expectedOp: Int,
        groupId: Int,
        commandId: Int,
        onRequestSending: (() -> Unit)? = null,
    ): GmpFrame {
        val request = GmpFrameCodec.decode(frame)
        check(request.sequence == requestSeq && request.groupId == groupId && request.commandId == commandId)
        val response = client.request(request, onRequestSending)
        check(response.operation.code == expectedOp)
        return response
    }
}

private fun ByteArray.readUInt16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.readUInt32BE(offset: Int): Int {
    val value = ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)
    return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
