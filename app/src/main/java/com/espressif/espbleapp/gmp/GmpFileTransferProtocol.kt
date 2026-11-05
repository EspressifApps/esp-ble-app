package com.espressif.espbleapp.gmp

object FtCommand {
    const val META = 0x01
    const val DATA = 0x02
    const val FINAL = 0x03
    const val ABORT = 0x04
}

object FtWireStatus {
    const val OK = 0x00
    const val REJECTED = 0x01
    const val FAILED = 0x01
}

object FtReason {
    const val OK = 0x0000
    const val INVALID_MESSAGE = 0x0001
    const val INVALID_FILE_NAME = 0x0002
    const val FILE_TOO_LARGE = 0x0003
    const val NO_SPACE = 0x0004
    const val BUSY = 0x0005
    const val CAPABILITY_MISMATCH = 0x0006
    const val LINK_ERROR = 0x0007
    const val DATA_SEND_FAILED = 0x0008
    const val FILE_OPEN_FAILED = 0x0009
    const val FILE_READ_FAILED = 0x000A
    const val FILE_WRITE_FAILED = 0x000B
    const val TIMEOUT = 0x000C
    const val HASH_MISMATCH = 0x000D
    const val ABORTED = 0x000E
    const val INTERNAL_ERROR = 0x000F
    const val REJECTED_BY_APP = 0x0010
}

class FtMetaRequest(
    val transferId: Long,
    val fileSize: Long,
    val blockSize: Int,
    val totalBlocks: Long,
    val sha256: ByteArray,
    val fileName: String,
) {
    override fun equals(other: Any?) = other is FtMetaRequest &&
        transferId == other.transferId &&
        fileSize == other.fileSize &&
        blockSize == other.blockSize &&
        totalBlocks == other.totalBlocks &&
        sha256.contentEquals(other.sha256) &&
        fileName == other.fileName

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + blockSize
        result = 31 * result + totalBlocks.hashCode()
        result = 31 * result + sha256.contentHashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}

data class FtMetaResponse(
    val transferId: Long,
    val status: Int,
    val reasonCode: Int,
    val acceptedBlockSize: Int,
)

data class FtDataResponse(
    val transferId: Long,
    val blockIndex: Long,
    val status: Int,
    val reasonCode: Int,
)

class FtFinalConfirm(
    val transferId: Long,
    val status: Int,
    val reasonCode: Int,
    val receivedSize: Long,
    val receivedSha256: ByteArray,
    val savedName: String,
) {
    override fun equals(other: Any?) = other is FtFinalConfirm &&
        transferId == other.transferId &&
        status == other.status &&
        reasonCode == other.reasonCode &&
        receivedSize == other.receivedSize &&
        receivedSha256.contentEquals(other.receivedSha256) &&
        savedName == other.savedName

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + status
        result = 31 * result + reasonCode
        result = 31 * result + receivedSize.hashCode()
        result = 31 * result + receivedSha256.contentHashCode()
        result = 31 * result + savedName.hashCode()
        return result
    }
}

data class FtAbort(val transferId: Long, val reasonCode: Int)

object GmpFileTransferCodec {
    private const val META_HEADER_SIZE = 56
    private const val META_RESPONSE_SIZE = 12
    private const val DATA_HEADER_SIZE = 12
    private const val DATA_RESPONSE_SIZE = 12
    private const val FINAL_HEADER_SIZE = 48
    private const val ABORT_SIZE = 8
    private const val SHA256_LENGTH = 32
    private const val FILE_NAME_MAX_LEN = 32
    private const val U32_MAX = 0xFFFF_FFFFL

    fun encodeMetaRequest(value: FtMetaRequest): ByteArray {
        val nameBytes = value.fileName.toByteArray(Charsets.UTF_8)
        require(isValidName(nameBytes, mustBeNonEmpty = true))
        requireValidTransferId(value.transferId)
        require(value.fileSize >= 0)
        require(value.blockSize > 0)
        require(value.sha256.size == SHA256_LENGTH)
        val computedBlocks = blocksFor(value.fileSize, value.blockSize)
        require(computedBlocks <= U32_MAX)
        require(value.totalBlocks == computedBlocks)

        val out = ByteArray(META_HEADER_SIZE + nameBytes.size)
        putU32(out, 0, value.transferId)
        putU64(out, 4, value.fileSize)
        putU32(out, 12, value.blockSize.toLong())
        putU32(out, 16, value.totalBlocks)
        value.sha256.copyInto(out, destinationOffset = 20)
        putU16(out, 52, 0)
        out[54] = nameBytes.size.toByte()
        out[55] = 0
        nameBytes.copyInto(out, destinationOffset = META_HEADER_SIZE)
        return out
    }

