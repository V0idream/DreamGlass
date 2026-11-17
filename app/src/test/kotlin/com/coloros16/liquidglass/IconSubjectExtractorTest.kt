package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconSubjectExtractor as E
import com.coloros16.liquidglass.hook.IconMaskPolicy
import org.junit.Assert.*
import org.junit.Test

class IconSubjectExtractorTest {
    @Test fun gradientBackgroundRevealsCentralSubjectWithoutMutatingInput() {
        val pixels=IntArray(128*128) {i ->val y=i/128;0xff000000.toInt() or (20 shl 16) or ((40+y) shl 8) or 210}
        for(y in 32..95)for(x in 43..84)pixels[y*128+x]=0xffffffff.toInt()
        val original=pixels.copyOf();val result=E.extract(pixels,128,128)
        assertEquals("subject-saliency",result.kind)
        assertTrue(result.pixels[64*128+64] ushr 24>200)
        assertEquals(0,result.pixels[8*128+8] ushr 24)
        assertArrayEquals(original,pixels)
    }
    @Test fun photographicFallbackBecomesTonalReliefRatherThanColouredPhoto() {
        val pixels=IntArray(128*128) {i ->0xff000000.toInt() or (((i*37)%256) shl 16) or (((i*67)%256) shl 8) or ((i*91)%256)}
        val result=E.extract(pixels,128,128)
        assertTrue(result.kind.startsWith("subject-"))
        assertTrue(result.pixels.any {it ushr 24>0});assertTrue(result.pixels.any {it ushr 24<200})
        result.pixels.forEach {assertEquals(it shr 16 and 255,it and 255);assertEquals(it shr 8 and 255,it and 255)}
        assertArrayEquals(result.pixels,E.extract(pixels,128,128).pixels)
    }
    @Test fun smallWhitePaddedEmblemIsEnlargedAndCentered() {
        val pixels=IntArray(128*128){0xffffffff.toInt()}
        for(y in 12..27)for(x in 7..18)pixels[y*128+x]=0xff1030a0.toInt()
        val conservative=IconMaskPolicy.extract(pixels,128,128)
        val subject=if(conservative.separated)conservative.pixels else E.extract(pixels,128,128).pixels
        val output=E.center(subject,128,128)
        val points=output.indices.filter {output[it] ushr 24>=128}
        assertTrue(points.isNotEmpty())
        val left=points.minOf {it%128};val right=points.maxOf {it%128}
        val top=points.minOf {it/128};val bottom=points.maxOf {it/128}
        assertEquals(63.5f,(left+right)/2f,1f);assertEquals(63.5f,(top+bottom)/2f,1f)
        assertTrue(maxOf(right-left+1,bottom-top+1) in 88..96)
        assertEquals(0,output[0])
    }
    @Test fun centredHollowGlyphRetainsItsHole() {
        val pixels=IntArray(128*128)
        for(y in 10..41)for(x in 5..36)if(x<10 || x>31 || y<15 || y>36)pixels[y*128+x]=0xffffffff.toInt()
        val output=E.center(pixels,128,128)
        assertEquals(0,output[64*128+64] ushr 24)
        assertTrue(output.any {it ushr 24>200})
    }
    @Test fun similarLuminanceColoursStillHaveDifferentReliefDepths() {
        val red=0xffff0a00.toInt();val blue=0xff003cff.toInt()
        val pixels=IntArray(400){if(it%20<10)red else blue}
        val output=E.tonalize(pixels)!!
        assertTrue(kotlin.math.abs((output[5] and 255)-(output[15] and 255))>100)
        assertEquals(255,output[5] ushr 24)
    }
    @Test fun uniformGlyphDoesNotAcquireInventedInternalDetail() {
        assertNull(E.tonalize(IntArray(400){0xff20b050.toInt()}))
        assertArrayEquals(IntArray(400),E.center(IntArray(400),20,20))
    }
    @Test fun diagonalMultiStopBackgroundDoesNotBecomeAForegroundTriangle() {
        val colours=arrayOf(intArrayOf(40,81,192),intArrayOf(156,56,124),intArrayOf(36,189,160))
        val pixels=IntArray(128*128) {i ->
            val position=(i%128+i/128)/127f;val band=if(position<1f)0 else 1;val t=position-band
            fun c(channel:Int)=(colours[band][channel]*(1-t)+colours[band+1][channel]*t).toInt()
            0xff000000.toInt() or (c(0) shl 16) or (c(1) shl 8) or c(2)
        }
        for(y in 30..90)for(x in 40..87)pixels[y*128+x]=0xffffffff.toInt()
        val result=E.extract(pixels,128,128)
        assertTrue(result.pixels[60*128+60] ushr 24>200)
        for((x,y) in listOf(12 to 95,95 to 12,20 to 70,108 to 58))assertEquals(0,result.pixels[y*128+x] ushr 24)
    }
}
