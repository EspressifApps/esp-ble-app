package com.espressif.espbleapp.flux

import java.util.UUID

object FluxUuids {
    /** 16-bit SPP Service UUID: 0xABF0 */
    val SERVICE: UUID = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")

    /** 16-bit SPP Service Characteristic UUID: 0xABF1 */
    val CHARACTERISTIC: UUID = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor (CCCD): 0x2902 */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEFAULT_NAME_PREFIX = "ESP_FLUX"
    const val PREFERRED_MTU = 512
}
