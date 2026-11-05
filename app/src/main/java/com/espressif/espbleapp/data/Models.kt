package com.espressif.espbleapp.data

data class ApStatus(
    val started: Boolean = false,
    val scanning: Boolean = false,
    val pawrActive: Boolean = false,
    val tagCount: Int = 0,
    val selectedIndex: Int = 0,
    val phoneLink: String = "disconnected",
    val lastError: String? = null,
    /** recovery-v1; missing on old AP → "unknown" */
    val bootId: String = "",
    val recoveryPhase: String = "unknown",
    val health: String = "unknown",
    val retryable: Boolean? = null,
    val errorCode: String? = null,
    val retryCount: Int? = null,
    val nextRetryUptimeMs: Long? = null,
    val recommendedAction: String? = null,
    val orphanBonds: List<OrphanBond> = emptyList(),
)

data class OrphanBond(
    val addr: String = "",
    val addrType: Int = 0,
    val firstSeenUptimeMs: Long = 0,
)

data class TagRecord(
    val bleAddr: String = "",
    val addrType: Int = 0,
    val eslId: Int = 0,
    val groupId: Int = 0,
    val eslAddr: Int = 0,
    /** Legacy: synchronized only when product_state==online && time_synced */
    val state: String = "unknown",
    val currentImageIndex: Int = 0,
    val hasDisplay: Boolean = false,
    val hasLed: Boolean = false,
    val hasSensor: Boolean = false,
    val numDisplays: Int = 0,
    val maxImageIndex: Int = 0,
    val valid: Boolean = true,
    /** recovery-v1 optional fields (defaults for old AP) */
    val productState: String = "unknown",
    val eslRuntimeState: String = "unknown",
    val associated: Boolean = false,
    val desiredState: String = "unknown",
    val timeSynced: Boolean = false,
    val recoveryGeneration: Int = 0,
    val lastSeenUptimeMs: Long = 0,
    val retryCount: Int = 0,
    val failureReason: String? = null,
    val provisionPhase: String = "unknown",
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val displayType: Int? = null,
) {
    /** PAwR controls allowed when online (recovery-v1) or legacy synchronized. */
    val isControllable: Boolean
        get() = when {
            productState != "unknown" -> productState == "online"
            else -> state == "synchronized"
        }

    /** Mosaico Full RGB badge display advertised by ESL Display Information. */
    val supportsBadgeImage: Boolean
        get() = displayWidth == 480 && displayHeight == 480 && displayType == 0x0B
}

data class HelloInfo(
    val protocol: String = "",
    val fwVer: String = "",
    val deviceName: String = "",
    val maxChunk: Int = 256,
    val maxLine: Int = 512,
    val encrypted: Boolean = false,
    val capabilities: List<String> = emptyList(),
) {
    val supportsGroupBroadcast: Boolean
        get() = capabilities.contains("group-broadcast")

    /** Mosaico AP advertises the 480×480 RGB565 badge upload/transfer commands. */
    val supportsBadgeImage: Boolean
        get() = capabilities.contains("upload_badge_image_begin") &&
            capabilities.contains("transfer_badge_image_to_tag")

    /** After upload_*_begin, image bytes go raw on NUS RX (no JSONL/Base64 chunks). */
    val supportsRawUpload: Boolean
        get() = capabilities.contains("upload_raw")
}

/** ESL_ID 0xFF — PAwR group broadcast address (PROTOCOL group-broadcast). */
const val ESL_GROUP_BROADCAST_ID = 255

/** ESL_ID 0xFE — AP self-display for transfer_badge_image_to_tag local=true. */
const val ESL_AP_LOCAL_ID = 254

enum class ControlScope {
    Selected,
    Group,
}

/** Stable control-page identity. Never a list_tags index. */
sealed class ControlTarget {
    data object Ap : ControlTarget()
    data class Tag(val eslId: Int, val groupId: Int) : ControlTarget()
}

sealed class ControlResolveResult {
    data class Ok(val eslId: Int, val groupId: Int) : ControlResolveResult()
    data class Fail(val reason: ControlResolveFail) : ControlResolveResult()
}

enum class ControlResolveFail {
    NoTarget,
    ApNotAllowed,
    TagGone,
    GroupNotReady,
}

object ControlTargetResolver {
    fun findTag(target: ControlTarget, tags: List<TagRecord>): TagRecord? {
        val key = target as? ControlTarget.Tag ?: return null
        return tags.firstOrNull { it.eslId == key.eslId && it.groupId == key.groupId }
    }

    fun retainOrFallback(current: ControlTarget, tags: List<TagRecord>): ControlTarget {
        return when (current) {
            ControlTarget.Ap -> ControlTarget.Ap
            is ControlTarget.Tag -> if (findTag(current, tags) != null) current else ControlTarget.Ap
        }
    }

    fun resolveDisplay(target: ControlTarget, scope: ControlScope, supportsGroup: Boolean): ControlResolveResult {
        return when (target) {
            ControlTarget.Ap -> ControlResolveResult.Ok(ESL_AP_LOCAL_ID, 0)
            is ControlTarget.Tag -> {
                val useGroup = scope == ControlScope.Group && supportsGroup
                if (useGroup) {
                    ControlResolveResult.Ok(ESL_GROUP_BROADCAST_ID, target.groupId)
                } else {
                    ControlResolveResult.Ok(target.eslId, target.groupId)
                }
            }
        }
    }

