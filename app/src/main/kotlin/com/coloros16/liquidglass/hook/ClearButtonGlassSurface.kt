/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Draw at PressFeedbackButton.onDraw, before its text. A native node having a
 * display list does NOT prove its buffer/alpha is visible. Keep a small visible
 * base even when the OEM disables blur or hands out a transparent input shader.
 * The live native shader is read only; native Paint and capture stay untouched. */
internal class ClearButtonGlassSurface {
    private val glass by lazy {LiquidGlassShader.create()}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE}
    private val optical=Paint(Paint.ANTI_ALIAS_FLAG)
    private var baseBounds=RectF()
    private var baseLuma=Float.NaN
    fun draw(canvas:Canvas,area:RectF,p:LiquidGlassShader.Params,source:Shader?,density:Float):Boolean {
        if(!canvas.isHardwareAccelerated || area.isEmpty ||
            !listOf(area.left,area.top,area.right,area.bottom).all(Float::isFinite))return false
        val radius=minOf(area.width(),area.height())*.5f
        val shade=if(p.grayBaseLuma>=0f)p.grayBaseLuma.coerceIn(0f,1f) else .6f
        if(baseBounds!=area || baseLuma!=shade) {
            val channel=(shade*255).toInt()
            fill.shader=LinearGradient(area.left,area.top,area.left,area.bottom,
                Color.argb(54,channel,channel,channel),Color.argb(32,channel,channel,channel),Shader.TileMode.CLAMP)
            rim.shader=LinearGradient(area.left,area.top,area.right,area.bottom,
                Color.argb(92,255,255,255),Color.argb(36,255,255,255),Shader.TileMode.CLAMP)
            baseBounds=RectF(area);baseLuma=shade
        }
        canvas.drawRoundRect(area,radius,radius,fill)
        if(source!=null)runCatching {
            LiquidGlassShader.setUniforms(glass,p.copy(cornerRadius=radius,parallaxX=0f,parallaxY=0f,sourcePreblurred=true),
                source,source,sourceRect=area,maskRect=area,maskOffsetX=area.left,maskOffsetY=area.top)
            optical.shader=glass
            canvas.drawRoundRect(area,radius,radius,optical)
        }
        rim.strokeWidth=density.coerceIn(.5f,4f)*.65f
        val inset=rim.strokeWidth*.5f
        val edge=RectF(area).apply {inset(inset,inset)}
        canvas.drawRoundRect(edge,(radius-inset).coerceAtLeast(0f),(radius-inset).coerceAtLeast(0f),rim)
        return true
    }
}
