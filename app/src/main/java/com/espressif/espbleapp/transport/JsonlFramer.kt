package com.espressif.espbleapp.transport

import java.io.ByteArrayOutputStream

/**
 * Reassembles BLE ATT write/notify fragments into newline-delimited JSONL lines.
 * Uses a byte buffer so UTF-8 multibyte sequences are never split across chunks.
 */
class JsonlFramer {
    private val buffer = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        buffer.write(bytes)
        return drainCompleteLines()
    }

    fun feed(text: String): List<String> = feed(text.toByteArray(Charsets.UTF_8))

    fun reset() {
        buffer.reset()
    }

    private fun drainCompleteLines(): List<String> {
        val bytes = buffer.toByteArray()
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '\n'.code.toByte()) {
                var end = i
                if (end > start && bytes[end - 1] == '\r'.code.toByte()) {
                    end--
                }
                if (end > start) {
                    val line = String(bytes, start, end - start, Charsets.UTF_8).trim()
                    if (line.isNotEmpty()) {
                        lines += line
                    }
                }
                start = i + 1
            }
            i++
        }
        buffer.reset()
        if (start < bytes.size) {
            buffer.write(bytes, start, bytes.size - start)
        }
        return lines
    }
}
