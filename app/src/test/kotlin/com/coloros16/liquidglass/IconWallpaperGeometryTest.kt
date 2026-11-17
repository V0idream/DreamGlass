package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconWallpaperGeometry
import org.junit.Assert.*
import org.junit.Test

class IconWallpaperGeometryTest {
    @Test fun allIconsInOneSceneReuseTheSameProjection() {
        val cache=IconWallpaperGeometry.ProjectionCache()
        val shared=cache.get(768,800,1440,3168,.5f,0f,1f)!!
        repeat(60) { i ->
            assertSame(shared,cache.get(768,800,1440,3168,.5f,0f,1f))
            assertTrue(shared.x(i*20f).isFinite())
        }
        assertNotSame(shared,cache.get(768,800,1440,3168,.6f,0f,1f))
        assertNotSame(shared,cache.get(768,800,1440,3168,.5f,0f,1.1f))
        assertNotSame(shared,cache.get(768,800,3168,1440,.5f,0f,1f))
        assertNull(cache.get(768,800,0,3168,.5f,0f,1f))
        assertNull(cache.get(768,800,1440,3168,Float.NaN,0f,1f))
    }
    @Test fun precomputedProjectionMatchesOriginalFormulaAcrossScrollAndZoom() {
        val cache=IconWallpaperGeometry.ProjectionCache()
        for(width in listOf(384,768))for(height in listOf(384,768))
            for(zoom in listOf(.1f,.8f,1f,1.2f,4f))for(offset in listOf(0f,.3f,1f)) {
                val projection=cache.get(width,height,1440,3168,offset,offset,zoom)!!
                val scale=maxOf(1440f/width,3168f/height)
                val cropX=(width*scale-1440f).coerceAtLeast(0f)*offset
                val cropY=(height*scale-3168f).coerceAtLeast(0f)*offset
                for(point in listOf(-80f,0f,360f,720f,1440f,3168f)) {
                    assertEquals(((point-720f)/zoom+720f+cropX)/scale,projection.x(point),.002f)
                    assertEquals(((point-1584f)/zoom+1584f+cropY)/scale,projection.y(point),.002f)
                }
            }
    }
    @Test fun wideWallpaperOffsetsUseLauncherLeftOriginAndFullScrollRange() {
        assertArrayEquals(floatArrayOf(20f,50f,60f,90f),
            IconWallpaperGeometry.map(400,200,100,200,20f,50f,60f,90f,0f,0f,1f),.001f)
        assertArrayEquals(floatArrayOf(170f,50f,210f,90f),
            IconWallpaperGeometry.map(400,200,100,200,20f,50f,60f,90f,.5f,0f,1f),.001f)
        assertArrayEquals(floatArrayOf(320f,50f,360f,90f),
            IconWallpaperGeometry.map(400,200,100,200,20f,50f,60f,90f,1f,0f,1f),.001f)
    }

    @Test fun portraitWallpaperVerticalOffsetUsesOnlyVerticalOverflow() {
        assertArrayEquals(floatArrayOf(20f,310f,60f,350f),
            IconWallpaperGeometry.map(200,400,200,100,20f,10f,60f,50f,1f,1f,1f),.001f)
    }

    @Test fun zoomInvertsAboutTheScreenCenterAfterParallax() {
        assertArrayEquals(floatArrayOf(185f,75f,205f,95f),
            IconWallpaperGeometry.map(400,200,100,200,20f,50f,60f,90f,.5f,0f,2f),.001f)
    }

    @Test fun downsampledBitmapUsesItsOwnPixelCoordinates() {
        assertArrayEquals(floatArrayOf(160f,25f,180f,45f),
            IconWallpaperGeometry.map(200,100,100,200,20f,50f,60f,90f,1f,0f,1f),.001f)
    }

    @Test fun orientationChangesRecomputeCoverWithoutChangingSourcePixels() {
        assertArrayEquals(floatArrayOf(40f,20f,120f,100f),
            IconWallpaperGeometry.map(400,200,200,100,20f,10f,60f,50f,1f,1f,1f),.001f)
    }

    @Test fun invalidGeometryCannotReachTheShader() {
        assertNull(IconWallpaperGeometry.map(0,200,100,200,20f,50f,60f,90f,0f,0f,1f))
        assertNull(IconWallpaperGeometry.map(400,200,0,200,20f,50f,60f,90f,0f,0f,1f))
        assertNull(IconWallpaperGeometry.map(400,200,100,200,20f,50f,20f,90f,0f,0f,1f))
        assertNull(IconWallpaperGeometry.map(400,200,100,200,20f,50f,60f,90f,Float.NaN,0f,1f))
        assertNull(IconWallpaperGeometry.map(400,200,100,200,20f,50f,60f,90f,0f,0f,0f))
    }
}
