package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.AppGlassSurfacePolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class AppNativeCardPolicyTest {
    @Test fun verifiedServiceCardCanHaveControlsWithoutBeingMistakenForToolbar() {
        assertEquals(Policy.PANEL,Policy.role("FrameLayout/assistant","service_card",950f,190f,1080f,2400f,true,false,true,true))
        assertEquals(Policy.NONE,Policy.role("FrameLayout","unknown",950f,190f,1080f,2400f,true,false,true))
    }
    @Test fun nativeCardDoesNotBypassChromeOrWholePageExclusions() {
        assertEquals(Policy.NONE,Policy.role("Toolbar/assistant","top_bar",950f,150f,1080f,2400f,true,false,true,true))
        assertEquals(Policy.NONE,Policy.role("FrameLayout/assistant","root",1080f,2000f,1080f,2400f,true,false,true,true))
    }
}
