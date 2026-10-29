package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.util.Log
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.glass.IosGlassPreset
import com.coloros16.liquidglass.hook.ButtonBackdropBinding
import com.coloros16.liquidglass.hook.ButtonBackdropWorker
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Debug-only controlled old/new optical study, with the production IOS profile and F16 binding. */
class MaterialStudyProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(object : View(this) {
            val before=RuntimeShader(assets.open("probes/baseline-1.0.7.agsl").bufferedReader().use { it.readText() })
            val after=LiquidGlassShader.create(metaball=true)
            val image=Bitmap.createBitmap(1080,2100,Bitmap.Config.ARGB_8888).apply {
                val c=Canvas(this)
                val p=Paint().apply { shader=LinearGradient(0f,0f,0f,2100f,
                    intArrayOf(0xff152341.toInt(),0xff356284.toInt(),0xffe5d3b4.toInt(),0xff795b9e.toInt()),null,Shader.TileMode.CLAMP) }
                c.drawPaint(p);p.shader=null;p.textSize=34f
                for(col in 0..1) {
                    val saved=c.save();c.translate(col*520f,0f);c.clipRect(0f,0f,500f,2100f)
                    for(y in 160..1900 step 128) {
                        p.color=if(y<1000)0xffd5e1f2.toInt() else 0xff29384a.toInt()
                        c.drawText("DreamGlass 012345 / form",30f,y.toFloat(),p)
                        p.color=if(y<1000)0xffce6976.toInt() else 0xff678abb.toInt()
                        c.drawRoundRect(230f,y-45f,490f,y+40f,30f,30f,p)
                    }
                    c.restoreToCount(saved)
                }
            }
            val worker=ButtonBackdropWorker({postInvalidateOnAnimation()},{throw AssertionError(it)}, {1})
            val values=IosGlassPreset.values
            var logged=false
            fun params(w:Float,h:Float,r:Float)=LiquidGlassShader.Params(
                viewportWidth=w,viewportHeight=h,cornerRadius=r,
                blurRadius=values.getValue(Prefs.KEY_BLUR_RADIUS),refractionHeight=minOf(w,h)*values.getValue(Prefs.KEY_REFRACTION_HEIGHT_RATIO),
                refractionAmount=values.getValue(Prefs.KEY_REFRACTION_AMOUNT),depth=values.getValue(Prefs.KEY_DEPTH),
                dispersion=values.getValue(Prefs.KEY_DISPERSION),vibrancy=values.getValue(Prefs.KEY_VIBRANCY),
                highlight=values.getValue(Prefs.KEY_HIGHLIGHT),highlightWidth=values.getValue(Prefs.KEY_HIGHLIGHT_WIDTH),
                highlightFalloff=values.getValue(Prefs.KEY_HIGHLIGHT_FALLOFF),highlightFloor=values.getValue(Prefs.KEY_HIGHLIGHT_FLOOR),
                highlightInnerAtt=values.getValue(Prefs.KEY_HIGHLIGHT_INNER_ATT),lightAngleDegrees=values.getValue(Prefs.KEY_LIGHT_ANGLE),
                surfacePolish=true)
            override fun onDraw(canvas:Canvas) {
                canvas.drawColor(0xff795b9e.toInt());canvas.drawBitmap(image,0f,0f,Paint())
                val title=Paint().apply { color=Color.WHITE;textSize=27f }
                canvas.drawText("SAME IOS PROFILE / OLD OPTICS       NEW OPTICS",20f,70f,title)
                var ready=true
                for(row in 0..4) for(col in 0..1) {
                    val w=if(row%2==0)370f else 240f;val h=if(row==3)300f else 200f
                    val x=30f+col*520f;val y=130f+row*375f;val r=if(row==0)100f else 70f
                    val bind=ButtonBackdropBinding.resolve(worker,image,RectF(0f,0f,1080f,2100f),
                        RectF(x,y,x+w,y+h),true,params(w,h,r))
                    ready=ready && !bind.pending
                    val source=LiquidGlassShader.createSourceBitmapShader(bind.bitmap)
                    val shader=if(col==0)before else after
                    LiquidGlassShader.setUniforms(shader,bind.params,source,source,
                        sourceRect=bind.sourceRect,maskAlpha=values.getValue(Prefs.KEY_MASK_GLASS_DIM))
                    val save=canvas.save();canvas.translate(x,y)
                    canvas.drawRect(0f,0f,w,h,Paint().apply { this.shader=shader });canvas.restoreToCount(save)
                }
                if(!ready)postInvalidateDelayed(100)
                else if(!logged) {
                    logged=true;Log.i("DreamGlassMaterialProbe","PASS production IOS profile, F16 binding, old/new Android AGSL rendered")
                }
            }
        })
    }
}
