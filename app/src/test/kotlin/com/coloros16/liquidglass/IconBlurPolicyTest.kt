package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconBlurPolicy
import org.junit.Assert.*
import org.junit.Test

class IconBlurPolicyTest {
    @Test fun zeroIsSharpAndDefaultRetainsExistingBlur() {
        assertEquals(0f,IconBlurPolicy.radius(0),0f)
        assertEquals(2.6f,IconBlurPolicy.radius(26),.0001f)
    }
    @Test fun settingIsMonotonicAndBounded() {
        assertEquals(0f,IconBlurPolicy.radius(Int.MIN_VALUE),0f)
        assertEquals(10f,IconBlurPolicy.radius(Int.MAX_VALUE),0f)
        for(i in 0..99)assertTrue(IconBlurPolicy.radius(i)<IconBlurPolicy.radius(i+1))
    }
}
