/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*

/** GPU coordinate bridge for the native preblurred bitmap alternative. Source
 * coordinates follow the very same bitmap-to-local matrix as native drawBitmap.
 * The Java shader drops its bitmap input after Canvas records this frame. */
internal class NativeClearBitmapShader {
    private val empty=LinearGradient(0f,0f,1f,1f,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP)
    private val mapping by lazy {RuntimeShader("""
        uniform shader nativeBitmap;
        uniform float3 mapX;
        uniform float3 mapY;
        uniform float3 mapW;
        half4 main(float2 p) {
            float3 q=float3(p,1.0);
            float w=dot(mapW,q);
            if(abs(w)<0.00001) return half4(0.0);
            return nativeBitmap.eval(float2(dot(mapX,q),dot(mapY,q))/w);
        }
    """.trimIndent())}

    /** null means input/matrix invalid; false is the consumer's draw result. */
    fun draw(bitmap:Bitmap,bitmapToLocal:Matrix,submit:(Shader)->Boolean):Boolean? {
        if(bitmap.isRecycled || bitmap.width<=0 || bitmap.height<=0)return null
        val inverse=Matrix()
        if(!bitmapToLocal.invert(inverse))return null
        val values=FloatArray(9).also(inverse::getValues)
        if(!values.all(Float::isFinite))return null
        val shader=mapping
        try {
            val input=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
            // The native blur buffer may be downscaled. RuntimeShader child
            // sampling does not inherit Paint.FILTER_BITMAP_FLAG, so request
            // linear GPU reconstruction explicitly rather than nearest texels.
            input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            shader.setInputShader("nativeBitmap",input)
            shader.setFloatUniform("mapX",values[0],values[1],values[2])
            shader.setFloatUniform("mapY",values[3],values[4],values[5])
            shader.setFloatUniform("mapW",values[6],values[7],values[8])
            return submit(shader)
        } finally {shader.setInputShader("nativeBitmap",empty)}
    }
}
