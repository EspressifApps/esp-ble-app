package com.espressif.espbleapp.flux

import java.util.UUID

/** GATT addressing only. Flux does not need to know which application uses the link. */
data class FluxGattProfile(
    val service: UUID,
    val rx: UUID,
    val tx: UUID,
    val minimumMtu: Int = 23,
)
