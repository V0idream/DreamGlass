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
    private val empty=LinearGradient(0f,0f,1f,1f,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP)
    var opticalSubmitted=false;private set
    var opticalError:String?=null;private set
    private var baseBounds=RectF()
    private var baseLuma=Float.NaN
    private var rimStrength=Float.NaN
    private var rimAngle=Float.NaN
    /** Prepare the shared program during entering/preDraw, even while the
     * native isolation flag still hides the button. Failure keeps the base. */
    fun prepare() {runCatching {glass}}
    fun draw(canvas:Canvas,area:RectF,p:LiquidGlassShader.Params,source:Shader?,density:Float):Boolean {
        opticalSubmitted=false;opticalError=null
        if(!canvas.isHardwareAccelerated || area.isEmpty ||
            !listOf(area.left,area.top,area.right,area.bottom).all(Float::isFinite))return false
        val radius=minOf(area.width(),area.height())*.5f
        val shade=if(p.grayBaseLuma>=0f)p.grayBaseLuma.coerceIn(0f,1f) else .6f
        val strength=p.highlight.coerceIn(0f,1f)
        val angle=if(p.lightAngleDegrees.isFinite())p.lightAngleDegrees else 45f
        if(baseBounds!=area || baseLuma!=shade || rimStrength!=strength || rimAngle!=angle) {
            val channel=(shade*255).toInt()
            fill.shader=LinearGradient(area.left,area.top,area.left,area.bottom,
                Color.argb(42,channel,channel,channel),Color.argb(26,channel,channel,channel),Shader.TileMode.CLAMP)
            val radians=Math.toRadians(angle.toDouble())
            val dx=(kotlin.math.cos(radians)*area.width()*.5).toFloat()
            val dy=(kotlin.math.sin(radians)*area.height()*.5).toFloat()
            rim.shader=LinearGradient(area.centerX()-dx,area.centerY()-dy,area.centerX()+dx,area.centerY()+dy,
                Color.argb((96*strength).toInt(),255,255,255),Color.argb((28*strength).toInt(),255,255,255),Shader.TileMode.CLAMP)
            baseBounds=RectF(area);baseLuma=shade;rimStrength=strength;rimAngle=angle
        }
        canvas.drawRoundRect(area,radius,radius,fill)
        val submitted=source!=null && runCatching {
            val material=glass
            try {
                LiquidGlassShader.setUniforms(material,p.copy(cornerRadius=radius,parallaxX=0f,parallaxY=0f,sourcePreblurred=true),
                    source,source,sourceRect=area,maskRect=area,maskOffsetX=area.left,maskOffsetY=area.top)
                optical.shader=material
                canvas.drawRoundRect(area,radius,radius,optical)
                opticalSubmitted=true
                true
            } finally {
                optical.shader=null
                // Drop source children after Canvas captured this display list.
                // In particular, don't cache a native hardware bitmap that the
                // OEM can replace/recycle on its next background update.
                material.setInputShader("uSource",empty)
                material.setInputShader("uSourceLowRes",empty)
            }
        }.onFailure {opticalError=it.javaClass.simpleName}.getOrDefault(false)
        // The shared shader already draws the preset's optical edge. A second
        // fixed white stroke made native clear glass look like a plastic ring.
        // Only the no-source/error base needs this soft, preset-scaled outline.
        if(submitted)return true
        rim.strokeWidth=density.coerceIn(.5f,4f)*.5f
        val inset=rim.strokeWidth*.5f
        val edge=RectF(area).apply {inset(inset,inset)}
        canvas.drawRoundRect(edge,(radius-inset).coerceAtLeast(0f),(radius-inset).coerceAtLeast(0f),rim)
        return true
    }
}
