package com.espressif.espbleapp.flux

import java.util.UUID

object GmpFileTransferUuids {
    val SERVICE: UUID = UUID.fromString("01000008-5e5c-6fa8-7a46-43d210598d7a")
    val RX: UUID = UUID.fromString("02000008-5e5c-6fa8-7a46-43d210598d7a")
    val TX: UUID = UUID.fromString("03000008-5e5c-6fa8-7a46-43d210598d7a")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val PREFERRED_MTU = 512
    const val MINIMUM_MTU = 185
}

internal fun shouldIncludeBleScanResult(
    advertisedServiceUuids: Iterable<UUID>?,
    targetServiceUuid: UUID?,
): Boolean {
    if (targetServiceUuid == null) return true
    return advertisedServiceUuids?.any { it == targetServiceUuid } == true
}
