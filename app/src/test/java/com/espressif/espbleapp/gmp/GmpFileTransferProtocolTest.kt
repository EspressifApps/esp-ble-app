package com.espressif.espbleapp.gmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GmpFileTransferProtocolTest {
    @Test
    fun metaRequestMatchesEspGoldenVector() {
        val encoded = GmpFileTransferCodec.encodeMetaRequest(
            FtMetaRequest(
                transferId = 0x12345678,
                fileSize = 0xABCDEFL,
                blockSize = 4096,
                totalBlocks = 0x0ABD,
                sha256 = goldenSha256(),
                fileName = "test.txt",
            ),
        )
        assertArrayEquals(goldenMetaRequest(), encoded)
    }

    @Test
    fun dataRequestHeaderMatchesEspGoldenVector() {
        val encoded = GmpFileTransferCodec.encodeDataRequest(
            transferId = 0x12345678,
            blockIndex = 42,
            data = ByteArray(1024),
        )
        assertArrayEquals(goldenDataRequestHeader(), encoded.copyOfRange(0, 12))
    }

    @Test
    fun responsesAndTerminalPayloadsMatchEspVectors() {
        assertEquals(
            FtMetaResponse(
                transferId = 0x12345678,
                status = FtWireStatus.OK,
                reasonCode = FtReason.OK,
                acceptedBlockSize = 4096,
            ),
            GmpFileTransferCodec.decodeMetaResponse(goldenMetaResponse()),
        )
        assertEquals(
            FtDataResponse(
                transferId = 0x12345678,
                blockIndex = 42,
                status = FtWireStatus.OK,
                reasonCode = FtReason.OK,
            ),
            GmpFileTransferCodec.decodeDataResponse(goldenDataResponse()),
        )
        assertEquals(
            FtFinalConfirm(
                transferId = 0x12345678,
                status = FtWireStatus.OK,
                reasonCode = FtReason.OK,
                receivedSize = 0xABCDEFL,
                receivedSha256 = goldenSha256(),
                savedName = "test.txt",
            ),
            GmpFileTransferCodec.decodeFinalConfirm(goldenFinalConfirm()),
        )
        assertArrayEquals(
            goldenAbort(),
            GmpFileTransferCodec.encodeAbort(FtAbort(0x12345678, FtReason.ABORTED)),
        )
    }

    @Test
    fun rejectsInvalidMetaRequestInputs() {
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(0, 1, 4096, 1, goldenSha256(), "test.txt"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(1, 1, 0, 1, goldenSha256(), "test.txt"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(1, 1, 4096, 1, ByteArray(31), "test.txt"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(1, 0xABCDEFL, 4096, 1, goldenSha256(), "test.txt"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(1, 1, 1, 1, goldenSha256(), "a".repeat(33)),
            )
        }
    }

    @Test
    fun rejectsMetaRequestWhenComputedBlockCountExceedsUint32() {
        val fileSize = 0x1_0000_0000L
        val blockSize = 1
        val computedBlocks = GmpFileTransferCodec.blocksFor(fileSize, blockSize)
        assertEquals(0x1_0000_0000L, computedBlocks)

        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeMetaRequest(
                FtMetaRequest(
                    transferId = 1,
                    fileSize = fileSize,
                    blockSize = blockSize,
                    totalBlocks = computedBlocks,
                    sha256 = goldenSha256(),
                    fileName = "test.txt",
                ),
            )
        }
    }

    @Test
    fun rejectsMalformedMetaResponse() {
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeMetaResponse(goldenMetaResponse().copyOf(11))
        }
        val nonZeroPad = goldenMetaResponse().clone()
        nonZeroPad[5] = 1
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeMetaResponse(nonZeroPad)
        }
        val zeroId = goldenMetaResponse().clone()
        zeroId[0] = 0
        zeroId[1] = 0
        zeroId[2] = 0
        zeroId[3] = 0
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeMetaResponse(zeroId)
        }
        val badStatus = goldenMetaResponse().clone()
        badStatus[4] = 2
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeMetaResponse(badStatus)
        }
    }

    @Test
    fun rejectsMalformedDataResponse() {
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeDataResponse(goldenDataResponse().copyOf(11))
        }
        val nonZeroPad = goldenDataResponse().clone()
        nonZeroPad[9] = 1
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeDataResponse(nonZeroPad)
        }
        val failWithReasonOk = goldenDataResponse().clone()
        failWithReasonOk[8] = FtWireStatus.FAILED.toByte()
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeDataResponse(failWithReasonOk)
        }
        val failWithReason = goldenDataResponse().clone()
        failWithReason[8] = FtWireStatus.FAILED.toByte()
        failWithReason[11] = FtReason.LINK_ERROR.toByte()
        assertEquals(
            FtDataResponse(0x12345678, 42, FtWireStatus.FAILED, FtReason.LINK_ERROR),
            GmpFileTransferCodec.decodeDataResponse(failWithReason),
        )
    }

    @Test
    fun rejectsMalformedFinalConfirm() {
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeFinalConfirm(goldenFinalConfirm().copyOf(47))
        }
        val truncatedName = goldenFinalConfirm().copyOf(48)
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeFinalConfirm(truncatedName)
        }
        val badStatus = goldenFinalConfirm().clone()
        badStatus[4] = 2
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeFinalConfirm(badStatus)
        }
        val invalidUtf8 = goldenFinalConfirm().clone()
        invalidUtf8[5] = 1
        invalidUtf8[48] = 0x00
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeFinalConfirm(invalidUtf8.copyOf(49))
        }
    }

    @Test
    fun rejectsInvalidAbortAndAcceptsKnownReasons() {
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeAbort(FtAbort(0, FtReason.ABORTED))
        }
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.encodeAbort(FtAbort(1, FtReason.OK))
        }
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x00, 0x07, 0x00, 0x00),
            GmpFileTransferCodec.encodeAbort(FtAbort(0x12345678, FtReason.LINK_ERROR)),
        )
        val nonZeroReserved = goldenAbort().clone()
        nonZeroReserved[6] = 1
        assertFailsWith<IllegalArgumentException> {
            GmpFileTransferCodec.decodeAbort(nonZeroReserved)
        }
    }

    private fun goldenMetaRequest() = byteArrayOf(
        0x12, 0x34, 0x56, 0x78,
        0x00, 0x00, 0x00, 0x00, 0x00, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
        0x00, 0x00, 0x10, 0x00,
        0x00, 0x00, 0x0A, 0xBD.toByte(),
        *goldenSha256(),
        0x00, 0x00,
        0x08,
        0x00,
        't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
        '.'.code.toByte(), 't'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(),
    )

    private fun goldenMetaResponse() = byteArrayOf(
        0x12, 0x34, 0x56, 0x78,
        0x00,
        0x00,
        0x00, 0x00,
        0x00, 0x00, 0x10, 0x00,
    )

    private fun goldenDataRequestHeader() = byteArrayOf(
        0x12, 0x34, 0x56, 0x78,
        0x00, 0x00, 0x00, 0x2A,
        0x00, 0x00, 0x04, 0x00,
    )

    private fun goldenDataResponse() = byteArrayOf(
        0x12, 0x34, 0x56, 0x78,
        0x00, 0x00, 0x00, 0x2A,
        0x00,
        0x00,
        0x00, 0x00,
    )

    private fun goldenFinalConfirm() = byteArrayOf(
        0x12, 0x34, 0x56, 0x78,
        0x00,
        0x08,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
        *goldenSha256(),
        't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
        '.'.code.toByte(), 't'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(),
    )

    private fun goldenAbort() = byteArrayOf(
        0x12, 0x34, 0x56, 0x78,
        0x00, 0x0E,
        0x00, 0x00,
    )

    private fun goldenSha256() = ByteArray(32) { (it + 1).toByte() }
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
        throw AssertionError("Expected ${T::class.simpleName}")
    } catch (e: Throwable) {
        if (e is AssertionError && e.message?.startsWith("Expected") == true) throw e
        if (e !is T) {
            throw AssertionError("Expected ${T::class.simpleName}, got ${e::class.simpleName}", e)
        }
    }
}
