package com.coloros16.liquidglass.hook

import android.graphics.*
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Full shared optics on the OEM background input, not a white ring on the foreground.
 * RuntimeShaderEffect replaces only uSource with the native backdrop. The low-res child
 * is unused in sourcePreblurred mode; native RenderEffect performs the GPU blur once.
 */
internal class AppGlassEffect {
    private val shader=LiquidGlassShader.create()
    private val empty=LinearGradient(0f,0f,1f,1f,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP)
    fun create(params:LiquidGlassShader.Params):RenderEffect {
        LiquidGlassShader.setUniforms(shader,params.copy(sourcePreblurred=true),empty,empty,
            sourceRect=RectF(0f,0f,params.viewportWidth,params.viewportHeight))
        val optical=RenderEffect.createRuntimeShaderEffect(shader,"uSource")
        return if(params.blurRadius>0f)RenderEffect.createChainEffect(optical,
            RenderEffect.createBlurEffect(params.blurRadius,params.blurRadius,Shader.TileMode.CLAMP)) else optical
    }
}