    fun resolveTagPawr(
        target: ControlTarget,
        scope: ControlScope,
        tags: List<TagRecord>,
        supportsGroup: Boolean,
        groupHasOnline: (Int) -> Boolean,
    ): ControlResolveResult {
        return when (target) {
            ControlTarget.Ap -> ControlResolveResult.Fail(ControlResolveFail.ApNotAllowed)
            is ControlTarget.Tag -> {
                val useGroup = scope == ControlScope.Group && supportsGroup
                if (useGroup) {
                    if (!groupHasOnline(target.groupId)) {
                        ControlResolveResult.Fail(ControlResolveFail.GroupNotReady)
                    } else {
                        ControlResolveResult.Ok(ESL_GROUP_BROADCAST_ID, target.groupId)
                    }
                } else if (findTag(target, tags) == null) {
                    ControlResolveResult.Fail(ControlResolveFail.TagGone)
                } else {
                    ControlResolveResult.Ok(target.eslId, target.groupId)
                }
            }
        }
    }
}

enum class TaskKind {
    Ping,
    LedControl,
    DisplayImage,
    RefreshDisplay,
    UploadImage,
    TransferImage,
    Unassociate,
    Scan,
    Other,
}

enum class TaskStatus {
    Pending,
    Accepted,
    Running,
    Ok,
    Timeout,
    Error,
    Cancelled,
}

data class TaskRecord(
    val id: String,
    val kind: TaskKind,
    val label: String,
    val status: TaskStatus = TaskStatus.Pending,
    val tagEslId: Int? = null,
    val tagGroupId: Int? = null,
    val detail: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class LogLine(
    val level: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class ScannedAp(
    val name: String?,
    val address: String,
    val rssi: Int,
)

data class ScannedTag(
    val bleAddr: String,
    val addrType: Int = 0,
    val rssi: Int = 0,
    val associated: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis(),
)

data class ImageUploadSession(
    val imageId: Long,
    val version: Long,
    val dataLen: Int,
    val checksum: String,
    val format: String = "raw",
)

/** UI state for custom epaper image compose / upload. */
data class ImageEditState(
    val sourceUri: String? = null,
    val preview: android.graphics.Bitmap? = null,
    val raw: ByteArray? = null,
    val imageIndex: Int = 0,
    val scaleModeOrdinal: Int = 0,
    val threshold: Int = 128,
    val dither: Boolean = true,
    val invert: Boolean = false,
    val converting: Boolean = false,
    val error: String? = null,
) {
    val readyToUpload: Boolean get() = raw != null && raw.size == 4736 && !converting && error == null
}

/** UI state for the independently encoded 480×480 RGB565 badge path. */
data class BadgeEditState(
    val sourceUri: String? = null,
    val preview: android.graphics.Bitmap? = null,
    val raw: ByteArray? = null,
    val slotIndex: Int = 0,
    val converting: Boolean = false,
    val error: String? = null,
) {
    val readyToUpload: Boolean
        get() = raw?.size == 480 * 480 * 2 && !converting && error == null
}

/** ESL LED Control (opcode 0xB0) parameters for gateway `led_control`. */
enum class LedRepeatMode {
    /** repeat_type=1, repeats_duration=0 — steady on */
    SteadyOn,
    /** repeat_type=0, repeats_duration=0 — turn off */
    SteadyOff,
    /** repeat_type=1, repeats_duration=N — on for N seconds */
    Timed,
    /**
     * Flashing pattern: 40-bit alternating bits, 250 ms on/off.
     * repeat_type=1, repeats_duration=N seconds.
     */
    Blink,
}

data class LedConfig(
    val ledIndex: Int = 0,
    val colorRed: Int = 3,
    val colorGreen: Int = 0,
    val colorBlue: Int = 0,
    val brightness: Int = 3,
    val repeatMode: LedRepeatMode = LedRepeatMode.SteadyOn,
    val durationSeconds: Int = 5,
    /** 10 hex chars = 40-bit pattern (little-endian bytes). Used by [LedRepeatMode.Blink]. */
    val patternHex: String = "AAAAAAAAAA",
    /** Off time per pattern bit, units of 2 ms. Default 125 → 250 ms. */
    val bitOffPeriod: Int = 125,
    /** On time per pattern bit, units of 2 ms. Default 125 → 250 ms. */
    val bitOnPeriod: Int = 125,
) {
    val repeatType: Int
        get() = when (repeatMode) {
            LedRepeatMode.SteadyOn, LedRepeatMode.Timed, LedRepeatMode.Blink -> 1
            LedRepeatMode.SteadyOff -> 0
        }

    val repeatsDuration: Int
        get() = when (repeatMode) {
            LedRepeatMode.SteadyOn, LedRepeatMode.SteadyOff -> 0
            LedRepeatMode.Timed, LedRepeatMode.Blink -> durationSeconds.coerceIn(1, 32767)
        }

    val usesFlashingPattern: Boolean
        get() = repeatMode == LedRepeatMode.Blink
}

/** Fixed Group IDs offered by the provision dialog (no free-form input). */
val PROVISION_GROUP_CHOICES = listOf(0, 1, 2, 3)
