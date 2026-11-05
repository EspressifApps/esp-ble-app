package com.espressif.espbleapp.protocol

import com.espressif.espbleapp.data.LedConfig
import com.espressif.espbleapp.data.LedRepeatMode
import com.espressif.espbleapp.data.PROVISION_GROUP_CHOICES
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape contracts for Group provision picker and Blink LED flashing fields.
 * Mirrors EslRpcClient.provisionTag / ledControl without BLE transport.
 */
class LedGroupControlContractTest {
    @Test
    fun provisionGroupChoicesAreFixed0To3() {
        assertEquals(listOf(0, 1, 2, 3), PROVISION_GROUP_CHOICES)
    }

    @Test
    fun provisionTagJsonIncludesSelectedGroupId() {
        PROVISION_GROUP_CHOICES.forEach { groupId ->
            val data = buildJsonObject {
                put("cmd", "provision_tag")
                put(
                    "tag",
                    buildJsonObject {
                        put("ble_addr", "aa:bb:cc:dd:ee:ff")
                        put("addr_type", 0)
                    },
                )
                put("group_id", JsonPrimitive(groupId))
            }
            assertEquals(groupId, data.int("group_id"))
            assertEquals("provision_tag", data.string("cmd"))
        }
    }

    @Test
    fun blinkLedConfigMapsToFlashingFields() {
        val cfg = LedConfig(
            repeatMode = LedRepeatMode.Blink,
            durationSeconds = 10,
            patternHex = "AAAAAAAAAA",
            bitOffPeriod = 125,
            bitOnPeriod = 125,
        )
        assertTrue(cfg.usesFlashingPattern)
        assertEquals(1, cfg.repeatType)
        assertEquals(10, cfg.repeatsDuration)
        assertEquals(10, cfg.patternHex.length)
    }

    @Test
    fun steadyModesDoNotUseFlashingPattern() {
        assertFalse(LedConfig(repeatMode = LedRepeatMode.SteadyOn).usesFlashingPattern)
        assertFalse(LedConfig(repeatMode = LedRepeatMode.SteadyOff).usesFlashingPattern)
        assertFalse(LedConfig(repeatMode = LedRepeatMode.Timed).usesFlashingPattern)
    }

    @Test
    fun ledControlBlinkJsonIncludesPatternAndBitPeriods() {
        val cfg = LedConfig(repeatMode = LedRepeatMode.Blink, durationSeconds = 10)
        val data = EspJsonlRpc.eslData(EslCommands.LED_CONTROL) {
            it["tag"] = EspJsonlRpc.tagSelector(eslId = 255, groupId = 2)
            it["led_index"] = JsonPrimitive(cfg.ledIndex)
            it["color_red"] = JsonPrimitive(cfg.colorRed)
            it["color_green"] = JsonPrimitive(cfg.colorGreen)
            it["color_blue"] = JsonPrimitive(cfg.colorBlue)
            it["brightness"] = JsonPrimitive(cfg.brightness)
            it["repeat_type"] = JsonPrimitive(cfg.repeatType)
            it["repeats_duration"] = JsonPrimitive(cfg.repeatsDuration)
            it["pattern"] = JsonPrimitive(cfg.patternHex)
            it["bit_off_period"] = JsonPrimitive(cfg.bitOffPeriod)
            it["bit_on_period"] = JsonPrimitive(cfg.bitOnPeriod)
        }

        assertEquals("led_control", data.string("cmd"))
        assertEquals(255, data.obj("tag")!!.int("esl_id"))
        assertEquals(2, data.obj("tag")!!.int("group_id"))
        assertEquals("AAAAAAAAAA", data.string("pattern"))
        assertEquals(125, data.int("bit_off_period"))
        assertEquals(125, data.int("bit_on_period"))
        assertEquals(1, data.int("repeat_type"))
        assertEquals(10, data.int("repeats_duration"))
    }

    @Test
    fun ledControlSteadyJsonOmitsPatternFields() {
        val cfg = LedConfig(repeatMode = LedRepeatMode.SteadyOn)
        val data = EspJsonlRpc.eslData(EslCommands.LED_CONTROL) {
            it["tag"] = EspJsonlRpc.tagSelector(eslId = 1, groupId = 0)
            it["repeat_type"] = JsonPrimitive(cfg.repeatType)
            it["repeats_duration"] = JsonPrimitive(cfg.repeatsDuration)
            if (cfg.usesFlashingPattern) {
                it["pattern"] = JsonPrimitive(cfg.patternHex)
            }
        }
        assertFalse(data.containsKey("pattern"))
        assertEquals(0, data.int("repeats_duration"))
    }
}
