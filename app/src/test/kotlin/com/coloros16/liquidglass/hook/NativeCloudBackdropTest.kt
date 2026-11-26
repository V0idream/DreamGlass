package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCloudBackdropTest {
    @Test fun matchesOnlyTheVerifiedNativeFluidCloudDecoration() {
        val blur = "com.android.internal.graphics.drawable.BackgroundBlurDrawable"
        val overlay = "com.oplus.systemui.plugins.seedling.util.d"
        val currentOverlay = "com.oplus.systemui.plugins.seedling.card.ui.view.f"
        assertTrue(NativeCloudBackdrop.isStockPair(blur, overlay))
        assertTrue(NativeCloudBackdrop.isStockPair(blur, currentOverlay))
        assertTrue(NativeCloudBackdrop.isStockPair("android.graphics.drawable.GradientDrawable", currentOverlay))
        assertFalse(NativeCloudBackdrop.isStockPair(blur, "android.graphics.drawable.GradientDrawable"))
        assertFalse(NativeCloudBackdrop.isStockPair("android.graphics.drawable.ColorDrawable", overlay))
        assertFalse(NativeCloudBackdrop.isStockPair(overlay, blur))
        assertFalse(NativeCloudBackdrop.isStockPair(currentOverlay, blur))
    }

    @Test fun expandedCardSoftensTheRimAndBlurWithoutChangingGeometry() {
        val base = LiquidGlassShader.Params(
            viewportWidth = 1300f, viewportHeight = 570f, cornerRadius = 110f,
            blurRadius = 20f, dispersion = 0.2f, innerGlow = 0.5f,
        )
        val tuned = NativeCloudBackdrop.glassFinish(base)
        assertTrue(tuned.surfacePolish)
        assertTrue(tuned.highlight in 0f..<base.highlight)
        assertTrue(tuned.highlightWidth in 0f..<base.highlightWidth)
        assertTrue(tuned.dispersion in 0f..<base.dispersion)
        assertTrue(tuned.innerGlow in 0f..<base.innerGlow)
        assertTrue(tuned.blurRadius in 0f..<base.blurRadius)
        assertEquals(base.cornerRadius, tuned.cornerRadius, 0f)
        assertEquals(base.viewportWidth, tuned.viewportWidth, 0f)
        assertEquals(base.viewportHeight, tuned.viewportHeight, 0f)
    }

    @Test fun customZeroValuesKeepOpticalEffectsOff() {
        val base = LiquidGlassShader.Params(
            viewportWidth = 1300f, viewportHeight = 570f, blurRadius = 0f,
            refractionAmount = 0f, dispersion = 0f, highlight = 0f, highlightWidth = 0f, innerGlow = 0f,
        )
        val tuned = NativeCloudBackdrop.glassFinish(base)
        assertEquals(0f, tuned.blurRadius, 0f)
        assertEquals(0f, tuned.refractionAmount, 0f)
        assertEquals(0f, tuned.dispersion, 0f)
        assertEquals(0f, tuned.highlight, 0f)
        assertEquals(0f, tuned.highlightWidth, 0f)
        assertEquals(0f, tuned.innerGlow, 0f)
    }
}
