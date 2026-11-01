package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.MediaSurfaceGeometry
import org.junit.Assert.*
import org.junit.Test

class MediaSurfaceGeometryTest {
    @Test fun mediaOwnershipIsExactAndDoesNotMatchAlbumsOrWholeShade() {
        assertTrue(MediaSurfaceGeometry.owns("com.oplus.systemui.qs.media.OplusQsNormalMediaPanelView"))
        assertTrue(MediaSurfaceGeometry.owns("com.oplus.systemui.media.controls.ui.OplusMediaCarouselView"))
        for(name in listOf("android.view.View", "com.android.systemui.shade.NotificationPanelView",
            "com.oplus.systemui.qs.media.OplusQsMediaOutputSpotlightLayout", "other.MediaPlayerCardPageRootView"))
            assertFalse(MediaSurfaceGeometry.owns(name))
    }
    @Test fun scrolledMediaKeepsNativeSizeAndSourceOrigin() {
        val geometry=MediaSurfaceGeometry.resolve(-36,0,684,720)!!
        assertEquals(720f,geometry.width,0f);assertEquals(720f,geometry.height,0f)
        assertEquals(-36f,geometry.sourceLeft,0f);assertEquals(0f,geometry.sourceTop,0f)
        // A partially visible card must retain its own right corner, not a corner at the clip edge.
        assertNotEquals(648f,geometry.width)
    }
    @Test fun shiftedAndResizedCardsKeepTheirOwnBounds() {
        val geometry=MediaSurfaceGeometry.resolve(19,12,371,342)!!
        assertEquals(352f,geometry.width,0f);assertEquals(330f,geometry.height,0f)
        assertEquals(19f,geometry.sourceLeft,0f);assertEquals(12f,geometry.sourceTop,0f)
    }
    @Test fun corruptOrOverflowingBoundsAreRejected() {
        assertNull(MediaSurfaceGeometry.resolve(0,0,0,720))
        assertNull(MediaSurfaceGeometry.resolve(100,0,20,720))
        assertNull(MediaSurfaceGeometry.resolve(Int.MIN_VALUE,0,Int.MAX_VALUE,720))
    }
}
