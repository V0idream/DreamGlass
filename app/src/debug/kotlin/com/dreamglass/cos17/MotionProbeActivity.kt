package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.coloros16.liquidglass.hook.LauncherTiltCompat
import com.coloros16.liquidglass.hook.NotificationTiltCompat
import java.io.File
import org.json.JSONObject

/** Synthetic RenderNode/Canvas fixture, not a vendor Launcher/SystemUI hook test. */
class MotionProbeActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private lateinit var label:TextView
    private lateinit var card:View
    private val report=JSONObject().put("physicalDeviceTested",false).put("vendorHookTested",false)
    private fun fail(error:Throwable) { Log.e("MotionProbe","FAIL",error);label.text="FAIL $error" }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val box=FrameLayout(this).apply { setBackgroundColor(Color.rgb(10,20,30)) }
        label=TextView(this).apply { setTextColor(Color.WHITE);textSize=17f;setPadding(30,30,30,0);text="3D motion regression…" }
        card=object:View(this) {
            private val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas) {
                p.shader=LinearGradient(0f,0f,640f,260f,Color.rgb(67,186,167),Color.rgb(41,65,139),Shader.TileMode.CLAMP)
                c.drawRoundRect(0f,0f,640f,260f,32f,32f,p);p.shader=null
                p.color=Color.WHITE;p.textSize=32f;c.drawText("DreamGlass · Notification",30f,62f,p)
                p.textSize=23f;c.drawText("RenderNode 3D / native translation retained",30f,110f,p)
            }
        }
        box.addView(card,FrameLayout.LayoutParams(640,260).apply { leftMargin=60;topMargin=300 })
        box.addView(label,FrameLayout.LayoutParams(-1,230));setContentView(box)
        box.post { try { runProbe(box) } catch(e:Throwable) { fail(e) } }
    }
    private fun save(name:String,done:()->Unit) {
        val b=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
        PixelCopy.request(window,b,{ result ->
            try {
                check(result==PixelCopy.SUCCESS)
                val dir=File(filesDir,"motion-probe").apply { mkdirs() }
                File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
                b.recycle();done()
            } catch(e:Throwable) { fail(e) }
        },ui)
    }
    private fun runProbe(box:FrameLayout) {
        card.rotationX=7f;card.scaleX=.9f;card.scaleY=.8f;card.pivotX=42f;card.pivotY=21f
        card.translationX=17f;card.translationY=23f
        val camera=card.cameraDistance
        var finished=0
        val animation=NotificationTiltCompat.Animation(card,true,38f,.25f,{true}) { finished++ }
        animation.start()
        ui.postDelayed({ try {
            check(card.rotationX>7f && card.scaleX<.9f)
            check(card.translationX==17f && card.translationY==23f)
            report.put("nativeRowHasVisible3DPose",true).put("nativeTranslationPreserved",true)
            save("notification-tilted") {
                try {
                    animation.stop();check(finished==1)
                    check(card.rotationX==7f && card.scaleX==.9f && card.scaleY==.8f)
                    check(card.pivotX==42f && card.pivotY==21f && card.cameraDistance==camera)
                    report.put("cancelRestoresOriginalTransform",true)
                    val next=NotificationTiltCompat.Animation(card,false,30f,.15f,{true}) { finished++ }
                    next.start()
                    ui.postDelayed({ try {
                        // Another host animation takes ownership; stop must keep this newer value.
                        card.rotationX=11f;next.stop();check(card.rotationX==11f && finished==2)
                        report.put("newHostTransformSurvivesCleanup",true)
                        val detach=NotificationTiltCompat.Animation(card,true,30f,0f,{true}) { finished++ }
                        detach.start();box.removeView(card);check(finished==3)
                        report.put("detachStopsCallbacks",true)
                        box.addView(card,FrameLayout.LayoutParams(640,260).apply { leftMargin=60;topMargin=300 })
                        val natural=NotificationTiltCompat.Animation(card,true,30f,0f,{true}) { finished++ }
                        natural.start()
                        ui.postDelayed({ try {
                            check(finished==4 && card.rotationX==11f)
                            report.put("naturalEndRestoresTransform",true)
                            projectionView(box)
                        } catch(e:Throwable) { fail(e) } },600)
                    } catch(e:Throwable) { fail(e) } },100)
                } catch(e:Throwable) { fail(e) }
            }
        } catch(e:Throwable) { fail(e) } },110)
    }
    private fun projectionView(box:FrameLayout) {
        box.addView(object:View(this) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas) {
                val values=FloatArray(16)
                check(LauncherTiltCompat.projection(values,.5f,1.5f,1f,320f,160f,320f,1f))
                val m=Matrix().apply { setValues(floatArrayOf(values[0],values[1],values[3],values[4],values[5],values[7],values[12],values[13],values[15])) }
                c.translate(70f,70f);c.concat(m);paint.shader=LinearGradient(0f,0f,640f,320f,Color.CYAN,Color.BLUE,Shader.TileMode.CLAMP)
                c.drawRoundRect(0f,0f,640f,320f,30f,30f,paint);paint.shader=null
                paint.color=Color.WHITE;paint.textSize=30f;c.drawText("Window projection · p=0.5",30f,90f,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
                for(x in 0..640 step 80)c.drawLine(x.toFloat(),0f,x.toFloat(),320f,paint)
                paint.style=Paint.Style.FILL
            }
        },FrameLayout.LayoutParams(780,480).apply { leftMargin=20;topMargin=750 })
        ui.postDelayed({save("window-projection") {
            report.put("gpuCanvasProjectionRendered",true)
            label.text="PASS\n"+report.toString(2);Log.i("MotionProbe","PASS $report")
        }},100)
    }
}
