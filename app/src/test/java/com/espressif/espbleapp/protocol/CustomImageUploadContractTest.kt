package com.espressif.espbleapp.protocol

import com.espressif.espbleapp.data.ESL_AP_LOCAL_ID
import com.espressif.espbleapp.image.BinaryImageEncoder
import com.espressif.espbleapp.image.ImageUploadIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol contract for custom image upload without exercising android.util.Base64
 * (JVM unit tests). Wire shape matches EslRpcClient.uploadImage / transferImageToTag.
 */
class CustomImageUploadContractTest {
    @Test
    fun slotsUseFixedIdsAndMatchingImageIndexOnEndAndTransfer() {
        (0..3).forEach { slot ->
            val imageId = ImageUploadIds.customImageId(slot).toLong()
            val bytes = BinaryImageEncoder.demoCheckerboard()
            val checksum = BinaryImageEncoder.crc32Hex(bytes)
            val version = ImageUploadIds.versionFromCrc32Hex(checksum).toLong()

            ImageUploadIds.assertInt32Positive(imageId, "image_id")
            ImageUploadIds.assertInt32Positive(version, "version")
            assertTrue(imageId <= Int.MAX_VALUE)
            assertTrue(version <= Int.MAX_VALUE)

            val end = buildJsonObject {
                put("image_id", JsonPrimitive(imageId))
                put("version", JsonPrimitive(version))
                put("image_index", JsonPrimitive(slot))
            }
            val transfer = buildJsonObject {
                put("image_id", JsonPrimitive(imageId))
                put("image_index", JsonPrimitive(slot))
            }
            assertEquals(slot, end["image_index"]!!.toString().toInt())
            assertEquals(
                end["image_index"]!!.toString(),
                transfer["image_index"]!!.toString(),
            )
            assertEquals(imageId.toString(), end["image_id"]!!.toString())
            assertEquals((1001L + slot).toString(), end["image_id"]!!.toString())
        }
    }

    @Test
    fun reuploadSameSlot_reusesId_andNeverCreatesFifthId() {
        val ids = mutableListOf<Int>()
        repeat(8) { n ->
            ids.add(ImageUploadIds.customImageId(n % 4))
        }
        assertEquals(setOf(1001, 1002, 1003, 1004), ids.toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMillisStyleImageId() {
        ImageUploadIds.assertInt32Positive(System.currentTimeMillis(), "image_id")
    }

    @Test
    fun apLocalBadgeTransfer_usesEslId254AndLocalFlag() {
        val slot = 0
        val imageId = ImageUploadIds.badgeImageId(slot).toLong()
        val begin = buildJsonObject {
            put("image_id", JsonPrimitive(imageId))
            put("slot_index", JsonPrimitive(slot))
            put("dest", JsonPrimitive(ImageUploadIds.DEST_AP))
        }
        val transfer = buildJsonObject {
            put("image_id", JsonPrimitive(imageId))
            put("slot_index", JsonPrimitive(slot))
            put("local", JsonPrimitive(true))
            put("tag", buildJsonObject {
                put("esl_id", JsonPrimitive(ESL_AP_LOCAL_ID))
                put("group_id", JsonPrimitive(0))
            })
        }
        assertEquals("2001", begin["image_id"]!!.toString())
        assertEquals("\"ap\"", begin["dest"]!!.toString())
        assertEquals("2001", transfer["image_id"]!!.toString())
        assertEquals("true", transfer["local"]!!.toString())
        val tag = transfer["tag"] as JsonObject
        assertEquals("254", tag["esl_id"]!!.toString())
        assertEquals("0", tag["group_id"]!!.toString())
    }

    @Test
    fun tagForwardBadgeUpload_usesSeparateIdsAndDest() {
        val slot = 1
        val apId = ImageUploadIds.badgeImageId(slot)
        val tagId = ImageUploadIds.badgeTagImageId(slot)
        assertEquals(2002, apId)
        assertEquals(3002, tagId)
        val begin = buildJsonObject {
            put("image_id", JsonPrimitive(tagId.toLong()))
            put("slot_index", JsonPrimitive(slot))
            put("dest", JsonPrimitive(ImageUploadIds.DEST_TAG))
        }
        val transfer = buildJsonObject {
            put("image_id", JsonPrimitive(tagId.toLong()))
            put("slot_index", JsonPrimitive(slot))
            put("tag", buildJsonObject {
                put("esl_id", JsonPrimitive(0))
                put("group_id", JsonPrimitive(0))
            })
        }
        assertEquals("\"tag\"", begin["dest"]!!.toString())
        assertEquals(tagId.toString(), begin["image_id"]!!.toString())
        assertEquals(tagId.toString(), transfer["image_id"]!!.toString())
        assertTrue(transfer["local"] == null)
    }
}
