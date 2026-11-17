package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconMaskPolicy
import org.junit.Assert.*
import org.junit.Test

class IconMaskPolicyTest {
    private val white=0xffffffff.toInt()
    private val blue=0xff2050c0.toInt()
    @Test fun transparentForegroundIsPreservedWithoutChangingCaller() {
        val pixels=IntArray(400)
        for(y in 5..14)for(x in 5..14)pixels[y*20+x]=blue
        val before=pixels.copyOf();val result=IconMaskPolicy.extract(pixels,20,20)
        assertTrue(result.separated);assertEquals("legacy-alpha",result.kind)
        assertArrayEquals(before,result.pixels);assertArrayEquals(before,pixels)
        assertNotSame(pixels,result.pixels)
    }
    @Test fun onlyEdgeConnectedFlatColorIsRemovedAndEnclosedLogoDetailSurvives() {
        val pixels=IntArray(400){white}
        for(y in 5..14)for(x in 5..14)pixels[y*20+x]=blue
        for(y in 8..11)for(x in 8..11)pixels[y*20+x]=white
        val before=pixels.copyOf();val result=IconMaskPolicy.extract(pixels,20,20)
        assertTrue(result.separated);assertEquals("flat-edge-separated",result.kind)
        assertEquals(0,result.pixels[0]);assertEquals(blue,result.pixels[5*20+5])
        assertEquals(white,result.pixels[9*20+9]);assertArrayEquals(before,pixels)
    }
    @Test fun connectedCorridorToTheExteriorIsAlsoRemoved() {
        val pixels=IntArray(400){white}
        for(y in 5..14)for(x in 5..14)pixels[y*20+x]=blue
        for(y in 0..9)pixels[y*20+9]=white
        val result=IconMaskPolicy.extract(pixels,20,20)
        assertTrue(result.separated);assertEquals(0,result.pixels[9*20+9])
        assertEquals(blue,result.pixels[9*20+8])
    }
    @Test fun ComplexPhotographicEdgesAreRetainedInsteadOfDestroyed() {
        val pixels=IntArray(400) {i ->0xff000000.toInt() or (((i*37)%256) shl 16) or (((i*67)%256) shl 8) or ((i*91)%256)}
        val result=IconMaskPolicy.extract(pixels,20,20)
        assertFalse(result.separated);assertEquals("flat-preserved",result.kind)
        assertArrayEquals(pixels,result.pixels)
    }
    @Test fun nearlySolidOrTinyForegroundFailsConfidenceGate() {
        val solid=IntArray(400){white}
        assertFalse(IconMaskPolicy.extract(solid,20,20).separated)
        solid[10*20+10]=blue
        val tiny=IconMaskPolicy.extract(solid,20,20)
        assertFalse(tiny.separated);assertArrayEquals(solid,tiny.pixels)
    }
    @Test fun opaqueAdaptiveForegroundDoesNotMasqueradeAsAMonochromeMask() {
        assertFalse(IconMaskPolicy.hasUsefulAlpha(IntArray(400){white},20,20))
        assertFalse(IconMaskPolicy.hasUsefulAlpha(IntArray(400),20,20))
    }
    @Test fun roundedPlateWithTransparentCornersIsNotAGlyph() {
        val plate=IntArray(10000)
        for(y in 5..94)for(x in 5..94) {
            val dx=(kotlin.math.abs(x-50)-25).coerceAtLeast(0)
            val dy=(kotlin.math.abs(y-50)-25).coerceAtLeast(0)
            if(dx*dx+dy*dy<20*20)plate[y*100+x]=blue
        }
        assertFalse(IconMaskPolicy.hasUsefulAlpha(plate,100,100))
        val glyph=IntArray(10000)
        for(y in 25..74)for(x in 30..69)glyph[y*100+x]=white
        assertTrue(IconMaskPolicy.hasUsefulAlpha(glyph,100,100))
    }
}
