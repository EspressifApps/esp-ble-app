package com.espressif.espbleapp.image

/**
 * AP gateway parses image_id/version with atoi() into 32-bit int, and keeps
 * only 4 runtime slots keyed by image_id. Custom uploads therefore use a
 * stable ID per display slot in the positive int32 range.
 */
object ImageUploadIds {
    /** Must stay > CONFIG_ESL_AP_DEFAULT_IMAGE_ID range (1..999) and built-ins 1..4. */
    const val CUSTOM_IMAGE_ID_BASE = 1001
    /** Persistent AP-local namespace for 480×480 badge frames. */
    const val BADGE_IMAGE_ID_BASE = 2001
    /** Transient AP-forward namespace; Tag images must not reuse AP IDs. */
    const val BADGE_TAG_IMAGE_ID_BASE = 3001

    const val DEST_AP = "ap"
    const val DEST_TAG = "tag"

    const val MAX_IMAGE_INDEX = 3

    fun customImageId(imageIndex: Int): Int {
        require(imageIndex in 0..MAX_IMAGE_INDEX) { "imageIndex out of range: $imageIndex" }
        return CUSTOM_IMAGE_ID_BASE + imageIndex
    }

    fun badgeImageId(slotIndex: Int): Int {
        require(slotIndex in 0..MAX_IMAGE_INDEX) { "slotIndex out of range: $slotIndex" }
        return BADGE_IMAGE_ID_BASE + slotIndex
    }

    fun badgeTagImageId(slotIndex: Int): Int {
        require(slotIndex in 0..MAX_IMAGE_INDEX) { "slotIndex out of range: $slotIndex" }
        return BADGE_TAG_IMAGE_ID_BASE + slotIndex
    }

    /**
     * Positive int32 version derived from CRC32 of payload.
     * Uses low 31 bits; maps 0 → 1 so AP never sees version 0.
     */
    fun versionFromCrc32Hex(checksumHex: String): Int {
        val crc = checksumHex.toLong(16) and 0x7fff_ffffL
        val v = crc.toInt()
        return if (v == 0) 1 else v
    }

    fun assertInt32Positive(value: Long, field: String) {
        require(value in 1..Int.MAX_VALUE) { "$field out of AP atoi/int32 range: $value" }
    }
}
