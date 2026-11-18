package com.coloros16.liquidglass.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeAreasTest {
    @Test fun independentScenesAndMultipleContoursRoundTrip() {
        assertNotEquals(SafeAreas.Scene.DESKTOP.key, SafeAreas.Scene.LOCK.key)
        val contours = listOf(
            listOf(SafeAreas.Point(0f, 0f), SafeAreas.Point(0.25f, 0.5f), SafeAreas.Point(0.5f, 0f)),
            listOf(SafeAreas.Point(0.75f, 0.75f), SafeAreas.Point(1f, 0.75f), SafeAreas.Point(1f, 1f)),
        )
        assertEquals(contours, SafeAreas.decode(SafeAreas.encode(contours)))
        assertEquals(emptyList<List<SafeAreas.Point>>(), SafeAreas.decode(""))
    }

    @Test fun invalidPointsAndUnboundedInputAreNotAccepted() {
        val valid = "0,0/10000,10000/0,10000"
        assertEquals(1, SafeAreas.decode("invalid;-1,1/10001,0/0,0;$valid").size)
        assertEquals(SafeAreas.MAX_REGIONS, SafeAreas.decode(List(60) { valid }.joinToString(";")).size)
        assertEquals(SafeAreas.MAX_POINTS,
            SafeAreas.decode(List(400) { "50,50" }.joinToString("/")).single().size)
    }

    @Test fun scaledMirroredClockUsesFullDisplayCoordinates() {
        val clock = SafeAreas.Mapping.affine(
            floatArrayOf(-1f, 0f, 860f, 0f, 1.2f, 350f),
            1080, 2400,
        )!!
        with(SafeAreas) {
            assertTrue(clock.intersects(350f, 190f, .42f, .17f, .58f, .20f))
            assertFalse(clock.intersects(350f, 190f, .02f, .10f, .20f, .20f))
            assertFalse(clock.intersects(350f, 190f, .42f, .08f, .58f, .13f))
        }
    }

    @Test fun partialIconAndTransformedLockscreenShareNormalizedDisplaySpace() {
        val icon = SafeAreas.Mapping.rect(200f, 300f, 100f, 200f, 1000, 2000)!!
        assertEquals(0.25f, icon.x + icon.xx * 0.5f, 0.00001f)
        assertEquals(0.20f, icon.y + icon.yy * 0.5f, 0.00001f)
        val lock = SafeAreas.Mapping.affine(floatArrayOf(0f, -2f, 200f, 2f, 0f, 300f), 1000, 2000)!!
        assertEquals(0.16f, lock.x + lock.xx * 50f + lock.xy * 20f, 0.00001f)
        assertEquals(0.20f, lock.y + lock.yx * 50f + lock.yy * 20f, 0.00001f)
        assertNull(SafeAreas.Mapping.rect(0f, 0f, 100f, 100f, 0, 2000))
    }
}
