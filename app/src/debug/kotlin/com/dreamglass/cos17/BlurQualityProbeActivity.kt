package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.util.Log
import android.view.View
import com.coloros16.liquidglass.hook.ButtonBackdropBlur
import com.coloros16.liquidglass.hook.ButtonBackdropWorker
import com.coloros16.liquidglass.hook.FloatBackdropBitmap
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Debug only: exercises production float packing, worker fairness and both Android AGSL variants. */
class BlurQualityProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(object : View(this) {
            val input=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888).apply {
                for(y in 0 until height)for(x in 0 until width)
                    setPixel(x,y,if((x/6+y/6)%2==0)Color.WHITE else Color.BLACK)
            }
            val worker=ButtonBackdropWorker({postInvalidateOnAnimation()},{throw AssertionError(it)})
            var passed=false
            init {
                val packed=FloatBackdropBitmap.create(floatArrayOf(255f,123.456f,11.25f,45.875f),1,1)
                check(packed.config==Bitmap.Config.RGBA_F16)
                val color=packed.getColor(0,0)
                check(kotlin.math.abs(color.red()*255-123.456f)<.08f)
                check(kotlin.math.abs(color.red()*255-kotlin.math.round(color.red()*255))>.2f)
                val translucent=FloatBackdropBitmap.create(floatArrayOf(128f,64f,32f,16f),1,1).getColor(0,0)
                check(kotlin.math.abs(translucent.alpha()-128f/255)<.001f)
                check(kotlin.math.abs(translucent.red()-.5f)<.001f)
            }
            override fun onDraw(canvas:Canvas) {
                canvas.drawColor(Color.rgb(12,17,32))
                val filtered=listOf(2f,8f,16f,32f).map { worker.resolve(input,RectF(0f,0f,384f,384f),it) }
                val ready=filtered.all { it!=null }
                val label=Paint().apply { color=Color.WHITE;textSize=24f }
                canvas.drawText("PRODUCTION F16 / DENSE BLUR / INNER VOLUME",24f,100f,label)
                val size=minOf((width-72f)/2,384f)
                for(i in 0..3) {
                    val bitmap=filtered[i]?:input
                    val shader=LiquidGlassShader.create(metaball=true)
                    val params=LiquidGlassShader.Params(viewportWidth=size,viewportHeight=size,
                        cornerRadius=size/2,blurRadius=16f,surfacePolish=true,sourcePreblurred=filtered[i]!=null)
                    val src=LiquidGlassShader.createSourceBitmapShader(bitmap)
                    LiquidGlassShader.setUniforms(shader,params,src,src,sourceRect=RectF(0f,0f,384f,384f))
                    val save=canvas.save();canvas.translate(24f+(i%2)*(size+24),160f+(i/2)*(size+60))
                    canvas.drawRect(0f,0f,size,size,Paint().apply { this.shader=shader })
                    canvas.restoreToCount(save)
                }
                if(ready && !passed) {
                    // Also compile the BASE variant used when metaballs are not available.
                    RuntimeShader(LiquidGlassShader.AGSL_SOURCE_BASE)
                    passed=true;Log.i("DreamGlassQualityProbe","PASS F16 fractional/alpha; four worker styles; FULL/BASE AGSL")
                }
                if(!ready)postInvalidateDelayed(100)
            }
        })
    }
}
