package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassMode
import com.coloros16.liquidglass.hook.HeadsUpDissolveContract
import org.junit.Assert.*
import org.junit.Test

class IconModeAndHeadsUpContractTest {
    @Test fun unknownModeKeepsPrefabAsStableDefault() {
        for(value in listOf(null,"","unknown","prefab")) {
            assertEquals("prefab",IconGlassMode.parse(value));assertFalse(IconGlassMode.preferLive(value))
        }
        assertTrue(IconGlassMode.preferLive("live"))
    }
    @Test fun headsUpParticlesOnlyFollowAcceptedUserDrag() {
        assertTrue(HeadsUpDissolveContract.accepted(true,"onEndDrag"))
        assertFalse(HeadsUpDissolveContract.accepted(false,"onEndDrag"))
        for(reason in listOf(null,"timeout","show","replace","onEntryRemoved","appOpen"))
            assertFalse(HeadsUpDissolveContract.accepted(true,reason))
    }
    @Test fun particleOverlayIsLimitedToNativeHeadsUpWindow() {
        assertTrue(HeadsUpDissolveContract.windowRoot("com.oplus.systemui.HeadsUpLayout",2017))
        assertFalse(HeadsUpDissolveContract.windowRoot("com.oplus.systemui.HeadsUpLayout",2040))
        assertFalse(HeadsUpDissolveContract.windowRoot("NotificationShadeWindowView",2017))
    }
}
