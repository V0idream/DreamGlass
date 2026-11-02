package com.coloros16.liquidglass.hook

import android.graphics.*
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** GPU-only native backdrop filter. Geometry and source use the same host-local
 * bounds; never substitute window coordinates or ignore a native inset. */
internal class AppGlassEffect {
    private val shader=LiquidGlassShader.create()
    private val empty=LinearGradient(0f,0f,1f,1f,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP)
    private val fade=RuntimeShader("""
        uniform shader material;
        uniform float opacity;
        half4 main(float2 p) { return material.eval(p) * opacity; }
    """.trimIndent())
    fun create(params:LiquidGlassShader.Params,
        bounds:RectF=RectF(0f,0f,params.viewportWidth,params.viewportHeight),opacity:Float=1f):RenderEffect {
        LiquidGlassShader.setUniforms(shader,params.copy(sourcePreblurred=true),empty,empty,
            sourceRect=bounds,maskRect=bounds,maskOffsetX=bounds.left,maskOffsetY=bounds.top)
        val optical=RenderEffect.createRuntimeShaderEffect(shader,"uSource")
        val blurred=if(params.blurRadius>0f)RenderEffect.createChainEffect(optical,
            RenderEffect.createBlurEffect(params.blurRadius,params.blurRadius,Shader.TileMode.CLAMP)) else optical
        if(opacity>=1f)return blurred
        fade.setFloatUniform("opacity",opacity.coerceIn(0f,1f))
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(fade,"material"),blurred)
    }
}