    fun decodeMetaResponse(bytes: ByteArray): FtMetaResponse {
        require(bytes.size == META_RESPONSE_SIZE)
        val transferId = getU32(bytes, 0)
        val status = bytes[4].toInt() and 0xFF
        val pad = bytes[5].toInt() and 0xFF
        val reasonCode = getU16(bytes, 6)
        val acceptedBlockSize = getU32(bytes, 8).toInt()

        requireValidTransferId(transferId)
        require(pad == 0)
        require(isValidReason(reasonCode))
        validateMetaWireStatus(status, reasonCode, acceptedBlockSize)
        return FtMetaResponse(transferId, status, reasonCode, acceptedBlockSize)
    }

    fun encodeDataRequest(transferId: Long, blockIndex: Long, data: ByteArray): ByteArray {
        requireValidTransferId(transferId)
        requireValidU32(blockIndex)
        require(data.isNotEmpty())

        val out = ByteArray(DATA_HEADER_SIZE + data.size)
        putU32(out, 0, transferId)
        putU32(out, 4, blockIndex)
        putU32(out, 8, data.size.toLong())
        data.copyInto(out, destinationOffset = DATA_HEADER_SIZE)
        return out
    }

    fun decodeDataResponse(bytes: ByteArray): FtDataResponse {
        require(bytes.size == DATA_RESPONSE_SIZE)
        val transferId = getU32(bytes, 0)
        val blockIndex = getU32(bytes, 4)
        val status = bytes[8].toInt() and 0xFF
        val pad = bytes[9].toInt() and 0xFF
        val reasonCode = getU16(bytes, 10)

        requireValidTransferId(transferId)
        require(pad == 0)
        require(isValidReason(reasonCode))
        validateDataFinalStatus(status, reasonCode)
        return FtDataResponse(transferId, blockIndex, status, reasonCode)
    }

    fun decodeFinalConfirm(bytes: ByteArray): FtFinalConfirm {
        require(bytes.size >= FINAL_HEADER_SIZE)
        val nameLength = bytes[5].toInt() and 0xFF
        require(nameLength <= FILE_NAME_MAX_LEN)
        require(bytes.size == FINAL_HEADER_SIZE + nameLength)

        val transferId = getU32(bytes, 0)
        val status = bytes[4].toInt() and 0xFF
        val reasonCode = getU16(bytes, 6)
        val receivedSize = getU64(bytes, 8)
        val sha = bytes.copyOfRange(16, 48)
        val name = bytes.copyOfRange(FINAL_HEADER_SIZE, bytes.size)

        require(isValidName(name, mustBeNonEmpty = false))
        requireValidTransferId(transferId)
        require(isValidReason(reasonCode))
        validateDataFinalStatus(status, reasonCode)
        return FtFinalConfirm(
            transferId = transferId,
            status = status,
            reasonCode = reasonCode,
            receivedSize = receivedSize,
            receivedSha256 = sha,
            savedName = name.toString(Charsets.UTF_8),
        )
    }

    fun encodeAbort(value: FtAbort): ByteArray {
        requireValidTransferId(value.transferId)
        require(value.reasonCode != FtReason.OK)
        require(isValidReason(value.reasonCode))

        val out = ByteArray(ABORT_SIZE)
        putU32(out, 0, value.transferId)
        putU16(out, 4, value.reasonCode)
        putU16(out, 6, 0)
        return out
    }

    fun decodeAbort(bytes: ByteArray): FtAbort {
        require(bytes.size == ABORT_SIZE)
        val transferId = getU32(bytes, 0)
        val reasonCode = getU16(bytes, 4)
        val reserved = getU16(bytes, 6)

        requireValidTransferId(transferId)
        require(reasonCode != FtReason.OK)
        require(isValidReason(reasonCode))
        require(reserved == 0)
        return FtAbort(transferId, reasonCode)
    }

