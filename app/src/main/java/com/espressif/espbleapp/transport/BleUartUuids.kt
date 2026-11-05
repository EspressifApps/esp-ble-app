package com.espressif.espbleapp.transport

import java.util.UUID

object BleUartUuids {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEFAULT_NAME_PREFIX = "ESP-ESL-AP"
    const val FALLBACK_NAME_PREFIX = "ESP_BLE_UART"
    const val PREFERRED_MTU = 517
    /** Android `BluetoothGatt` rejects writes longer than GATT_MAX_ATTR_LEN. */
    const val GATT_MAX_ATTR_LEN = 512
}
