package com.espressif.espbleapp.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonlFramerTest {
    @Test
    fun reassemblesSplitLines() {
        val framer = JsonlFramer()
        assertEquals(emptyList<String>(), framer.feed("{\"a\":1"))
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), framer.feed("}\n{\"b\":2}\n"))
        assertEquals(listOf("{\"c\":3}"), framer.feed("{\"c\":3}\r\n"))
    }

    @Test
    fun reassemblesBleNotifySizedChunks() {
        val line = buildString {
            append("{\"v\":1,\"id\":\"req-21\",\"ok\":true,\"data\":{\"tags\":[{\"ble_addr\":\"60:55:f9:f6:a8:c2\",\"has_sensor\":true,\"num_displays\":0,\"pad\":\"")
            append("x".repeat(400))
            append("\"}]}}\n")
        }
        val bytes = line.toByteArray(Charsets.UTF_8)
        check(bytes.size > 506) { "payload too short: ${bytes.size}" }
        val framer = JsonlFramer()
        assertEquals(emptyList<String>(), framer.feed(bytes.copyOfRange(0, 253)))
        assertEquals(emptyList<String>(), framer.feed(bytes.copyOfRange(253, 506)))
        assertEquals(listOf(line.trim()), framer.feed(bytes.copyOfRange(506, bytes.size)))
    }
}
