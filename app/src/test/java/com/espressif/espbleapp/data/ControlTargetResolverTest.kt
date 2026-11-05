package com.espressif.espbleapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTargetResolverTest {
    private val tag0 = TagRecord(eslId = 0, groupId = 1, bleAddr = "aa:bb:cc:dd:ee:01")
    private val tag1 = TagRecord(eslId = 1, groupId = 2, bleAddr = "aa:bb:cc:dd:ee:02")
    private val tags = listOf(tag0, tag1)

    @Test
    fun retainKeepsApAndMatchingTag() {
        assertEquals(ControlTarget.Ap, ControlTargetResolver.retainOrFallback(ControlTarget.Ap, tags))
        assertEquals(
            ControlTarget.Tag(1, 2),
            ControlTargetResolver.retainOrFallback(ControlTarget.Tag(1, 2), tags),
        )
    }

    @Test
    fun retainFallsBackToApWhenTagLeavesInventory() {
        assertEquals(
            ControlTarget.Ap,
            ControlTargetResolver.retainOrFallback(ControlTarget.Tag(9, 0), tags),
        )
    }

    @Test
    fun displayApAlwaysUsesLocalId() {
        val result = ControlTargetResolver.resolveDisplay(
            ControlTarget.Ap,
            ControlScope.Group,
            supportsGroup = true,
        )
        assertEquals(ControlResolveResult.Ok(ESL_AP_LOCAL_ID, 0), result)
    }

    @Test
    fun displaySelectedTagIsUnicast() {
        val result = ControlTargetResolver.resolveDisplay(
            ControlTarget.Tag(0, 1),
            ControlScope.Selected,
            supportsGroup = true,
        )
        assertEquals(ControlResolveResult.Ok(0, 1), result)
    }

    @Test
    fun displayGroupSendsBroadcastOnly() {
        val result = ControlTargetResolver.resolveDisplay(
            ControlTarget.Tag(0, 1),
            ControlScope.Group,
            supportsGroup = true,
        )
        assertEquals(ControlResolveResult.Ok(ESL_GROUP_BROADCAST_ID, 1), result)
    }

    @Test
    fun tagPawrRejectsApAndEmptyGroup() {
        assertEquals(
            ControlResolveResult.Fail(ControlResolveFail.ApNotAllowed),
            ControlTargetResolver.resolveTagPawr(
                ControlTarget.Ap,
                ControlScope.Selected,
                tags,
                supportsGroup = true,
            ) { true },
        )
        assertEquals(
            ControlResolveResult.Fail(ControlResolveFail.GroupNotReady),
            ControlTargetResolver.resolveTagPawr(
                ControlTarget.Tag(0, 1),
                ControlScope.Group,
                tags,
                supportsGroup = true,
            ) { false },
        )
    }

    @Test
    fun findTagMatchesStableIdentityNotIndex() {
        val found = ControlTargetResolver.findTag(ControlTarget.Tag(1, 2), tags)
        assertTrue(found === tag1)
    }
}
