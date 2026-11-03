package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.GalleryDialogGeometry as Geometry
import org.junit.Assert.*
import org.junit.Test

class GalleryDialogGeometryTest {
    @Test fun ordinaryPanelExcludesNativeOuterMarginsEvenWithTransparentDrawable() {
        assertEquals(Geometry.Bounds(64f,24f,1376f,1424f),
            Geometry.bounds(1440,1464,64,64,40,96,false,false))
    }
    @Test fun loadingPanelUsesNativeFullWidthAndHeightMinusVerticalMargin() {
        assertEquals(Geometry.Bounds(0f,24f,1440f,1424f),
            Geometry.bounds(1440,1464,64,64,40,96,false,true))
    }
    @Test fun tinyPanelExtendsPastBottomByRadiusRatherThanRoundingItsBottomCorners() {
        assertEquals(Geometry.Bounds(0f,64f,1440f,1624f),
            Geometry.bounds(1440,1464,64,64,0,96,true,false))
    }
    @Test fun tinyRuleTakesPriorityOverLoadingExactlyLikeNativeOutline() {
        assertEquals(Geometry.bounds(1440,1464,64,64,0,96,true,false),
            Geometry.bounds(1440,1464,64,64,0,96,true,true))
    }
    @Test fun transitionMayHaveNegativeTopAndIsNotShiftedIntoTheWindow() {
        assertEquals(Geometry.Bounds(20f,-16f,280f,164f),
            Geometry.bounds(300,200,20,20,36,32,false,false))
    }
    @Test fun invalidOrOverflowingNativeGeometryIsRejected() {
        assertNull(Geometry.bounds(0,100,5,5,5,10,false,false))
        assertNull(Geometry.bounds(20,100,10,5,5,10,false,false))
        assertNull(Geometry.bounds(200,100,10,5,5,-1,false,false))
        assertNull(Geometry.bounds(200,Int.MAX_VALUE,10,0,0,96,true,false))
    }
}
