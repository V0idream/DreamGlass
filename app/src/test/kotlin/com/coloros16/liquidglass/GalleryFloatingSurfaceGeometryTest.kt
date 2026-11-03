package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.GalleryFloatingSurfaceGeometry as Geometry
import org.junit.Assert.*
import org.junit.Test

class GalleryFloatingSurfaceGeometryTest {
    @Test fun parentDrawingOriginIsNotAppliedAgainToTheChildBackground() {
        val left=Geometry.menu(500f,140f,77f,12f,377f,112f,false)
        val moved=Geometry.menu(500f,140f,1077f,812f,1377f,912f,false)
        assertEquals(Geometry.Bounds(0f,0f,300f,100f),left)
        assertEquals(left,moved)
    }

    @Test fun rtlUsesNativeRightBottomAnchoringRatherThanParentCoordinates() {
        assertEquals(Geometry.Bounds(200f,40f,500f,140f),
            Geometry.menu(500f,140f,77f,12f,377f,112f,true))
        assertEquals(Geometry.menu(500f,140f,77f,12f,377f,112f,true),
            Geometry.menu(500f,140f,1000f,1900f,1300f,2000f,true))
    }

    @Test fun expandingAndCollapsingFramesKeepFractionalNativeWidthIncludingOvershoot() {
        for(width in listOf(37.25f,299.75f,500f,537.5f,299.75f,37.25f)) {
            val ltr=Geometry.menu(500f,140f,80f,20f,80f+width,120f,false)!!
            val rtl=Geometry.menu(500f,140f,80f,20f,80f+width,120f,true)!!
            assertEquals(width,ltr.right-ltr.left,0f);assertEquals(width,rtl.right-rtl.left,0f)
            assertEquals(0f,ltr.left,0f);assertEquals(500f,rtl.right,0f)
            if(width>500f)assertTrue(rtl.left<0f)
        }
    }

    @Test fun accessoryBackgroundUsesFullDrawableBoundsWhilePaddingOnlyChangesRadius() {
        assertEquals(Geometry.Bounds(0f,0f,120f,120f),Geometry.accessory(120f,120f))
        assertEquals(60f,Geometry.radius(120f,0,0)!!,0f)
        assertEquals(40f,Geometry.radius(120f,12,28)!!,0f)
        assertEquals(Geometry.Bounds(0f,0f,120f,120f),Geometry.accessory(120f,120f))
    }

    @Test fun radiusUsesCurrentNativeHeightAndNeverPreviousLayoutCache() {
        // Menu uses height; accessory uses measuredHeight, matching their two
        // distinct native call sites even during temporary layout transitions.
        assertEquals(40f,Geometry.radius(100f,10,10)!!,0f)
        assertEquals(60f,Geometry.radius(140f,10,10)!!,0f)
        assertEquals(50f,Geometry.radius(120f,10,10)!!,0f)
    }

    @Test fun invalidAndNonfiniteNativeGeometryIsRejectedRatherThanInventingOffsets() {
        assertNull(Geometry.menu(500f,140f,0f,0f,0f,100f,false))
        assertNull(Geometry.menu(500f,140f,Float.NaN,0f,100f,100f,true))
        assertNull(Geometry.menu(500f,140f,-Float.MAX_VALUE,0f,Float.MAX_VALUE,100f,false))
        assertNull(Geometry.accessory(-1f,100f));assertNull(Geometry.accessory(100f,Float.POSITIVE_INFINITY))
        assertNull(Geometry.radius(0f,0,0));assertNull(Geometry.radius(20f,20,20))
    }
}
