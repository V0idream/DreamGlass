package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassMode
import com.coloros16.liquidglass.hook.HeadsUpDissolveContract
import org.junit.Assert.*
import org.junit.Test

class IconModeAndHeadsUpContractTest {
    private class SingleAxisAnimator {
        fun onEndDrag(velocity: Float, result: (Boolean) -> Unit): Boolean =
            (velocity < 0f).also(result)
    }

    private class DualAxisAnimator {
        fun onEndDrag(velocity: Float, result: (Boolean) -> Unit): Boolean =
            onEndDrag(0f, velocity, result)

        fun onEndDrag(horizontal: Float, vertical: Float, result: (Boolean) -> Unit): Boolean =
            (vertical < 0f && horizontal == 0f).also(result)
    }

    @Test fun headsUpResolvesBothNativeDragSignatures() {
        val callback = Function1::class.java
        assertArrayEquals(arrayOf(Float::class.javaPrimitiveType, callback),
            HeadsUpDissolveContract.dragEnd(SingleAxisAnimator::class.java, callback).parameterTypes)
        assertArrayEquals(arrayOf(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, callback),
            HeadsUpDissolveContract.dragEnd(DualAxisAnimator::class.java, callback).parameterTypes)
    }

    @Test fun unknownModeKeepsPrefabAsStableDefault() {
        for(value in listOf(null,"","unknown","prefab")) {
            assertEquals("prefab",IconGlassMode.parse(value));assertFalse(IconGlassMode.preferLive(value))
        }
        assertTrue(IconGlassMode.preferLive("live"))
    }
    @Test fun headsUpParticlesOnlyFollowAcceptedUserDrag() {
        assertTrue(HeadsUpDissolveContract.accepted(true,"onEndDrag"))
        assertFalse(HeadsUpDissolveContract.accepted(false,"onEndDrag"))
        for(reason in listOf(null,"timeout","show","replace","onEntryRemoved","appOpen"))
            assertFalse(HeadsUpDissolveContract.accepted(true,reason))
        assertTrue(HeadsUpDissolveContract.managerRelease(true,"OplusHeadsUpTouchHelper.onTouchEvent"))
        assertFalse(HeadsUpDissolveContract.managerRelease(false,"OplusHeadsUpTouchHelper.onTouchEvent"))
        assertFalse(HeadsUpDissolveContract.managerRelease(true,"timeout"))
    }
    @Test fun particleOverlayIsLimitedToNativeHeadsUpWindow() {
        assertTrue(HeadsUpDissolveContract.windowRoot("com.oplus.systemui.HeadsUpLayout",2017))
        assertFalse(HeadsUpDissolveContract.windowRoot("com.oplus.systemui.HeadsUpLayout",2040))
        assertFalse(HeadsUpDissolveContract.windowRoot("NotificationShadeWindowView",2017))
        assertTrue(HeadsUpDissolveContract.windowRoot("DecorView",2017,verifiedContainer=true))
        assertFalse(HeadsUpDissolveContract.windowRoot("DecorView",2040,verifiedContainer=true))
        assertEquals(-45f,HeadsUpDissolveContract.releaseY(-150f,100))
        assertEquals(12f,HeadsUpDissolveContract.releaseY(12f,100))
        assertEquals(-45f,HeadsUpDissolveContract.releaseX(-150f,100,200))
        assertEquals(145f,HeadsUpDissolveContract.releaseX(225f,100,200))
    }

    @Test fun simpleBannerHorizontalRequiresTheNativeDismissListener() {
        assertEquals(-1f, HeadsUpDissolveContract.simpleBannerHorizontal("translationX", true, -40f))
        assertEquals(1f, HeadsUpDissolveContract.simpleBannerHorizontal("translationX", true, 40f))
        assertNull(HeadsUpDissolveContract.simpleBannerHorizontal("translationX", false, -40f))
        assertNull(HeadsUpDissolveContract.simpleBannerHorizontal("translationY", true, -40f))
    }

    @Test fun simpleBannerVerticalOnlyAcceptsUpwardDismissal() {
        assertTrue(HeadsUpDissolveContract.simpleBannerUpward(-40f, false))
        assertFalse(HeadsUpDissolveContract.simpleBannerUpward(40f, false))
        assertFalse(HeadsUpDissolveContract.simpleBannerUpward(0f, false))
        assertFalse(HeadsUpDissolveContract.simpleBannerUpward(-40f, true))
    }
}
