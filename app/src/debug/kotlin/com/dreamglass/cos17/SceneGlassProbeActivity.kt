package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import com.coloros16.liquidglass.glass.SharedGlassParams
import com.coloros16.liquidglass.glass.IosGlassPreset
import com.coloros16.liquidglass.hook.*
import java.io.File
import org.json.JSONObject

/** Synthetic background-channel fixture: exercises the real shared optical graph, not OEM IPC. */
class SceneGlassProbeActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private lateinit var label:TextView
    private lateinit var root:FrameLayout
    private val inputs=ArrayList<Backdrop>()
    private val buttons=ArrayList<FrameLayout>()
    private val effects=ArrayList<AppGlassEffect>()
    private var second=false
    private var clicks=0
    private val report=JSONObject().put("physicalDeviceTested",false).put("vendorBridgeExecuted",false)
    private fun fail(t:Throwable) { Log.e("SceneGlassProbe","FAIL",t);ui.post { label.text="FAIL $t" } }
    private inner class Backdrop:View(this@SceneGlassProbeActivity) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas) {
            paint.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),
                if(second)Color.rgb(30,180,110) else Color.rgb(42,68,174),
                if(second)Color.rgb(170,200,45) else Color.rgb(196,65,116),Shader.TileMode.CLAMP)
            c.drawPaint(paint);paint.shader=null
            paint.color=Color.argb(130,245,240,220)
            c.drawCircle(width*.75f,height*.65f,width*.30f,paint)
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        root=FrameLayout(this).apply { setBackgroundColor(Color.rgb(11,21,31)) }
        label=TextView(this).apply { text="Shared glass / GPU input test";textSize=18f;setTextColor(Color.WHITE);setPadding(25,25,25,0) }
        root.addView(label,FrameLayout.LayoutParams(-1,300));setContentView(root)
        root.post { try { setup() } catch(t:Throwable) { fail(t) } }
    }
    private fun setup() {
        // A wide native toolbar remains unframed; only the individual control backgrounds filter.
        check(AppGlassSurfacePolicy.role("GalleryToolbar","top_action_bar",1080f,150f,1080f,2340f,true,false,true)==0)
        for((i,title) in listOf("Back","Share","More").withIndex()) {
            val frame=FrameLayout(this).apply { setOnClickListener { clicks++ } }
            val input=Backdrop();frame.addView(input,FrameLayout.LayoutParams(-1,-1));inputs.add(input)
            frame.addView(TextView(this).apply { text=title;textSize=23f;gravity=Gravity.CENTER;setTextColor(Color.WHITE) },FrameLayout.LayoutParams(-1,-1))
            root.addView(frame,FrameLayout.LayoutParams(270,270).apply { leftMargin=40+i*320;topMargin=350 })
            buttons.add(frame);effects.add(AppGlassEffect())
        }
        root.postDelayed({capture("native") { native ->
            try {
                for((i,input) in inputs.withIndex()) {
                    val p=SharedGlassParams.resolve(emptyMap(),IosGlassPreset.ID,null,270f,270f,135f,true)
                    check(p.refractionAmount==36f && p.blurRadius==8f)
                    input.setRenderEffect(effects[i].create(p));input.invalidate()
                }
                ui.postDelayed({capture("ios-shared") { optical ->
                    try {
                        var changed=0
                        for(y in 350 until 620)for(x in 40 until 950)if(native.getPixel(x,y)!=optical.getPixel(x,y))changed++
                        check(changed>1000) { "No optical response $changed" }
                        report.put("sharedOpticalPixelsChanged",changed).put("threeControlsShareIosPreset",true).put("outerToolbarUnframed",true)
                        second=true;inputs.forEach { it.invalidate() }
                        ui.postDelayed({capture("changed-native-input") { updated ->
                            try {
                                var response=0
                                for(y in 360 until 600)for(x in 50 until 930)if(optical.getPixel(x,y)!=updated.getPixel(x,y))response++
                                check(response>10000)
                                buttons.forEach { it.performClick() };check(clicks==3)
                                inputs.forEach { it.setRenderEffect(null) }
                                report.put("liveInputPixelsChangedWithoutEffectRebuild",response).put("foregroundClicksRetained",clicks)
                                borrowedSnapshot()
                            } catch(t:Throwable) { fail(t) }
                        }},160)
                    } catch(t:Throwable) { fail(t) }
                }},200)
            } catch(t:Throwable) { fail(t) }
        }},150)
    }
    private fun capture(name:String,done:(Bitmap)->Unit) {
        val result=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
        PixelCopy.request(window,result,{ status ->try {
            check(status==PixelCopy.SUCCESS)
            File(filesDir,"scene-probe").apply { mkdirs() }.resolve("$name.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG,100,it) }
            done(result)
        } catch(t:Throwable) { fail(t) }},ui)
    }
    private fun borrowedSnapshot() {
        val borrowed=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        borrowed.eraseColor(Color.rgb(30,200,90))
        val node=RenderNode("Borrowed vendor image fixture");node.setPosition(0,0,128,128)
        val c=node.beginRecording(128,128);c.drawBitmap(borrowed,0f,0f,null);node.endRecording()
        borrowed.recycle() // The host may release its Bitmap immediately after the callback returns.
        Thread {
            try {
                val owned=HardwareBackdropBlur().use { it.render(node,128,128,64,64,.01f) { false } }
                check(owned.config==Bitmap.Config.HARDWARE)
                val pixels=owned.copy(Bitmap.Config.ARGB_8888,false)!! // debug-only readback
                val colour=pixels.getPixel(32,32)
                check(Color.green(colour)>185 && Color.alpha(colour)>250)
                report.put("recordedWallpaperSurvivesHostBitmapRecycle",true).put("hardwareOwnedOutput",true)
                ui.post { label.text="PASS\n"+report.toString(2);Log.i("SceneGlassProbe","PASS $report") }
            } catch(t:Throwable) { fail(t) } finally { node.discardDisplayList() }
        }.start()
    }
}
