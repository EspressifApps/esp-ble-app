package com.espressif.espbleapp.protocol

import com.espressif.espbleapp.data.ControlResolveResult
import com.espressif.espbleapp.data.ControlScope
import com.espressif.espbleapp.data.ControlTarget
import com.espressif.espbleapp.data.ControlTargetResolver
import com.espressif.espbleapp.data.ESL_AP_LOCAL_ID
import com.espressif.espbleapp.data.ESL_GROUP_BROADCAST_ID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayApContractTest {
    @Test
    fun selectedApSendsLocalDisplayImage() {
        val resolved = ControlTargetResolver.resolveDisplay(
            ControlTarget.Ap,
            ControlScope.Selected,
            supportsGroup = true,
        ) as ControlResolveResult.Ok
        val data = EspJsonlRpc.eslData(EslCommands.DISPLAY_IMAGE) {
            it["tag"] = EspJsonlRpc.tagSelector(resolved.eslId, resolved.groupId)
            it["display_index"] = JsonPrimitive(0)
            it["image_index"] = JsonPrimitive(1)
        }
        assertEquals(ESL_AP_LOCAL_ID, data.obj("tag")!!.int("esl_id"))
        assertEquals(0, data.obj("tag")!!.int("group_id"))
        assertEquals(1, data.int("image_index"))
    }

    @Test
    fun groupDisplaySendsOnlyBroadcastId() {
        val resolved = ControlTargetResolver.resolveDisplay(
            ControlTarget.Tag(eslId = 3, groupId = 2),
            ControlScope.Group,
            supportsGroup = true,
        ) as ControlResolveResult.Ok
        val data = EspJsonlRpc.eslData(EslCommands.DISPLAY_IMAGE) {
            it["tag"] = EspJsonlRpc.tagSelector(resolved.eslId, resolved.groupId)
            it["image_index"] = JsonPrimitive(1)
        }
        assertEquals(ESL_GROUP_BROADCAST_ID, data.obj("tag")!!.int("esl_id"))
        assertEquals(2, data.obj("tag")!!.int("group_id"))
        assertFalse(data.toString().contains("254"))
    }

    @Test
    fun commandResultCanOmitApDisplayed() {
        val raw = EspJson.parseToJsonElement(
            """{"event":"command_result","cmd":"display_image","status":"ok"}""",
        ).jsonObject
        assertNull(raw.boolOrNull("ap_displayed"))
    }
}
