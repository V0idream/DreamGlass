package com.coloros16.liquidglass.glass

import com.coloros16.liquidglass.config.Prefs

/** DreamGlass 2026-10-26. Non-destructive built-in material overlay; custom class values win. */
object IosGlassPreset {
    const val ID = "builtin_ios"
    const val NAME = "IOS风"
    val values: Map<String, Float> = mapOf(
        Prefs.KEY_BLUR_RADIUS to 8f,
        Prefs.KEY_BUTTON_BLUR_RADIUS to 8f,
        Prefs.KEY_REFRACTION_AMOUNT to 36f,
        Prefs.KEY_REFRACTION_HEIGHT_RATIO to 0.28f,
        Prefs.KEY_DEPTH to 2.1f,
        Prefs.KEY_DISPERSION to 0f,
        Prefs.KEY_HIGHLIGHT to 0.72f,
        Prefs.KEY_HIGHLIGHT_WIDTH to 9f,
        Prefs.KEY_HIGHLIGHT_FALLOFF to 2.8f,
        Prefs.KEY_HIGHLIGHT_FLOOR to 0.10f,
        Prefs.KEY_HIGHLIGHT_INNER_ATT to 0.15f,
        Prefs.KEY_INNER_GLOW to 0f,
        Prefs.KEY_VIBRANCY to 1.12f,
        Prefs.KEY_MASK_GLASS_DIM to 0.06f,
        Prefs.KEY_LIGHT_ANGLE to 45f,
        Prefs.KEY_PARALLAX_X to 0f,
        Prefs.KEY_PARALLAX_Y to 0f,
    )

    fun resolve(activeId: String?, polished: Boolean, custom: Map<String, Float>?): Map<String, Float>? =
        if (activeId != ID || !polished) custom else if (custom.isNullOrEmpty()) values else values + custom
}
