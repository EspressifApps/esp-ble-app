package com.espressif.espbleapp.flux

import kotlin.math.min

object FluxProtocol {
    const val PACKET_TYPE_SINGLE = 0x1
    const val PACKET_TYPE_FRAGMENT = 0x2
    const val PACKET_TYPE_SACK = 0x3
    const val PACKET_TYPE_START = 0x4

    const val ATT_HEADER_SIZE = 3
    const val SINGLE_HEADER_SIZE = 4
    const val START_HEADER_SIZE = 13
    const val FRAGMENT_HEADER_SIZE = 4
    const val SACK_HEADER_SIZE = 4
    const val SACK_BITMAP_SIZE = 16

    sealed class FluxPacket {
        abstract val streamId: Int

        data class Single(
            override val streamId: Int,
            val seqNum: Int,
            val payload: ByteArray,
        ) : FluxPacket() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Single
                return streamId == other.streamId && seqNum == other.seqNum && payload.contentEquals(other.payload)
            }

            override fun hashCode(): Int {
                var result = streamId
                result = 31 * result + seqNum
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Start(
            override val streamId: Int,
            val windowSize: Int,
            val windowThresholdPercent: Int,
            val payloadSize: Int,
            val totalFrags: Int,
            val totalSize: Long,
            val payload: ByteArray,
        ) : FluxPacket() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Start
                return streamId == other.streamId &&
                    windowSize == other.windowSize &&
                    windowThresholdPercent == other.windowThresholdPercent &&
                    payloadSize == other.payloadSize &&
                    totalFrags == other.totalFrags &&
                    totalSize == other.totalSize &&
                    payload.contentEquals(other.payload)
            }

            override fun hashCode(): Int {
                var result = streamId
                result = 31 * result + windowSize
                result = 31 * result + windowThresholdPercent
                result = 31 * result + payloadSize
                result = 31 * result + totalFrags
                result = 31 * result + totalSize.hashCode()
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Fragment(
            override val streamId: Int,
            val seqNum: Int,
            val payloadSize: Int,
            val payload: ByteArray,
        ) : FluxPacket() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Fragment
                return streamId == other.streamId && seqNum == other.seqNum && payloadSize == other.payloadSize && payload.contentEquals(other.payload)
            }

            override fun hashCode(): Int {
                var result = streamId
                result = 31 * result + seqNum
                result = 31 * result + payloadSize
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Sack(
            override val streamId: Int,
            val ackNum: Int,
            val bitmapSize: Int,
            val bitmap: ByteArray?,
        ) : FluxPacket() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Sack
                return streamId == other.streamId && ackNum == other.ackNum && bitmapSize == other.bitmapSize && (bitmap?.contentEquals(other.bitmap ?: byteArrayOf()) ?: (other.bitmap == null))
            }

