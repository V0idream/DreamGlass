package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.BackdropBlurEffectCache
import org.junit.Assert.*
import org.junit.Test

class BackdropBlurEffectCacheTest {
    @Test fun zeroAndNegativeRadiusNeverCreateABlurEffect() {
        var creations=0;val cache=BackdropBlurEffectCache { _:Float -> creations++;Any() }
        repeat(30) {assertNull(cache.resolve(0f));assertNull(cache.resolve(-2f))}
        assertEquals(0,creations)
    }
    @Test fun repeatedRadiusReusesItsExistingEffect() {
        var creations=0;val cache=BackdropBlurEffectCache { _:Float -> creations++;Any() }
        val effect=cache.resolve(2.6f)
        repeat(30) {assertSame(effect,cache.resolve(2.6f))}
        assertEquals(1,creations)
    }
    @Test fun zeroAfterBlurClearsThePreviouslyAppliedEffect() {
        val cache=BackdropBlurEffectCache { radius:Float -> radius }
        assertEquals(2.6f,cache.resolve(2.6f)!!,0f)
        assertNull(cache.resolve(0f))
        assertEquals(4f,cache.resolve(4f)!!,0f)
    }
    @Test fun invalidRadiusDoesNotPoisonTheLastValidEffect() {
        val cache=BackdropBlurEffectCache { _:Float -> Any() };val valid=cache.resolve(3f)
        for(invalid in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            try {cache.resolve(invalid);fail("Expected rejection")}catch(_:IllegalArgumentException) {}
        }
        assertSame(valid,cache.resolve(3f))
    }
    @Test fun closingTheRendererReleasesItsCachedEffect() {
        val cache=BackdropBlurEffectCache { _:Float -> Any() };val previous=cache.resolve(3f)
        cache.clear()
        assertNotSame(previous,cache.resolve(3f))
    }
}
