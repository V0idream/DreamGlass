package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherSuggestionSurfacePolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class LauncherSuggestionSurfacePolicyTest {
    @Test fun onlyRealSuggestionCardTypesAndDedicatedFirstBackgroundAreEligible() {
        for(type in listOf(82,83))assertTrue(Policy.eligible(true,true,true,100,type))
        assertFalse(Policy.eligible(false,true,true,100,83))
        assertFalse(Policy.eligible(true,false,true,100,83))
        assertFalse(Policy.eligible(true,true,false,100,83))
    }
    @Test fun visuallySimilarFolderOrWidgetNeverCountsAsSuggestionCard() {
        for(item in listOf(0,2,4,6,101))assertFalse(Policy.eligible(true,true,true,item,83))
        for(type in listOf(0,1,81,84))assertFalse(Policy.eligible(true,true,true,100,type))
    }
}
