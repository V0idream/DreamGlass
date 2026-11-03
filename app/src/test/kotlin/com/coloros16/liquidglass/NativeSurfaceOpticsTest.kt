package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NativeSurfaceOptics
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import org.junit.Assert.*
import org.junit.Test

class NativeSurfaceOpticsTest {
    @Test fun fixedNativeMaskIgnoresParallaxWithoutChangingTheSharedPreset() {
        val source=LiquidGlassShader.Params(294f,178f,cornerRadius=51f,parallaxX=24f,parallaxY=-16f,
            blurRadius=20f,refractionAmount=37f,grayBaseLuma=.35f,debugGlassBounds=false)
        val anchored=NativeSurfaceOptics.anchor(source)
        assertEquals(source.copy(sourcePreblurred=true,parallaxX=0f,parallaxY=0f),anchored)
        assertEquals(24f,source.parallaxX,0f);assertEquals(-16f,source.parallaxY,0f)
        assertFalse(source.sourcePreblurred)
    }
    @Test fun repeatedAnchoringDoesNotAccumulateAnOffsetOrChangeOptics() {
        val source=LiquidGlassShader.Params(420f,120f,blurRadius=0f,debugGlassBounds=false)
        assertEquals(NativeSurfaceOptics.anchor(source),NativeSurfaceOptics.anchor(NativeSurfaceOptics.anchor(source)))
    }
}
