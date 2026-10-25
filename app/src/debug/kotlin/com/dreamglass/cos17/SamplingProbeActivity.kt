package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.View
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Actual Android GPU sampling regression. Debug source set only, no module/framework required. */
class SamplingProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(object : View(this) {
            private val bitmap = Bitmap.createBitmap(32, 4, Bitmap.Config.ARGB_8888).apply {
                for (y in 0..3) for (x in 0..31) setPixel(x,y,Color.rgb(x*8,x*8,x*8))
            }
            private val sources = listOf(
                BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
                },
                LiquidGlassShader.createSourceBitmapShader(bitmap),
                LiquidGlassShader.createLowResSourceBitmapShader(bitmap),
            )
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(12,17,32))
                val scale = (width-64)/32f
                for (i in 0..2) {
                    val child=sources[i]
                    if(i>0) check(child.filterMode==BitmapShader.FILTER_MODE_LINEAR)
                    val shader=RuntimeShader("uniform shader src; uniform float factor; half4 main(float2 p) { return src.eval(float2(p.x/factor, 1.5)); }")
                    shader.setInputShader("src",child);shader.setFloatUniform("factor",scale)
                    val paint=Paint().apply { this.shader=shader }
                    val saved=canvas.save();canvas.translate(32f,150f+i*260)
                    canvas.drawRect(0f,0f,width-64f,160f,paint);canvas.restoreToCount(saved)
                    val label=Paint().apply { color=Color.WHITE;textSize=28f }
                    canvas.drawText(listOf("REFERENCE NEAREST","PRODUCTION SOURCE LINEAR","PRODUCTION LOW-RES LINEAR")[i],32f,130f+i*260,label)
                }
            }
        })
    }
}
