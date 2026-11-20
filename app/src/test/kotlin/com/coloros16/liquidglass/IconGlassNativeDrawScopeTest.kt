package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassNativeDrawScope as Scope
import org.junit.Assert.*
import org.junit.Test

class IconGlassNativeDrawScopeTest {
    @Test fun preservesAnimatorInterceptionAndSkipsOnlyItsLegacyPaint() {
        val engine=Any();val unrelated=Any();var animations=0;var oldPaint=0
        val intercepted=Scope.run(engine) {
            if(!Scope.suppresses(engine,beforeIcon=true))oldPaint++
            assertFalse(Scope.suppresses(unrelated,beforeIcon=true))
            animations++
            true // The native animation owns this frame and must retain its return value.
        }
        assertTrue(intercepted);assertEquals(1,animations);assertEquals(0,oldPaint)
        assertFalse(Scope.suppresses(engine,beforeIcon=true))
    }
    @Test fun preservesNativeAfterIconOverlaysInBothModes() {
        val engine=Any()
        for(live in listOf(false,true)) {
            var overlays=0
            Scope.run(engine) {
                assertTrue(Scope.suppresses(engine,beforeIcon=true))
                Scope.finish(false,live) {}
                if(!Scope.suppresses(engine,beforeIcon=false))overlays++
                assertTrue(Scope.owns(engine))
                assertFalse(Scope.owns(Any()))
            }
            assertEquals(1,overlays)
        }
    }
    @Test fun preservesNormalDrawingWhenAnimatorDoesNotIntercept() {
        val engine=Any()
        assertFalse(Scope.run(engine) {false})
        assertFalse(Scope.suppresses(engine,beforeIcon=true))
        assertFalse(Scope.suppresses(null,beforeIcon=true))
    }
    @Test fun nestedUnownedAndOwnedCallsRestoreOuterPaintScope() {
        val outer=Any();val inner=Any()
        Scope.run(outer) {
            Scope.run(null) {assertFalse(Scope.suppresses(outer,beforeIcon=true))}
            Scope.run(inner) {assertTrue(Scope.suppresses(inner,beforeIcon=true));assertFalse(Scope.suppresses(outer,beforeIcon=true))}
            assertTrue(Scope.suppresses(outer,beforeIcon=true))
        }
        assertFalse(Scope.suppresses(outer,beforeIcon=true))
    }
    @Test fun failedAnimationCannotSuppressAnotherFramesPaint() {
        val engine=Any()
        try {Scope.run(engine) {throw IllegalStateException("native animation")}}catch(_:IllegalStateException) {}
        assertFalse(Scope.suppresses(engine,beforeIcon=true))
    }
    @Test fun liveNormalFrameDrawsContentOnceAndPreventsStaticReplay() {
        val engine=Any();val phases=ArrayList<String>()
        val intercepted=Scope.run(engine) {
            phases.add("animator")
            Scope.finish(false,true) {
                assertTrue(Scope.suppresses(engine,beforeIcon=true))
                phases.add("live-content")
            }
        }
        if(!intercepted)phases.add("static-textview-replay")
        assertEquals(listOf("animator","live-content"),phases)
        assertFalse(Scope.suppresses(engine,beforeIcon=true))
    }
    @Test fun animatedFrameDoesNotPaintOverItsTwoNativeClippedPasses() {
        val phases=ArrayList<String>()
        phases.add("animated-icon-pass");phases.add("outside-icon-text-pass")
        assertTrue(Scope.finish(true,true) {phases.add("duplicate-live-content")})
        assertEquals(listOf("animated-icon-pass","outside-icon-text-pass"),phases)
    }
    @Test fun prefabNormalFrameKeepsItsNativeContentPath() {
        var duplicateContent=0
        assertFalse(Scope.finish(false,false) {duplicateContent++})
        assertEquals(0,duplicateContent)
    }
    @Test fun liveContentFailureRestoresRenderManagerScope() {
        val engine=Any()
        try {
            Scope.run(engine) {Scope.finish(false,true) {throw IllegalStateException("content")}}
            fail("Expected failure")
        }catch(_:IllegalStateException) {}
        assertFalse(Scope.suppresses(engine,beforeIcon=true))
        var nextFrame=0
        assertTrue(Scope.finish(false,true) {nextFrame++})
        assertEquals(1,nextFrame)
    }
}
