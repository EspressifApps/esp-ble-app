package com.espressif.espbleapp.gmp

enum class GmpOperation(val code: Int) {
    READ_REQUEST(0), READ_RESPONSE(1), WRITE_REQUEST(2), WRITE_RESPONSE(3);
    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unsupported GMP operation: $code")
    }
}

enum class GmpStatus(val code: Int) {
    OK(0), UNKNOWN_COMMAND(1), BAD_STATE(2), BAD_LENGTH(3), CRC_ERROR(5),
    BUSY(6), NOT_SUPPORTED(7), NO_SESSION(8), SESSION_IN_USE(9), INTERNAL(0xFF)
}

data class GmpFrame(
    val operation: GmpOperation,
    val groupId: Int,
    val sequence: Int,
    val commandId: Int,
    val flags: Int = 0,
    val status: Int = 0,
    val payload: ByteArray,
) {
    override fun equals(other: Any?) = other is GmpFrame &&
        operation == other.operation && groupId == other.groupId &&
        sequence == other.sequence && commandId == other.commandId &&
        flags == other.flags && status == other.status &&
        payload.contentEquals(other.payload)
    override fun hashCode() = 31 * sequence + payload.contentHashCode()
}

object GmpFrameCodec {
    private const val HEADER = 10
    const val FILE_TRANSFER_GROUP_ID = 0x08

    fun encode(frame: GmpFrame): ByteArray {
        requireStackFrame(frame.groupId, frame.sequence, frame.flags)
        require(frame.commandId in 0..0xFF && frame.status in 0..0xFF)
        require(frame.payload.size <= 0xFFFF)
        val out = ByteArray(HEADER + frame.payload.size)
        out[0] = (0x10 or frame.operation.code).toByte()
        out[1] = frame.groupId.toByte()
        putU16(out, 2, frame.sequence)
        out[4] = 0
        out[5] = frame.commandId.toByte()
        out[6] = frame.flags.toByte()
        out[7] = frame.status.toByte()
        putU16(out, 8, frame.payload.size)
        frame.payload.copyInto(out, HEADER)
        return out
    }

    fun decode(bytes: ByteArray): GmpFrame {
        require(bytes.size >= HEADER && (bytes[0].toInt() ushr 4) == 1)
        require(bytes[4].toInt() == 0)
        val length = getU16(bytes, 8)
        require(bytes.size == HEADER + length)
        val groupId = bytes[1].toInt() and 0xFF
        val sequence = getU16(bytes, 2)
        val flags = bytes[6].toInt() and 0xFF
        requireStackFrame(groupId, sequence, flags)
        return GmpFrame(
            GmpOperation.fromCode(bytes[0].toInt() and 0x0F),
            groupId,
            sequence,
            bytes[5].toInt() and 0xFF,
            flags,
            bytes[7].toInt() and 0xFF,
            bytes.copyOfRange(HEADER, bytes.size),
        )
    }

    private fun requireStackFrame(groupId: Int, sequence: Int, flags: Int) {
        require(groupId in 0..0xFF) { "GMP group must fit one byte" }
        require(sequence in 1..0xFFFF) {
            "GMP frame sequence must be non-zero"
        }
        require(flags in 0..0xFF) { "GMP flags must fit one byte" }
    }

    private fun putU16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 8).toByte()
        out[at + 1] = value.toByte()
    }
    private fun getU16(input: ByteArray, at: Int) =
        ((input[at].toInt() and 0xFF) shl 8) or (input[at + 1].toInt() and 0xFF)
}
