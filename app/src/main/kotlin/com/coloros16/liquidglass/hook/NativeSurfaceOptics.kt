/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

internal object NativeSurfaceOptics {
    fun anchor(params:LiquidGlassShader.Params)=params.copy(
        sourcePreblurred=true,parallaxX=0f,parallaxY=0f)
}
