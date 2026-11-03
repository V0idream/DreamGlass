package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.AssistantSpecificSurfacePolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class AssistantSpecificSurfacePolicyTest {
    @Test fun onlyThreeVerifiedHeaderBackgroundsAreEligible() {
        for(id in listOf("search_bar","ai_icon","add_icon"))assertTrue(Policy.headerControl(true,true,id))
        for(id in listOf("head_icon","head_icon_blur","search_icon","header_image","","add_card_icon"))
            assertFalse(Policy.headerControl(true,true,id))
    }
    @Test fun matchingIdOutsideTheActualHeaderOrOnAnotherClassIsRejected() {
        assertFalse(Policy.headerControl(true,false,"add_icon"))
        assertFalse(Policy.headerControl(false,true,"search_bar"))
    }
    @Test fun servicesTargetIsTheWholeKnownCardNotItsIconOrRecyclerRows() {
        assertTrue(Policy.servicesCard(true,"shortcut_card"))
        assertFalse(Policy.servicesCard(false,"shortcut_card"))
        for(id in listOf("rv_shortcuts1","rv_shortcuts2","iv_internal_shortcut","nearby_services_container"))
            assertFalse(Policy.servicesCard(true,id))
    }
}