            override fun hashCode(): Int {
                var result = streamId
                result = 31 * result + ackNum
                result = 31 * result + bitmapSize
                result = 31 * result + (bitmap?.contentHashCode() ?: 0)
                return result
            }
        }
    }

    fun packControl(packetType: Int, streamId: Int = 0, protoVer: Int = 0): Byte {
        val b = ((protoVer and 0x03) shl 6) or ((streamId and 0x07) shl 3) or (packetType and 0x07)
        return b.toByte()
    }

    fun parsePacket(data: ByteArray): FluxPacket? {
        if (data.isEmpty()) return null
        val control = data[0].toInt() and 0xFF
        val streamId = (control shr 3) and 0x07
        val packetType = control and 0x07

        return when (packetType) {
            PACKET_TYPE_SINGLE -> {
                if (data.size < SINGLE_HEADER_SIZE) return null
                val seqNum = data[1].toInt() and 0xFF
                val size = readUInt16LE(data, 2)
                val payload = if (data.size >= SINGLE_HEADER_SIZE + size) {
                    data.copyOfRange(SINGLE_HEADER_SIZE, SINGLE_HEADER_SIZE + size)
                } else {
                    data.copyOfRange(SINGLE_HEADER_SIZE, data.size)
                }
                FluxPacket.Single(streamId = streamId, seqNum = seqNum, payload = payload)
            }

            PACKET_TYPE_START -> {
                if (data.size < START_HEADER_SIZE) return null
                val windowSize = data[3].toInt() and 0xFF
                val windowThresh = data[4].toInt() and 0xFF
                val payloadSize = readUInt16LE(data, 5)
                val totalFrags = readUInt16LE(data, 7)
                val totalSize = readUInt32LE(data, 9)
                val payload = if (data.size >= START_HEADER_SIZE + payloadSize) {
                    data.copyOfRange(START_HEADER_SIZE, START_HEADER_SIZE + payloadSize)
                } else {
                    data.copyOfRange(START_HEADER_SIZE, data.size)
                }
                FluxPacket.Start(
                    streamId = streamId,
                    windowSize = windowSize,
                    windowThresholdPercent = windowThresh,
                    payloadSize = payloadSize,
                    totalFrags = totalFrags,
                    totalSize = totalSize,
                    payload = payload,
                )
            }

            PACKET_TYPE_FRAGMENT -> {
                if (data.size < FRAGMENT_HEADER_SIZE) return null
                val seqNum = data[1].toInt() and 0xFF
                val size = readUInt16LE(data, 2)
                val payload = if (data.size >= FRAGMENT_HEADER_SIZE + size) {
                    data.copyOfRange(FRAGMENT_HEADER_SIZE, FRAGMENT_HEADER_SIZE + size)
                } else {
                    data.copyOfRange(FRAGMENT_HEADER_SIZE, data.size)
                }
                FluxPacket.Fragment(
                    streamId = streamId,
                    seqNum = seqNum,
                    payloadSize = size,
                    payload = payload,
                )
            }

            PACKET_TYPE_SACK -> {
                if (data.size < SACK_HEADER_SIZE) return null
                val ackNum = data[1].toInt() and 0xFF
                val bitmapSize = readUInt16LE(data, 2)
                val bitmap = if (bitmapSize > 0 && data.size >= SACK_HEADER_SIZE + bitmapSize) {
                    data.copyOfRange(SACK_HEADER_SIZE, SACK_HEADER_SIZE + bitmapSize)
                } else {
                    null
                }
                FluxPacket.Sack(
                    streamId = streamId,
                    ackNum = ackNum,
                    bitmapSize = bitmapSize,
                    bitmap = bitmap,
                )
            }

            else -> null
        }
    }

    fun buildSinglePacket(streamId: Int = 0, payload: ByteArray): ByteArray {
        val pkt = ByteArray(SINGLE_HEADER_SIZE + payload.size)
        pkt[0] = packControl(PACKET_TYPE_SINGLE, streamId)
        pkt[1] = 0 // seqNum
        writeUInt16LE(pkt, 2, payload.size)
        System.arraycopy(payload, 0, pkt, SINGLE_HEADER_SIZE, payload.size)
        return pkt
    }

    fun buildStartPacket(
        streamId: Int = 0,
        windowSize: Int = 6,
        windowThresholdPercent: Int = 80,
        startPayload: ByteArray,
        totalFrags: Int,
        totalSize: Long,
    ): ByteArray {
        val pkt = ByteArray(START_HEADER_SIZE + startPayload.size)
        pkt[0] = packControl(PACKET_TYPE_START, streamId)
        pkt[1] = 0 // reserved
        pkt[2] = 0 // seqNum
        pkt[3] = windowSize.toByte()
        pkt[4] = windowThresholdPercent.toByte()
        writeUInt16LE(pkt, 5, startPayload.size)
        writeUInt16LE(pkt, 7, totalFrags)
        writeUInt32LE(pkt, 9, totalSize)
        System.arraycopy(startPayload, 0, pkt, START_HEADER_SIZE, startPayload.size)
        return pkt
    }

    fun buildFragmentPacket(
        streamId: Int = 0,
        seqNum: Int,
        fragPayload: ByteArray,
    ): ByteArray {
        val pkt = ByteArray(FRAGMENT_HEADER_SIZE + fragPayload.size)
        pkt[0] = packControl(PACKET_TYPE_FRAGMENT, streamId)
        pkt[1] = seqNum.toByte()
        writeUInt16LE(pkt, 2, fragPayload.size)
        System.arraycopy(fragPayload, 0, pkt, FRAGMENT_HEADER_SIZE, fragPayload.size)
        return pkt
    }

    fun buildSackPacket(
        streamId: Int = 0,
        ackNum: Int,
        bitmap: ByteArray? = null,
    ): ByteArray {
        val bmpSize = bitmap?.size ?: 0
        val pkt = ByteArray(SACK_HEADER_SIZE + bmpSize)
        pkt[0] = packControl(PACKET_TYPE_SACK, streamId)
        pkt[1] = ackNum.toByte()
        writeUInt16LE(pkt, 2, bmpSize)
        if (bitmap != null && bmpSize > 0) {
            System.arraycopy(bitmap, 0, pkt, SACK_HEADER_SIZE, bmpSize)
        }
        return pkt
    }

    fun buildPacketsForData(
        data: ByteArray,
        mtu: Int = 512,
        streamId: Int = 0,
        windowSize: Int = 6,
        windowThresholdPercent: Int = 80,
    ): List<ByteArray> {
        val effectiveMtu = maxOf(23, mtu)
        val maxSinglePayload = effectiveMtu - ATT_HEADER_SIZE - SINGLE_HEADER_SIZE

        if (data.size <= maxSinglePayload) {
            return listOf(buildSinglePacket(streamId, data))
        }

        val maxStartPayload = maxOf(1, effectiveMtu - ATT_HEADER_SIZE - START_HEADER_SIZE)
        val maxFragPayload = maxOf(1, effectiveMtu - ATT_HEADER_SIZE - FRAGMENT_HEADER_SIZE)

        val firstChunkLen = min(data.size, maxStartPayload)
        val remaining = data.size - firstChunkLen
        val remainingFrags = if (remaining > 0) (remaining + maxFragPayload - 1) / maxFragPayload else 0
        val totalFrags = 1 + remainingFrags

        val packets = ArrayList<ByteArray>(totalFrags)

        val startPayload = data.copyOfRange(0, firstChunkLen)
        packets.add(
            buildStartPacket(
                streamId = streamId,
                windowSize = windowSize,
                windowThresholdPercent = windowThresholdPercent,
                startPayload = startPayload,
                totalFrags = totalFrags,
                totalSize = data.size.toLong(),
            ),
        )

        var offset = firstChunkLen
        var seqNum = 1
        while (offset < data.size) {
            val len = min(maxFragPayload, data.size - offset)
            val fragPayload = data.copyOfRange(offset, offset + len)
            packets.add(
                buildFragmentPacket(
                    streamId = streamId,
                    seqNum = seqNum,
                    fragPayload = fragPayload,
                ),
            )
            offset += len
            seqNum++
        }

        return packets
    }

    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toLong() and 0xFF
        val b1 = data[offset + 1].toLong() and 0xFF
        val b2 = data[offset + 2].toLong() and 0xFF
        val b3 = data[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun writeUInt16LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeUInt32LE(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

class FluxReassembler {
    private var totalFrags: Int = 0
    private var totalSize: Long = 0L
    private var buffer: ByteArray? = null
    private var receivedFrags = 0
    private var startChunkLen = 0
    private var maxFragPayload = 0

    fun feed(packet: FluxProtocol.FluxPacket): ByteArray? {
        when (packet) {
            is FluxProtocol.FluxPacket.Single -> {
                return packet.payload
            }

            is FluxProtocol.FluxPacket.Start -> {
                totalFrags = packet.totalFrags
                totalSize = packet.totalSize
                buffer = ByteArray(totalSize.toInt())
                startChunkLen = packet.payload.size
                System.arraycopy(packet.payload, 0, buffer!!, 0, startChunkLen)
                receivedFrags = 1
                if (receivedFrags == totalFrags) {
                    val result = buffer
                    reset()
                    return result
                }
            }

            is FluxProtocol.FluxPacket.Fragment -> {
                val buf = buffer ?: return null
                if (packet.seqNum <= 0) return null
                // Infer frag size if not set yet
                if (maxFragPayload == 0) {
                    maxFragPayload = packet.payload.size
                }
                val offset = startChunkLen + (packet.seqNum - 1) * maxFragPayload
                if (offset + packet.payload.size <= buf.size) {
                    System.arraycopy(packet.payload, 0, buf, offset, packet.payload.size)
                    receivedFrags++
                    if (receivedFrags >= totalFrags) {
                        val result = buffer
                        reset()
                        return result
                    }
                }
            }

            is FluxProtocol.FluxPacket.Sack -> {
                // SACK packet received from remote
            }
        }
        return null
    }

    fun reset() {
        totalFrags = 0
        totalSize = 0L
        buffer = null
        receivedFrags = 0
        startChunkLen = 0
        maxFragPayload = 0
    }
}
