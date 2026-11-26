package com.coloros16.liquidglass.hook

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Material for the original Fluid Cloud card, with the OEM gradient kept above the glass. */
internal object NativeCloudBackdrop {
    private const val FRAMEWORK_BLUR = "com.android.internal.graphics.drawable.BackgroundBlurDrawable"
    private const val LEGACY_GRADIENT = "com.oplus.systemui.plugins.seedling.util.d"
    private const val CARD_GRADIENT = "com.oplus.systemui.plugins.seedling.card.ui.view.f"

    fun splitLayers(background: Drawable): LayerDrawable? {
        if (background !is LayerDrawable || background.numberOfLayers != 2) return null
        val blur = background.getDrawable(0)
        val gradient = background.getDrawable(1)
        return background.takeIf { isStockPair(blur.javaClass.name, gradient.javaClass.name) }
    }

    fun glassFinish(base: LiquidGlassShader.Params): LiquidGlassShader.Params = base.copy(
        blurRadius = base.blurRadius * 0.80f,
        refractionHeight = base.refractionHeight * 0.64f,
        refractionAmount = base.refractionAmount * 0.72f,
        depth = base.depth * 0.76f,
        dispersion = base.dispersion * 0.30f,
        highlight = base.highlight * 0.52f,
        highlightWidth = base.highlightWidth * 0.68f,
        highlightFloor = base.highlightFloor * 0.45f,
        innerGlow = base.innerGlow * 0.24f,
        vibrancy = 1f + (base.vibrancy - 1f) * 0.70f,
        surfacePolish = true,
    )

    internal fun isStockPair(baseClass: String, decorationClass: String): Boolean =
        // Current SystemUIPlugin can layer this same drawable above framework blur
        // or a stock color background. A separate CardGradientOverlayView is drawn
        // by the ROM and does not enter this background wrapper.
        decorationClass == CARD_GRADIENT || (baseClass == FRAMEWORK_BLUR && decorationClass == LEGACY_GRADIENT)
}
