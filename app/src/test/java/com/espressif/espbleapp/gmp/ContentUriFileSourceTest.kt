package com.espressif.espbleapp.gmp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class ContentUriFileSourceTest {
    @Test
    fun knownSizeUsesMetadataAndDoesNotOpenUntilHash() {
        val bytes = ByteArray(12_345) { it.toByte() }
        val access = FakeUriContentAccess(
            key = KEY,
            metadata = UriFileMetadata("payload.bin", bytes.size.toLong()),
            bytes = bytes,
        )

        val source = ContentUriFileSource(access, KEY)

        assertEquals("payload.bin", source.displayName)
        assertEquals(bytes.size.toLong(), source.size)
        assertEquals(0, access.openCount)
    }

    @Test
    fun unknownSizeIsMeasuredByStreaming() {
        val bytes = ByteArray(20_000) { it.toByte() }
        val access = FakeUriContentAccess(
            key = KEY,
            metadata = UriFileMetadata("unknown.bin", size = null),
            bytes = bytes,
        )

        val source = ContentUriFileSource(access, KEY)

        assertEquals(bytes.size.toLong(), source.size)
        assertEquals(1, access.openCount)
        assertTrue(access.maxReadLength <= STREAM_BUFFER)
        assertTrue(access.readCallCount > 1)
    }

    @Test
    fun sha256StreamsAndReopenServesSendWithoutMaterializing() = runTest {
        val bytes = ByteArray(16_384) { index -> (index * 31).toByte() }
        val access = FakeUriContentAccess(
            key = KEY,
            metadata = UriFileMetadata("hash.bin", bytes.size.toLong()),
            bytes = bytes,
        )
        val source = ContentUriFileSource(access, KEY)

        val digest = source.sha256()
        assertArrayEquals(sha256(bytes), digest)
        assertEquals(1, access.openCount)
        assertTrue(access.maxReadLength <= STREAM_BUFFER)
        assertTrue(access.readCallCount > 1)

        source.open().use { first ->
            assertArrayEquals(bytes, first.readAllViaBuffer())
        }
        source.open().use { second ->
            assertArrayEquals(bytes, second.readAllViaBuffer())
        }
        assertEquals(3, access.openCount)
        assertEquals(listOf(KEY, KEY, KEY), access.openedKeys)
    }

    @Test
    fun hashThenSendOpensOnceEach() = runTest {
        val bytes = ByteArray(4096) { 7 }
        val access = FakeUriContentAccess(
            key = KEY,
            metadata = UriFileMetadata("once.bin", bytes.size.toLong()),
            bytes = bytes,
        )
        val source = ContentUriFileSource(access, KEY)

        source.sha256()
        source.open().close()

        assertEquals(2, access.openCount)
    }

    @Test
    fun acceptsOneAndThirtyTwoByteUtf8Names() {
        val oneByte = "a"
        val thirtyTwoBytes = "a".repeat(32)
        val mixed = "测".repeat(10) + "ab"

        assertEquals(1, oneByte.toByteArray(Charsets.UTF_8).size)
        assertEquals(32, thirtyTwoBytes.toByteArray(Charsets.UTF_8).size)
        assertEquals(32, mixed.toByteArray(Charsets.UTF_8).size)

        ContentUriFileSource(namedAccess(oneByte), KEY).also {
            assertEquals(oneByte, it.displayName)
        }
        ContentUriFileSource(namedAccess(thirtyTwoBytes), KEY).also {
            assertEquals(thirtyTwoBytes, it.displayName)
        }
        ContentUriFileSource(namedAccess(mixed), KEY).also {
            assertEquals(mixed, it.displayName)
        }
    }

    @Test
    fun rejectsEmptyAndOverlongUtf8Names() {
        val overlongAscii = "a".repeat(33)
        val overlongUtf8 = "测".repeat(11)
        assertEquals(33, overlongAscii.toByteArray(Charsets.UTF_8).size)
        assertEquals(33, overlongUtf8.toByteArray(Charsets.UTF_8).size)

        assertFailsWith<IllegalArgumentException> {
            ContentUriFileSource(namedAccess(""), KEY)
        }
        assertFailsWith<IllegalArgumentException> {
            ContentUriFileSource(namedAccess(overlongAscii), KEY)
        }
        assertFailsWith<IllegalArgumentException> {
            ContentUriFileSource(namedAccess(overlongUtf8), KEY)
        }
    }

    private fun namedAccess(displayName: String): FakeUriContentAccess =
        FakeUriContentAccess(
            key = KEY,
            metadata = UriFileMetadata(displayName, 0L),
            bytes = ByteArray(0),
        )
}

private const val KEY = "content://esl.test/file"
private const val STREAM_BUFFER = 8 * 1024

private class FakeUriContentAccess(
    private val key: String,
    private val metadata: UriFileMetadata,
    private val bytes: ByteArray,
) : UriContentAccess {
    var openCount = 0
        private set
    var maxReadLength = 0
        private set
    var readCallCount = 0
        private set
    val openedKeys = mutableListOf<String>()

    override fun metadata(requested: String): UriFileMetadata {
        require(requested == key)
        return metadata
    }

    override fun open(requested: String): InputStream {
        require(requested == key)
        openCount++
        openedKeys += requested
        return object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readCallCount++
                maxReadLength = maxOf(maxReadLength, len)
                return super.read(b, off, len)
            }
        }
    }
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun InputStream.readAllViaBuffer(): ByteArray {
    val buffer = ByteArray(STREAM_BUFFER)
    val out = ArrayList<Byte>()
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        repeat(n) { index -> out += buffer[index] }
    }
    return out.toByteArray()
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return
        throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.name}", error)
    }
    throw AssertionError("Expected ${T::class.java.simpleName} to be thrown")
}
