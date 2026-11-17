package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassUniformCache
import org.junit.Assert.*
import org.junit.Test

class IconGlassUniformCacheTest {
    @Test fun staticMaterialHasNoRepeatedNativeWrites() {
        val cache=IconGlassUniformCache();var writes=0
        repeat(60) {
            if(cache.two("origin",12f,24f))writes++
            if(cache.two("size",120f,120f))writes++
            if(cache.four("source",15f,30f,60f,60f))writes++
            if(cache.one("alpha",1f))writes++
            if(cache.one("clarity",.76f))writes++
        }
        assertEquals(5,writes)
    }
    @Test fun movingBackgroundStillUploadsCurrentCoordinates() {
        val cache=IconGlassUniformCache()
        assertTrue(cache.four("source",0f,20f,60f,60f))
        assertTrue(cache.four("source",1f,20f,60f,60f))
        assertFalse(cache.four("source",1f,20f,60f,60f))
        assertTrue(cache.four("source",1f,20f,58f,60f))
        assertTrue(cache.four("source",1f,22f,58f,59f))
    }
    @Test fun nativeFadeChangesDoNotInvalidateOpticalParameters() {
        val cache=IconGlassUniformCache()
        cache.one("alpha",1f);cache.one("refraction",.56f)
        for(alpha in listOf(.5f,0f,1f)) {
            assertTrue(cache.one("alpha",alpha))
            assertFalse(cache.one("refraction",.56f))
        }
    }
    @Test fun newShaderForcesUniformsToUploadAgain() {
        val cache=IconGlassUniformCache()
        cache.two("size",64f,64f);cache.one("disabled",0f)
        cache.clear()
        assertTrue(cache.two("size",64f,64f));assertTrue(cache.one("disabled",0f))
    }
    @Test fun materialOwnersCannotShareStaleUniformState() {
        val a=IconGlassUniformCache();val b=IconGlassUniformCache()
        assertTrue(a.one("alpha",1f));assertTrue(b.one("alpha",1f))
        assertTrue(a.one("alpha",.5f));assertFalse(b.one("alpha",1f))
    }
}
