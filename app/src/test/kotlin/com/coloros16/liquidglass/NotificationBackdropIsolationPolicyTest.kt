package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NotificationBackdropIsolationPolicy as Policy
import com.coloros16.liquidglass.hook.NotificationBackdropIsolationPolicy.ScrimRole as Role
import org.junit.Assert.*
import org.junit.Test

class NotificationBackdropIsolationPolicyTest {
    @Test fun onlyAuditedBehindScrimsReceiveTheWholePanelMask() {
        assertTrue(Policy.mayDrawPanelMask(Policy.scrimRole("behind_scrim")))
        assertTrue(Policy.mayDrawPanelMask(Policy.scrimRole(null, "scrim_behind")))
        for (name in listOf("notifications_scrim", "front_scrim", "other_scrim", null)) {
            assertFalse(name, Policy.mayDrawPanelMask(Policy.scrimRole(name)))
        }
    }

    @Test fun foregroundResourceAliasesDoNotBecomeBehindScrims() {
        assertEquals(Role.NOTIFICATIONS, Policy.scrimRole(null, "scrim_notifications"))
        assertEquals(Role.FRONT, Policy.scrimRole(null, "scrim_in_front"))
        assertFalse(Policy.mayDrawPanelMask(Policy.scrimRole(null, "scrim_notifications")))
        assertFalse(Policy.mayDrawPanelMask(Policy.scrimRole(null, "scrim_in_front")))
    }

    @Test fun conflictingOrMissingIdentityDoesNotDarkenTheCards() {
        assertEquals(Role.UNKNOWN, Policy.scrimRole("notifications_scrim", "scrim_behind"))
        assertEquals(Role.UNKNOWN, Policy.scrimRole("behind_scrim", "scrim_in_front"))
        assertEquals(Role.UNKNOWN, Policy.scrimRole(null))
        assertFalse(Policy.mayDrawPanelMask(Policy.scrimRole(null)))
    }

    @Test fun visibleShadeMustBeExcludedBeforeScreenshotBecomesIndependentInput() {
        assertFalse(Policy.mayCaptureIndependentBackdrop(true, false))
        assertTrue(Policy.mayCaptureIndependentBackdrop(true, true))
        assertTrue(Policy.mayCaptureIndependentBackdrop(false, false))
        assertTrue(Policy.mayCaptureIndependentBackdrop(false, true))
    }
}
