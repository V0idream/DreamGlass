package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.GalleryDialogPaintRoute
import org.junit.Assert.*
import org.junit.Test

class GalleryDialogPaintRouteTest {
    private class Host(var visible:Boolean=true,var leased:Boolean=true)
    private val route=GalleryDialogPaintRoute<Host>()
    private fun paint(host:Host,panel:Boolean)=route.paintPanel(host,panel,{it.visible},{it.leased})

    @Test fun nativeOverlayOwnsExactlyOneBodyPaint() {
        val panel=Host();val overlay=Host();route.bind(panel,overlay)
        assertNull(paint(panel,true))
        assertSame(panel,paint(overlay,false))
    }
    @Test fun hiddenNativeOverlayHandsBodyBackToParent() {
        val panel=Host();val overlay=Host();route.bind(panel,overlay)
        overlay.visible=false
        assertSame(panel,paint(panel,true));assertNull(paint(overlay,false))
        overlay.visible=true
        assertNull(paint(panel,true));assertSame(panel,paint(overlay,false))
    }
    @Test fun nativeBackgroundReplacementCannotSilenceBothPaintHosts() {
        val panel=Host();val overlay=Host();route.bind(panel,overlay)
        overlay.leased=false
        assertSame(panel,paint(panel,true));assertNull(paint(overlay,false))
        overlay.leased=true
        assertNull(paint(panel,true));assertSame(panel,paint(overlay,false))
    }
    @Test fun recreatedOverlayDoesNotKeepAStaleRoute() {
        val panel=Host();val old=Host();val replacement=Host()
        route.bind(panel,old);route.bind(panel,replacement)
        assertNull(route.panel(old));assertNull(paint(old,false))
        assertSame(panel,paint(replacement,false))
        assertSame(replacement,route.unbind(panel))
        assertSame(panel,paint(panel,true));assertNull(paint(replacement,false))
    }
    @Test fun reusedOverlayCannotPaintIntoItsPreviousWindow() {
        val first=Host();val second=Host();val overlay=Host()
        route.bind(first,overlay);route.bind(second,overlay)
        assertNull(route.overlay(first));assertSame(first,paint(first,true))
        assertSame(second,paint(overlay,false))
        route.clear();assertNull(route.panel(overlay))
    }
}
