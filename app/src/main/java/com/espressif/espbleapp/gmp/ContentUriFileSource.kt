package com.espressif.espbleapp.gmp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.security.MessageDigest

interface UriContentAccess {
    fun metadata(key: String): UriFileMetadata
    fun open(key: String): InputStream
}

data class UriFileMetadata(val displayName: String, val size: Long?)

class ContentUriFileSource(
    private val access: UriContentAccess,
    private val key: String,
) : FtFileSource {
    constructor(context: Context, uri: Uri) : this(
        AndroidUriContentAccess(context),
        uri.toString(),
    )

    private val metadata = access.metadata(key)

    override val displayName: String = requireValidDisplayName(metadata.displayName)
    override val size: Long by lazy { metadata.size?.takeIf { it >= 0 } ?: measureSize() }

    override suspend fun sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        stream { buffer, count -> digest.update(buffer, 0, count) }
        return digest.digest()
    }

    override suspend fun open(): InputStream = access.open(key)

    private fun measureSize(): Long {
        var total = 0L
        stream { _, count -> total += count }
        return total
    }

    private fun stream(consume: (ByteArray, Int) -> Unit) {
        access.open(key).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                consume(buffer, count)
            }
        }
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 8 * 1024
        const val FILE_NAME_MAX_BYTES = 32

        fun requireValidDisplayName(name: String): String {
            val bytes = name.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= FILE_NAME_MAX_BYTES) {
                "Display name must be 1..$FILE_NAME_MAX_BYTES UTF-8 bytes, got ${bytes.size}"
            }
            return name
        }
    }
}

private class AndroidUriContentAccess(
    context: Context,
) : UriContentAccess {
    private val resolver = context.applicationContext.contentResolver

    override fun metadata(key: String): UriFileMetadata {
        val uri = Uri.parse(key)
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return UriFileMetadata("", null)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex).takeIf { it >= 0 }
            } else {
                null
            }
            return UriFileMetadata(name, size)
        }
        return UriFileMetadata("", null)
    }

    override fun open(key: String): InputStream {
        val uri = Uri.parse(key)
        return resolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open input stream for $uri")
    }
}
