package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconDefaultPolicy
import org.junit.Assert.*
import org.junit.Test

class IconDefaultPolicyTest {
    private fun reference()=IntArray(1024) {i ->
        val x=i%32;val y=i/32
        if(x in 7..24 && y in 8..22)0xffeeeeee.toInt() else 0xff165bb1.toInt()
    }
    @Test fun defaultResourceMatchesAcrossApplicationsWithoutAnyPackageIdentity() {
        val pixels=reference()
        assertTrue(IconDefaultPolicy.matches(pixels.copyOf(),pixels))
        // A small colour-space rounding difference should not defeat resource recognition.
        val rounded=pixels.map {it-0x010101}.toIntArray()
        assertTrue(IconDefaultPolicy.matches(rounded,pixels))
    }
    @Test fun ordinaryBlueIconWithDifferentForegroundIsNotReplaced() {
        val pixels=reference()
        val other=IntArray(1024) {i ->
            val x=i%32;val y=i/32
            if((x-16)*(x-16)+(y-16)*(y-16)<64)0xffeeeeee.toInt() else 0xff165bb1.toInt()
        }
        assertFalse(IconDefaultPolicy.matches(other,pixels))
    }
    @Test fun transparentOrMissingInputCannotMatchTheDefaultResource() {
        assertFalse(IconDefaultPolicy.matches(IntArray(1024),reference()))
        assertFalse(IconDefaultPolicy.matches(IntArray(1024),IntArray(1024)))
        assertFalse(IconDefaultPolicy.matches(IntArray(16),IntArray(16)))
        assertFalse(IconDefaultPolicy.matches(reference(),IntArray(256)))
    }
}
