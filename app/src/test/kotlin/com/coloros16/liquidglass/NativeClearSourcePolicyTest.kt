package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NativeClearSourceKind as Kind
import com.coloros16.liquidglass.hook.NativeClearSourcePolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class NativeClearSourcePolicyTest {
    @Test fun disabledAgslStillUsesTheNativeBitmapPath() {
        assertEquals(Kind.BITMAP,Policy.choose(false,false,false,true))
        // A retained old M/L shader must not override current disabled AGSL.
        assertEquals(Kind.BITMAP,Policy.choose(false,true,true,true))
    }
    @Test fun readyNativeShaderWorksBeforePaintWasRecorded() {
        assertEquals(Kind.L,Policy.choose(true,false,true,true))
        assertEquals(Kind.M,Policy.choose(true,true,true,true))
    }
    @Test fun legitimateBitmapDoesNotRequireAgslReadiness() {
        assertEquals(Kind.BITMAP,Policy.choose(true,false,false,true))
        assertEquals(Kind.NONE,Policy.choose(true,false,false,false))
        assertEquals(Kind.NONE,Policy.choose(false,true,true,false))
    }
}
