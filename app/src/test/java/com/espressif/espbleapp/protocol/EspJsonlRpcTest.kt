package com.espressif.espbleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EspJsonlRpcTest {
    @Test
    fun parsesOkResponse() {
        val msg = EspJsonlRpc.parseIncoming("""{"v":1,"id":"req-1","ok":true,"data":{"status":"accepted"}}""")
        val resp = msg as IncomingMessage.RpcResponse
        assertEquals("req-1", resp.id)
        assertTrue(resp.ok)
        assertEquals("accepted", resp.data.string("status"))
    }

    @Test
    fun parsesEvent() {
        val msg = EspJsonlRpc.parseIncoming("""{"event":"log","level":"W","msg":"timeout"}""")
        val event = msg as IncomingMessage.Event
        assertEquals("log", event.name)
    }
}
