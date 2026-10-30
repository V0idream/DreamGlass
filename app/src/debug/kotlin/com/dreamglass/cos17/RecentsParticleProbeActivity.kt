package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject
import java.io.File

/** Synthetic task cards only. No ActivityManager, real task removal, or private thumbnails. */
class RecentsParticleProbeActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private val api=RecentsParticleHook
    private lateinit var root:FrameLayout
    private lateinit var card:FrameLayout
    private lateinit var label:TextView
    private var clicks=0
    private val report=JSONObject()
    private val output by lazy {File(filesDir,"recents-particle-probe").apply {mkdirs()}}
    private fun step(delay:Long=0,action:()->Unit) {ui.postDelayed({try {action()} catch(t:Throwable) {fail(t)}},delay)}
    private fun fail(error:Throwable) {api.stopAll();Log.e("RecentsParticleProbe","FAIL",error);label.text="FAIL $error"}
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        root=FrameLayout(this).apply {setBackgroundColor(Color.rgb(13,22,35))};setContentView(root)
        label=TextView(this).apply {text="DreamGlass · recents GPU particle test";setTextColor(Color.WHITE);textSize=17f;setPadding(25,25,25,0)}
        root.addView(label,FrameLayout.LayoutParams(-1,200))
        card=FrameLayout(this).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(29,92,116),Color.rgb(27,42,75))).apply {cornerRadius=36f}
            clipToOutline=true;setOnClickListener {clicks++}
        }
        val title=TextView(this).apply {text="DreamGlass · Test notes\n\nA synthetic recent task\n\nGPU-only dissolve\nNo real app is closed";setTextColor(Color.WHITE);textSize=26f;setPadding(35,35,35,20)}
        card.addView(title,FrameLayout.LayoutParams(-1,-1))
        root.addView(card,FrameLayout.LayoutParams(560,900).apply {leftMargin=230;topMargin=320})
        root.postDelayed({step {begin()}},450)
    }
    private fun awaitHidden(t:RecentsParticleHook.Ticket,then:()->Unit) {
        var attempts=0
        fun poll() {step(16) {
            check(!t.cancelled) {"GPU handoff was late or unavailable"}
            if(t.hidden)then() else {check(attempts++<40);poll()}
        }}
        poll()
    }
    private fun begin() {
        val attempt=api.capture(card)!!
        check(!attempt.submitted && !attempt.hidden && card.alpha==1f)
        api.cancel(attempt);check(card.alpha==1f)
        report.put("prepareDoesNotPlay",true).put("cancelBeforeAcceptKeepsCard",true)
        val t=api.capture(card)!!;api.accept(t)
        awaitHidden(t) {
            check(card.alpha==0f && t.dust!=null)
            card.performClick();check(clicks==1)
            card.alpha=api.nativeAlpha(card,.37f);check(card.alpha==0f)
            saveFrame("active.png")
            api.cancel(t);check(kotlin.math.abs(card.alpha-.37f)<.001f)
            report.put("gpuHandoffHidesSource",true).put("nativeAlphaUpdatesRetained",true).put("clickPreserved",true)
            card.alpha=1f
            val natural=api.capture(card)!!;api.accept(natural)
            awaitHidden(natural) {
                step(1300) {
                    check(natural.cancelled && natural.dust==null && card.alpha==1f)
                    report.put("naturalEndRestoresSource",true)
                    slowPreset()
                }
            }
        }
    }
    private fun slowPreset() {
        val speed=api.javaClass.getDeclaredField("speed").apply {isAccessible=true}
        speed.setInt(api,35)
        val slow=api.capture(card)!!;api.accept(slow)
        speed.setInt(api,100) // In-flight work must retain the speed chosen at acceptance.
        awaitHidden(slow) {
            step(2000) {
                check(!slow.cancelled && slow.dust!=null) {"Slow preset cut off by preparation timeout"}
                step(1400) {
                    check(slow.cancelled && card.alpha==1f)
                    report.put("slowPresetCompletesWithoutEarlyTimeout",true).put("inFlightSpeedStable",true)
                    pressure()
                }
            }
        }
    }
    private fun pressure() {
        val small=(0..2).map {i->View(this).apply {setBackgroundColor(Color.CYAN);root.addView(this,FrameLayout.LayoutParams(80,80).apply {leftMargin=i*100;topMargin=1400})}}
        step(60) {
            check(api.capture(small[0])!=null);check(api.capture(small[1])!=null);check(api.capture(small[2])==null)
            api.stopAll();small.forEach {root.removeView(it)}
            check(card.alpha==1f)
            report.put("concurrentCaptureLimit",2).put("stopAllReleasesPending",true)
            val detached=api.capture(card)!!;api.accept(detached)
            awaitHidden(detached) {
                root.removeView(card)
                check(detached.dust!=null) // Native successful deletion does not kill the overlay.
                api.stopAll();check(detached.cancelled && detached.dust==null)
                report.put("removedCardDoesNotKillAcceptedDust",true).put("stopReleasesDetachedSource",true)
                verticalShader()
            }
        }
    }
    private fun render(node:RenderNode,w:Int,h:Int):Bitmap = HardwareBackdropBlur().use {
        it.render(node,w,h,w,h,.01f) {false}.copy(Bitmap.Config.ARGB_8888,false)!!
    }
    private fun saveFrame(name:String) {
        val view=root.rootView
        val node=RenderNode("recents frame");node.setPosition(0,0,view.width,view.height)
        val c=node.beginRecording(view.width,view.height);view.draw(c);node.endRecording()
        try {val b=render(node,view.width,view.height);File(output,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        finally {node.discardDisplayList()}
    }
    private fun verticalShader() {
        val image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        Canvas(image).drawColor(Color.CYAN)
        val shader=RuntimeShader(ParticleDissolveHook.Api33.SHADER)
        shader.setInputShader("uImage",BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP))
        shader.setFloatUniform("uSize",200f,300f);shader.setFloatUniform("uImageScale",1f,1f)
        shader.setFloatUniform("uPad",150f);shader.setFloatUniform("uCell",2f)
        shader.setFloatUniform("uVertical",1f);shader.setFloatUniform("uDirection",-1f)
        var upward=0;var end=0
        for(progress in listOf(.55f,1f)) {
            shader.setFloatUniform("uProgress",progress)
            val node=RenderNode("upward particles");node.setPosition(0,0,500,600)
            val c=node.beginRecording(500,600);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            c.drawRect(0f,0f,500f,600f,Paint().apply {this.shader=shader});node.endRecording()
            val b=try {render(node,500,600)} finally {node.discardDisplayList()}
            for(y in 0 until 600)for(x in 0 until 500)if(Color.alpha(b.getPixel(x,y))>8) {
                if(progress==1f)end++ else if(y<150)upward++
            }
            if(progress==.55f)File(output,"upward.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        check(upward>50 && end==0) {"upward=$upward end=$end"}
        report.put("upwardEscapedPixels",upward).put("endTransparent",true).put("physicalLauncherHookTested",false)
        File(output,"summary.json").writeText(report.toString(2));Log.i("RecentsParticleProbe","PASS $report")
        label.text="PASS · recents particle GPU lifecycle"
    }
    override fun onDestroy() {ui.removeCallbacksAndMessages(null);api.stopAll();super.onDestroy()}
}
