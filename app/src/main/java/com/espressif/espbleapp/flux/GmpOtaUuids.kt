package com.espressif.espbleapp.flux

import java.util.UUID

object GmpOtaUuids {
    /** ESP-GMP-OTA Service UUID: a0eeffc0-504f-4b53-b62f-0a0000000001 */
    val SERVICE: UUID = UUID.fromString("a0eeffc0-504f-4b53-b62f-0a0000000001")

    /** ESP-GMP-OTA RX Characteristic UUID: a0eeffc0-504f-4b53-b62f-0a0000000002 */
    val RX_CHARACTERISTIC: UUID = UUID.fromString("a0eeffc0-504f-4b53-b62f-0a0000000002")

    /** ESP-GMP-OTA TX Characteristic UUID: a0eeffc0-504f-4b53-b62f-0a0000000003 */
    val TX_CHARACTERISTIC: UUID = UUID.fromString("a0eeffc0-504f-4b53-b62f-0a0000000003")

    const val DEVICE_NAME = "ESP-GMP-OTA"
}
