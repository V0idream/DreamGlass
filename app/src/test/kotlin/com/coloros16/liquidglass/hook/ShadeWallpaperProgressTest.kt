package com.coloros16.liquidglass.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeWallpaperProgressTest {
    @Test fun combinedShadeFadesInAndOutWithTheGesture() {
        val progress = ShadeWallpaperProgress()
        assertEquals(0, progress.alpha)
        assertTrue(progress.update(null, .4f))
        assertTrue(progress.alpha in 1..254)
        assertTrue(progress.update("SHADE_PANEL", 1f))
        assertEquals(255, progress.alpha)
        assertTrue(progress.update("SHADE_PANEL", 0f))
        assertEquals(0, progress.alpha)
    }

    @Test fun switchingBetweenSplitPanelsKeepsTheWallpaperDuringTheOverlap() {
        val progress = ShadeWallpaperProgress()
        progress.update("NOTIFICATION_PANEL", 1f)
        assertFalse(progress.update("QS_PANEL", 1f))
        assertFalse(progress.update("NOTIFICATION_PANEL", 0f))
        assertEquals(255, progress.alpha)
        progress.update("QS_PANEL", 0f)
        assertEquals(0, progress.alpha)
    }

    @Test fun ignoresInvalidProgressAndClearsOldStateOnScreenOff() {
        val progress = ShadeWallpaperProgress()
        progress.update("QS_PANEL", 1f)
        assertFalse(progress.update("QS_PANEL", Float.NaN))
        assertFalse(progress.update("OTHER", 0f))
        assertEquals(255, progress.alpha)
        progress.reset()
        assertEquals(0, progress.alpha)
        progress.update("NOTIFICATION_PANEL", .4f)
        assertTrue(progress.alpha in 1..254)
    }
}
