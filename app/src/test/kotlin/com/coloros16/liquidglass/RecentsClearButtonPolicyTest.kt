package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.RecentsClearButtonPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class RecentsClearButtonPolicyTest {
    @Test fun nativeRelativeLayoutSlotIsResolvedWithoutScanningOtherAncestors() {
        assertEquals(0,Policy.panelLevel(true,false,false))
        assertEquals(1,Policy.panelLevel(false,true,true))
        assertNull(Policy.panelLevel(false,false,true))
        assertNull(Policy.panelLevel(false,true,false))
    }
    @Test fun onlyExactNativePanelSlotAndResourceAreEligible() {
        assertTrue(Policy.eligible(true,true,true,true,"btn_clear"))
        assertFalse(Policy.eligible(false,true,true,true,"btn_clear"))
        assertFalse(Policy.eligible(true,false,true,true,"btn_clear"))
        assertFalse(Policy.eligible(true,true,false,true,"btn_clear"))
        assertFalse(Policy.eligible(true,true,true,false,"btn_clear"))
        assertFalse(Policy.eligible(true,true,true,true,"clean_storage_button"))
        assertFalse(Policy.eligible(true,true,true,true,""))
    }
    @Test fun radiusUsesSameNativeCapsuleRuleForPortraitAndLandscape() {
        assertEquals(48f,Policy.radius(0f,0f,384f,96f)!!,0f)
        assertEquals(48f,Policy.radius(0f,0f,96f,384f)!!,0f)
        assertEquals(24.25f,Policy.radius(10.5f,20.5f,210.5f,69f)!!,0f)
    }
    @Test fun missingOrInvalidNativeGeometryNeverMounts() {
        assertNull(Policy.radius(0f,0f,0f,96f))
        assertNull(Policy.radius(1f,0f,0f,96f))
        assertNull(Policy.radius(0f,0f,384f,Float.NaN))
        assertNull(Policy.radius(0f,0f,Float.POSITIVE_INFINITY,96f))
    }
}
