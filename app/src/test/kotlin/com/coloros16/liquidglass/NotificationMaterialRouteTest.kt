package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NotificationMaterialRoute
import org.junit.Assert.*
import org.junit.Test

class NotificationMaterialRouteTest {
    private fun route(motion: Boolean = false, non: Boolean = true, headsUp: Boolean = true,
                      enabled: Boolean = true, power: Boolean = false, background: Boolean = true) =
        NotificationMaterialRoute.shouldReplace(enabled, power, background, motion, non, headsUp)

    @Test fun thirdPartyThemeHeadsUpUsesGlassEvenWhenNativeChoosesNon() { assertTrue(route()) }
    @Test fun ordinaryNonCardsArePreserved() { assertFalse(route(headsUp = false)) }
    @Test fun existingMotionCompatibilitySurvivesBeforeAttachment() {
        assertTrue(route(motion = true, non = false, headsUp = false))
    }
    @Test fun otherNativeMaterialsAreNotReplaced() { assertFalse(route(non = false)) }
    @Test fun disabledAndPowerSavingKeepNativeRendering() {
        assertFalse(route(enabled = false)); assertFalse(route(power = true))
    }
    @Test fun neverChangesNonNotificationSurfaces() { assertFalse(route(background = false)) }
}
