package com.espressif.espbleapp.repository

import android.content.Context
import com.espressif.espbleapp.BuildConfig
import com.espressif.espbleapp.protocol.EslRpcClient
import com.espressif.espbleapp.transport.ApTransport
import com.espressif.espbleapp.transport.BleUartTransport
import com.espressif.espbleapp.transport.MockApTransport

class ApRepository(context: Context, forceMock: Boolean? = null) {
    private val useMock = forceMock ?: BuildConfig.USE_MOCK_TRANSPORT

    val transport: ApTransport = if (useMock) {
        MockApTransport()
    } else {
        BleUartTransport(context.applicationContext)
    }

    val rpc: EslRpcClient = EslRpcClient(transport)

    val isMock: Boolean get() = useMock

    fun start() {
        rpc.start()
    }

    fun stop() {
        rpc.stop()
    }
}
