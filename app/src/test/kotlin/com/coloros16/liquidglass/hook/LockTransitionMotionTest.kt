package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.config.AodComposition
import com.coloros16.liquidglass.config.AodCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTransitionMotionTest {
    @Test fun enteringAodFromDesktopOrKeyguardPlaysSleepButAuthenticationDoesNotPlayWake() {
        val lock = AodTransitionPhase.fromNative("KEYGUARD")
        val unlocked = AodTransitionPhase.fromNative("UNLOCK")
        for (name in listOf("NORMAL_AOD", "WORKSHOP_AOD", "PANORAMIC_AOD")) {
            val aod = AodTransitionPhase.fromNative(name)
            assertEquals(1f, aod.destination(lock)!!, 0f)
            assertEquals(0f, lock.destination(aod)!!, 0f)
            assertEquals(0f, unlocked.destination(aod)!!, 0f)
            assertNull(aod.destination(unlocked))
        }
        assertNull(unlocked.destination(lock))
        assertNull(lock.destination(unlocked))
        for (name in listOf("AOD_OFF", "OCCLUDED", "UNKNOW_STATE")) {
            val other = AodTransitionPhase.fromNative(name)
            assertNull(other.destination(lock))
            assertNull(lock.destination(other))
            assertNull(AodTransitionPhase.AOD.destination(other))
        }
    }

    @Test fun cropAndDisplayChoicesRoundTripAndRejectInvalidSelections() {
        val crop = AodCrop.fromCorners(.82f, .76f, .14f, .21f)!!
        assertEquals(.14f, crop.left, 0f)
        assertEquals(.76f, crop.bottom, 0f)
        val value = AodComposition(clock = false, date = true, battery = false, crop = crop)
        assertEquals(value, AodComposition.decode(value.encode()))
        assertEquals(AodComposition(), AodComposition.decode(null))
        assertEquals(AodCrop.FULL, AodComposition.decode("false,true,false,NaN,0,1,1").crop)
        assertNull(AodCrop.fromCorners(.2f, .2f, .2f, .25f))
        assertEquals(AodCrop.FULL, AodCrop.fromCorners(-1f, -.5f, 1.3f, 2f))
    }

    @Test fun aPortraitWallpaperFillsBothAspectRatiosAndPreservesTheZoomAnchor() {
        val imageAspect = .45f
        for (style in listOf(LockTransitionStyle.PHOTO, LockTransitionStyle.LETTER)) {
            val frame = AodCrop.FULL.framing(imageAspect, style.imageAspect)
            assertEquals(1f, frame.width, .00001f)
            assertEquals(style.imageAspect, imageAspect * frame.width / frame.height, .00001f)
            val anchorX = .62f
            val anchorY = .48f
            val zoomed = frame.zoom(2f, anchorX, anchorY, frame)
            assertEquals(frame.width / 2f, zoomed.width, .00001f)
            assertEquals((anchorX - frame.left) / frame.width, (anchorX - zoomed.left) / zoomed.width, .00001f)
            assertEquals((anchorY - frame.top) / frame.height, (anchorY - zoomed.top) / zoomed.height, .00001f)
            assertEquals(1f, zoomed.pan(2f, 2f).right, .00001f)
            assertEquals(0f, zoomed.pan(-2f, -2f).top, .00001f)
            assertEquals(frame.width / AodCrop.MAX_ZOOM, zoomed.zoom(100f, .5f, .5f, frame).width, .00001f)
            val restored = AodComposition.decode(AodComposition(crop = zoomed).encode()).crop
                .framing(imageAspect, style.imageAspect)
            assertEquals(zoomed.centerX, restored.centerX, .00001f)
            assertEquals(zoomed.width, restored.width, .00001f)
        }
    }

    @Test fun reversingAnInFlightTransitionUsesOnlyTheRemainingDistance() {
        val style = LockTransitionStyle.PHOTO
        assertEquals(style.duration / 2, style.durationBetween(.5f, 1f))
        assertEquals(style.sleepDuration / 2, style.durationBetween(.5f, 0f))
    }

    @Test fun storedStylesResolveSafely() {
        assertEquals(LockTransitionStyle.SYSTEM, LockTransitionStyle.fromKey(null))
        assertEquals(LockTransitionStyle.SYSTEM, LockTransitionStyle.fromKey("unknown"))
        for (style in LockTransitionStyle.entries) assertEquals(style, LockTransitionStyle.fromKey(style.name))
    }

    @Test fun animationStagesUseTheSameOrderedTimelineInEitherDirection() {
        for ((start, end) in listOf(.13f to .32f, .16f to .60f, .48f to .94f, .77f to 1f)) {
            val values = (0..100).map { LockTransitionMotion.ease(it / 100f, start, end) }
            assertEquals(0f, values.first(), 0f)
            assertEquals(1f, values.last(), 0f)
            assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
            assertEquals(values.asReversed(), (100 downTo 0).map { LockTransitionMotion.ease(it / 100f, start, end) })
        }
    }
}
