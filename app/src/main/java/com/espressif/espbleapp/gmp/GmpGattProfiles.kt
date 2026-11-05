package com.espressif.espbleapp.gmp

import com.espressif.espbleapp.flux.FluxGattProfile
import com.espressif.espbleapp.flux.GmpFileTransferUuids
import com.espressif.espbleapp.flux.GmpOtaUuids

object GmpGattProfiles {
    val FILE_TRANSFER = FluxGattProfile(
        GmpFileTransferUuids.SERVICE, GmpFileTransferUuids.RX, GmpFileTransferUuids.TX,
        GmpFileTransferUuids.MINIMUM_MTU,
    )
    val OTA = FluxGattProfile(GmpOtaUuids.SERVICE, GmpOtaUuids.RX_CHARACTERISTIC, GmpOtaUuids.TX_CHARACTERISTIC)
}