    fun blocksFor(fileSize: Long, blockSize: Int): Long {
        require(fileSize >= 0)
        require(blockSize > 0)
        return if (fileSize == 0L) 0 else 1 + (fileSize - 1) / blockSize
    }

    private fun validateMetaWireStatus(status: Int, reasonCode: Int, acceptedBlockSize: Int) {
        val accepted = status == FtWireStatus.OK
        require(
            (accepted && reasonCode == FtReason.OK && acceptedBlockSize > 0) ||
                (!accepted && status == FtWireStatus.REJECTED &&
                    reasonCode != FtReason.OK && acceptedBlockSize == 0),
        )
    }

    private fun validateDataFinalStatus(status: Int, reasonCode: Int) {
        val success = status == FtWireStatus.OK
        require(
            (success && reasonCode == FtReason.OK) ||
                (!success && status == FtWireStatus.FAILED && reasonCode != FtReason.OK),
        )
    }

    private fun requireValidTransferId(transferId: Long) {
        require(transferId in 1..U32_MAX)
    }

    private fun requireValidU32(value: Long) {
        require(value in 0..U32_MAX)
    }

    private fun isValidReason(reason: Int) = reason in FtReason.OK..FtReason.REJECTED_BY_APP

    private fun isValidName(bytes: ByteArray, mustBeNonEmpty: Boolean): Boolean {
        if ((mustBeNonEmpty && bytes.isEmpty()) || bytes.size > FILE_NAME_MAX_LEN) return false
        if (bytes.isNotEmpty()) {
            if ((bytes.size == 1 && bytes[0] == '.'.code.toByte()) ||
                bytes[0] == '/'.code.toByte() ||
                bytes[0] == '\\'.code.toByte()
            ) {
                return false
            }
            if (bytes.size >= 2 && bytes[1] == ':'.code.toByte()) {
                val c = bytes[0].toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z') return false
            }
        }

        var index = 0
        while (index < bytes.size) {
            val c = bytes[index].toInt() and 0xFF
            if (c == 0 || c < 0x20 || c == 0x7F || c == '/'.code || c == '\\'.code) {
                return false
            }
            if (c == '.'.code && index + 1 < bytes.size && bytes[index + 1] == '.'.code.toByte()) {
                return false
            }
            if (c < 0x80) {
                index++
                continue
            }

            val continuation: Int
            var codePoint: Int
            when {
                c in 0xC2..0xDF -> {
                    continuation = 1
                    codePoint = c and 0x1F
                }
                c in 0xE0..0xEF -> {
                    continuation = 2
                    codePoint = c and 0x0F
                }
                c in 0xF0..0xF4 -> {
                    continuation = 3
                    codePoint = c and 0x07
                }
                else -> return false
            }

            if (index + continuation >= bytes.size) return false
            for (step in 1..continuation) {
                val next = bytes[index + step].toInt() and 0xFF
                if ((next and 0xC0) != 0x80) return false
                codePoint = (codePoint shl 6) or (next and 0x3F)
            }
            if ((continuation == 2 && codePoint < 0x800) ||
                (continuation == 3 && codePoint < 0x10000) ||
                codePoint in 0xD800..0xDFFF ||
                codePoint > 0x10FFFF
            ) {
                return false
            }
            index += continuation + 1
        }
        return true
    }

    private fun putU16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 8).toByte()
        out[at + 1] = value.toByte()
    }

    private fun putU32(out: ByteArray, at: Int, value: Long) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    private fun putU64(out: ByteArray, at: Int, value: Long) {
        putU32(out, at, value ushr 32)
        putU32(out, at + 4, value and U32_MAX)
    }

    private fun getU16(input: ByteArray, at: Int): Int =
        ((input[at].toInt() and 0xFF) shl 8) or (input[at + 1].toInt() and 0xFF)

    private fun getU32(input: ByteArray, at: Int): Long =
        ((input[at].toLong() and 0xFF) shl 24) or
            ((input[at + 1].toLong() and 0xFF) shl 16) or
            ((input[at + 2].toLong() and 0xFF) shl 8) or
            (input[at + 3].toLong() and 0xFF)

    private fun getU64(input: ByteArray, at: Int): Long =
        (getU32(input, at) shl 32) or getU32(input, at + 4)
}
