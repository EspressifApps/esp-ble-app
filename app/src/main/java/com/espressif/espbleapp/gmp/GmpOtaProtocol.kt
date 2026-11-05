package com.espressif.espbleapp.gmp

import java.security.MessageDigest

object GmpOtaProtocol {
    const val VER = 1

    const val OP_READ_REQ = 0x00
    const val OP_READ_RSP = 0x01
    const val OP_WRITE_REQ = 0x02
    const val OP_WRITE_RSP = 0x03

    const val GRP_OS = 0x00
    const val GRP_OTA = 0x01

    const val OS_CAP_QUERY = 0x01

    const val OTA_UPLOAD_DATA = 0x01
    const val OTA_UPLOAD_CONTROL = 0x02
    const val OTA_UPLOAD_QUERY = 0x03

    const val OTA_ACTION_START = 0
    const val OTA_ACTION_FINISH = 1
    const val OTA_ACTION_ABORT = 2

    const val OTA_FLAG_SHA256 = 0x0001
    const val OTA_DATA_FLAG_LAST_CHUNK = 0x01

    fun buildGmpFrame(
        op: Int,
        groupId: Int,
        seq: Int,
        commandId: Int,
        flags: Int = 0,
        status: Int = 0,
        payload: ByteArray? = null,
    ): ByteArray {
        return GmpFrameCodec.encode(
            GmpFrame(GmpOperation.fromCode(op), groupId, seq, commandId, flags, status, payload ?: byteArrayOf()),
        )
    }

    fun buildStartPayload(imageSize: Int): ByteArray {
        val pl = ByteArray(8)
        pl[0] = OTA_ACTION_START.toByte()
        pl[1] = 0 // session_id
        pl[2] = 0 // flags high
        pl[3] = 0 // flags low
        pl[4] = ((imageSize shr 24) and 0xFF).toByte()
        pl[5] = ((imageSize shr 16) and 0xFF).toByte()
        pl[6] = ((imageSize shr 8) and 0xFF).toByte()
        pl[7] = (imageSize and 0xFF).toByte()
        return pl
    }

    fun buildDataPayload(
        sessionId: Int,
        offset: Int,
        chunkData: ByteArray,
        isLastChunk: Boolean,
    ): ByteArray {
        val pl = ByteArray(8 + chunkData.size)
        pl[0] = sessionId.toByte()
        pl[1] = if (isLastChunk) OTA_DATA_FLAG_LAST_CHUNK.toByte() else 0
        pl[2] = ((chunkData.size shr 8) and 0xFF).toByte()
        pl[3] = (chunkData.size and 0xFF).toByte()
        pl[4] = ((offset shr 24) and 0xFF).toByte()
        pl[5] = ((offset shr 16) and 0xFF).toByte()
        pl[6] = ((offset shr 8) and 0xFF).toByte()
        pl[7] = (offset and 0xFF).toByte()
        System.arraycopy(chunkData, 0, pl, 8, chunkData.size)
        return pl
    }

    fun buildFinishPayload(
        sessionId: Int,
        imageSize: Int,
        sha256Digest: ByteArray,
    ): ByteArray {
        val pl = ByteArray(40)
        pl[0] = OTA_ACTION_FINISH.toByte()
        pl[1] = sessionId.toByte()
        pl[2] = 0x00 // flags high
        pl[3] = 0x01 // flags low (OTA_FLAG_SHA256)
        pl[4] = ((imageSize shr 24) and 0xFF).toByte()
        pl[5] = ((imageSize shr 16) and 0xFF).toByte()
        pl[6] = ((imageSize shr 8) and 0xFF).toByte()
        pl[7] = (imageSize and 0xFF).toByte()
        System.arraycopy(sha256Digest, 0, pl, 8, minOf(32, sha256Digest.size))
        return pl
    }

    fun calculateSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }
}
