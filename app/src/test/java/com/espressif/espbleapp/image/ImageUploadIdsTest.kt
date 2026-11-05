package com.espressif.espbleapp.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadIdsTest {
    @Test
    fun slotsMapToFixedIds_1001to1004() {
        val ids = (0..3).map { ImageUploadIds.customImageId(it) }
        assertEquals(listOf(1001, 1002, 1003, 1004), ids)
    }

    @Test
    fun reuploadSameSlot_reusesSameId() {
        assertEquals(ImageUploadIds.customImageId(2), ImageUploadIds.customImageId(2))
    }

    @Test
    fun fiveUploads_neverCreateFifthId() {
        val ids = mutableListOf<Int>()
        repeat(5) { n ->
            val slot = n % 4
            ids.add(ImageUploadIds.customImageId(slot))
        }
        assertEquals(4, ids.toSet().size)
        assertTrue(ids.all { it in 1001..1004 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeSlot() {
        ImageUploadIds.customImageId(4)
    }
}
