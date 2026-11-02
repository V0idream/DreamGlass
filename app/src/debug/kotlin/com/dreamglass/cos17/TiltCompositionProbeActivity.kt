package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import com.coloros16.liquidglass.hook.LauncherTiltAffine
import org.json.JSONObject
import java.io.File

/** Hardware Canvas fixture of the exact 6 affine coefficients sent through the
 * native i(Matrix) argument. Does not claim OEM callback or 4x4 compositor proof.
 */
class TiltCompositionProbeActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val report=JSONObject().put("vendorHookTested",false).put("physicalDeviceTested",false)
        val view=object:View(this) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas) {
                report.put("hardwareAccelerated",canvas.isHardwareAccelerated)
                canvas.drawColor(Color.rgb(11,20,29))
                val factor=width/800f;canvas.save();canvas.scale(factor,factor)
                paint.color=Color.WHITE;paint.textSize=25f
                canvas.drawText("Native animation + compatible tilt",26f,42f,paint)
                paint.textSize=18f;paint.color=Color.LTGRAY
                canvas.drawText("Native",112f,78f,paint);canvas.drawText("Composed",505f,78f,paint)
                for((row,p) in listOf(0f,.25f,.5f,.75f,1f).withIndex()) {
                    val top=95f+row*224f
                    paint.color=Color.rgb(28,43,54);canvas.drawRoundRect(20f,top,780f,top+210,14f,14f,paint)
                    for(column in 0..1) {
                        val scale=.25f+.75f*p
                        val native=floatArrayOf(scale,0f,100f*(1-p),0f,scale,18f*(1-p),0f,0f,1f)
                        val submitted=FloatArray(9)
                        val active=LauncherTiltAffine.compose(native,submitted,p,1f,1f,100f,93f,1f)
                        if(p==0f || p==1f)check(!active && native.contentEquals(submitted))
                        val cx=100f;val cy=93f
                        check(kotlin.math.abs(native[0]*cx+native[1]*cy+native[2]-(submitted[0]*cx+submitted[1]*cy+submitted[2]))<.001f)
                        check(kotlin.math.abs(native[3]*cx+native[4]*cy+native[5]-(submitted[3]*cx+submitted[4]*cy+submitted[5]))<.001f)
                        val m=Matrix().apply {setValues(if(column==0)native else submitted)}
                        canvas.save();canvas.translate(80f+column*390f,top+10f);canvas.concat(m)
                        paint.shader=LinearGradient(0f,0f,200f,186f,intArrayOf(Color.rgb(129,223,210),Color.rgb(42,94,143)),null,Shader.TileMode.CLAMP)
                        canvas.drawRoundRect(0f,0f,200f,186f,15f,15f,paint);paint.shader=null
                        paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=Color.argb(70,255,255,255)
                        for(i in 1..3) {canvas.drawLine(i*50f,0f,i*50f,186f,paint);canvas.drawLine(0f,i*46f,200f,i*46f,paint)}
                        paint.style=Paint.Style.FILL;paint.color=Color.rgb(12,32,48);paint.textSize=20f
                        canvas.drawText("DreamGlass",16f,36f,paint);paint.textSize=14f
                        canvas.drawText("Original scale retained",16f,66f,paint)
                        canvas.drawCircle(45f,109f,17f,paint);canvas.drawRoundRect(77f,93f,179f,125f,9f,9f,paint)
                        paint.textSize=15f;canvas.drawText("Animation ${ (p*100).toInt() }%",16f,164f,paint)
                        canvas.restore()
                    }
                    paint.color=Color.rgb(251,197,103);paint.strokeWidth=2f
                    val centreX=80f+(.25f+.75f*p)*100f+100f*(1-p)
                    val centreY=top+10f+(.25f+.75f*p)*93f+18f*(1-p)
                    for(offset in floatArrayOf(0f,390f)) {
                        canvas.drawLine(centreX+offset-5,centreY,centreX+offset+5,centreY,paint)
                        canvas.drawLine(centreX+offset,centreY-5,centreX+offset,centreY+5,paint)
                    }
                }
                paint.color=Color.LTGRAY;paint.textSize=17f
                canvas.drawText("GPU fixture; OEM Launcher visible behavior remains unverified",26f,1245f,paint)
                canvas.restore()
                report.put("nativeCentrePreserved",true).put("endpointMatricesExact",true)
            }
        }
        setContentView(view)
        view.postDelayed({
            val b=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(window,b,{status ->
                try {
                    check(status==PixelCopy.SUCCESS && report.getBoolean("hardwareAccelerated"))
                    val dir=File(filesDir,"tilt-composition-probe").apply {mkdirs()}
                    File(dir,"comparison.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
                    report.put("pixelCopySuccess",true)
                    File(dir,"report.json").writeText(report.toString(2))
                    android.util.Log.i("TiltCompositionProbe","PASS $report")
                } finally {b.recycle()}
            },handler)
        },650)
    }
}
