package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconWallpaperGeometry
import org.junit.Assert.*
import org.junit.Test

class IconWallpaperGeometryTest {
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
