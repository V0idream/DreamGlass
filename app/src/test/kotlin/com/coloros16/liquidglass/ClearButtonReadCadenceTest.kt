package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.ClearButtonReadCadence
import org.junit.Assert.*
import org.junit.Test

class ClearButtonReadCadenceTest {
    @Test fun frameworkNotReadyAtPreDrawCanRetryOnSameFrameDraw() {
        val cache=ClearButtonReadCadence()
        assertTrue(cache.shouldRead(100L)) // preDraw gets no framework value.
        assertTrue(cache.shouldRead(101L)) // draw must not wait until 600ms.
        cache.succeeded(101L)
        assertFalse(cache.shouldRead(102L))
    }
    @Test fun enteringRecentsRefreshesCachedDisabledStateImmediately() {
        val cache=ClearButtonReadCadence()
        cache.succeeded(100L) // previous screen saved a disabled state.
        assertFalse(cache.shouldRead(200L))
        assertTrue(cache.shouldRead(200L,entering=true))
        cache.succeeded(200L)
        assertFalse(cache.shouldRead(201L))
    }
    @Test fun ordinaryFramesRetainBoundedPreferenceReads() {
        val cache=ClearButtonReadCadence();cache.succeeded(100L)
        for(t in 101L..599L)assertFalse(cache.shouldRead(t))
        assertTrue(cache.shouldRead(600L))
        cache.succeeded(600L)
        assertFalse(cache.shouldRead(601L))
    }
}
