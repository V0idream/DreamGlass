package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.widget.TextView
import android.widget.FrameLayout
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import com.coloros16.liquidglass.hook.GalleryDialogBackgroundLease
import com.coloros16.liquidglass.hook.LiveContentGlassNode
import com.coloros16.liquidglass.hook.ClearButtonGlassSurface
import com.coloros16.liquidglass.hook.NativePaintShaderLease
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.io.File
import org.json.JSONObject

/** Owned GPU scene only; no vendor app, screenshots or private media. Exercises
 * the production live node and Paint scope, including unchanged material while
 * native content and its position change. Not OEM/physical visual acceptance. */
class LiveContentBackdropProbeActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val text=TextView(this).apply {text="Live native content GPU probe"};setContentView(text)
        text.post {try {text.text=probe().toString(2)} catch(error:Throwable) {
            android.util.Log.e("LiveContentBackdropProbe","FAIL",error);text.text="FAIL $error"
        }}
    }
    private fun probe():JSONObject {
        val source=RenderNode("native content source").apply {setPosition(13,19,333,279)}
        val live=LiveContentGlassNode()
        val area=RectF(83f,97f,283f,237f)
        val params=LiquidGlassShader.Params(area.width(),area.height(),cornerRadius=40f,
            refractionAmount=0f,depth=0f,highlight=0f,vibrancy=1f,debugGlassBounds=false)
        val output=File(filesDir,"live-content-backdrop-probe").apply {mkdirs()}
        fun updateSource(phase:Int) {
            val c=source.beginRecording(320,260)
            val paint=Paint()
            for(y in 0 until 260) {
                paint.color=Color.rgb(45+phase*105,y.coerceAtMost(255),70)
                c.drawRect(0f,y.toFloat(),320f,(y+1).toFloat(),paint)
            };source.endRecording()
        }
        val reader=ImageReader.newInstance(360,300,PixelFormat.RGBA_8888,1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val root=RenderNode("probe output").apply {setPosition(0,0,360,300)}
        renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(root)
        fun render(name:String,hostX:Float=0f,hostY:Float=0f,draw:((Canvas)->Unit)?=null):Bitmap {
            val c=root.beginRecording(360,300);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            c.translate(hostX,hostY);if(draw==null)live.draw(c) else draw(c);root.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage() ?: error("No output image")
            try {
                val buffer=image.hardwareBuffer ?: error("No output buffer")
                try {
                    val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    try {return hardware.copy(Bitmap.Config.ARGB_8888,false)!!.also {
                        File(output,"$name.png").outputStream().use {stream->it.compress(Bitmap.CompressFormat.PNG,100,stream)}
                    }} finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        }

        try {
            updateSource(0)
            check(live.update(source,Matrix(),area,params,1f))
            val effect=live.effect
            val first=render("initial")
            updateSource(1)
            // Same operation as Gallery's existing preDraw frame, not a timer.
            // Refreshing source content must NOT schedule another frame itself.
            check(!live.update(source,Matrix(),area,params,1f,refreshContent=true))
            val second=render("native-content-changed")
            source.translationY=21f
            check(!live.update(source,Matrix(),area,params,1f,refreshContent=true))
            val third=render("native-content-moved")
            live.update(source,Matrix().apply {setTranslate(-37f,15f)},area,params,1f)
            val fourth=render("host-origin-moved",37f,-15f)
            check(live.effect===effect)
            fun near(actual:Int,expected:Int)=kotlin.math.abs(actual-expected)<=3
            val a=first.getPixel(180,150);val b=second.getPixel(180,150);val c=third.getPixel(180,150)
            check(near(Color.red(a),45) && near(Color.red(b),150)) {"Frozen live content: ${Color.red(a)} -> ${Color.red(b)}"}
            check(near(Color.green(b)-Color.green(c),21)) {"Source motion not followed: ${Color.green(b)} -> ${Color.green(c)}"}
            check(near(Color.green(fourth.getPixel(217,135)),95)) {"Host and source origins were applied twice"}
            fourth.recycle()
            for(image in listOf(first,second,third)) {
                check(Color.alpha(image.getPixel(50,150))==0)
                check(Color.alpha(image.getPixel(290,150))==0)
                check(Color.alpha(image.getPixel(180,80))==0)
                check(Color.alpha(image.getPixel(180,245))==0)
                check(Color.alpha(image.getPixel(180,150))==255)
                image.recycle()
            }
            val paint=Paint();val native=LinearGradient(0f,0f,20f,20f,Color.RED,Color.BLUE,Shader.TileMode.CLAMP)
            paint.shader=native
            val replacement=LinearGradient(0f,0f,10f,10f,Color.BLACK,Color.WHITE,Shader.TileMode.CLAMP)
            val expected=IllegalStateException("expected")
            try {NativePaintShaderLease.withShader(paint,replacement) {throw expected}} catch(e:IllegalStateException) {check(e===expected)}
            check(paint.shader===native) {"Native Paint source not restored after recording failure"}
            val button=ClearButtonGlassSurface()
            val buttonArea=RectF(45f,80f,305f,150f)
            val bp=params.copy(viewportWidth=buttonArea.width(),viewportHeight=buttonArea.height(),cornerRadius=35f)
            fun buttonImage(name:String,source:Shader?)=render(name,draw={canvas ->
                check(button.draw(canvas,buttonArea,bp,source,1f))
            })
            val absent=buttonImage("clear-no-native-input",null)
            val transparent=buttonImage("clear-transparent-native-input",LinearGradient(0f,0f,1f,1f,0,0,Shader.TileMode.CLAMP))
            for(image in listOf(absent,transparent)) {
                check(Color.alpha(image.getPixel(170,115))>=24) {"Clear capsule disappeared with no visible source"}
                check(Color.alpha(image.getPixel(20,115))==0)
                check(Color.alpha(image.getPixel(46,81))==0)
                image.recycle()
            }
            val nativeInput=RuntimeShader("""
                uniform float phase;
                half4 main(float2 p) {return half4(phase,p.y/300.0,0.15,1.0);}
            """.trimIndent())
            val untouchedPaint=Paint().apply {shader=nativeInput;alpha=0}
            nativeInput.setFloatUniform("phase",.15f)
            val ca=buttonImage("clear-live-initial",nativeInput)
            nativeInput.setFloatUniform("phase",.75f)
            val cb=buttonImage("clear-live-changed",nativeInput)
            check(Color.red(cb.getPixel(170,115))-Color.red(ca.getPixel(170,115))>100) {"Clear live source frozen"}
            check(untouchedPaint.shader===nativeInput && untouchedPaint.alpha==0) {"Native Paint mutated"}
            ca.recycle();cb.recycle()
            // Exercise the real View.draw/dispatchDraw path with a willNotDraw
            // parent and a separate gray overlay, using the production leases.
            // This is an owned scene, not an emulator imitation of COUI classes.
            var panelMaterial=true
            val panel=object:FrameLayout(this) {
                override fun dispatchDraw(c:Canvas) {if(panelMaterial)live.draw(c);super.dispatchDraw(c)}
            }
            panel.setWillNotDraw(true)
            val originalPanel=StateListDrawable().apply {addState(intArrayOf(),ColorDrawable(Color.DKGRAY))}
            val overlay=View(this).apply {background=ColorDrawable(Color.DKGRAY)}
            val originalOverlay=overlay.background
            panel.background=originalPanel
            panel.addView(overlay,FrameLayout.LayoutParams(360,300))
            panel.addView(TextView(this).apply {text="Native foreground";setTextColor(Color.WHITE);textSize=16f},
                FrameLayout.LayoutParams(180,42).apply {leftMargin=100;topMargin=35})
            panel.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY))
            panel.layout(0,0,360,300)
            source.translationY=0f;updateSource(0);live.update(source,Matrix(),area,params,1f,refreshContent=true)
            val covered=render("dialog-original-overlay",draw={panel.draw(it)})
            check(Color.red(covered.getPixel(180,150))==Color.red(Color.DKGRAY))
            check(GalleryDialogBackgroundLease.sync(panel,true) && GalleryDialogBackgroundLease.sync(overlay,true))
            panel.invalidate();val visible=render("dialog-leased-live",draw={panel.draw(it)})
            check(near(Color.red(visible.getPixel(180,150)),45)) {"Dialog overlay still covers live source"}
            updateSource(1);check(!live.update(source,Matrix(),area,params,1f,refreshContent=true))
            panel.invalidate();val changedPanel=render("dialog-leased-updated",draw={panel.draw(it)})
            check(near(Color.red(changedPanel.getPixel(180,150)),150)) {"Dialog content frozen"}
            check(Color.alpha(changedPanel.getPixel(50,150))==0) {"Dialog leaked outside glass outline"}
            panelMaterial=false
            check(GalleryDialogBackgroundLease.sync(panel,false) && GalleryDialogBackgroundLease.sync(overlay,false))
            check(panel.background===originalPanel && overlay.background===originalOverlay)
            panel.invalidate();val restored=render("dialog-restored-native",draw={panel.draw(it)})
            check(Color.red(restored.getPixel(180,150))==Color.red(Color.DKGRAY))
            for(bitmap in listOf(covered,visible,changedPanel,restored))bitmap.recycle()
            val report=JSONObject().put("version",com.coloros16.liquidglass.BuildConfig.VERSION_NAME)
                .put("status","PASS").put("liveContentChangedWithoutEffectRebuild",true)
                .put("nativeSourceMotionWithoutEffectRebuild",true).put("nonzeroHostOriginAligned",true)
                .put("hostAndSourceOriginsIndependent",true)
                .put("contentRefreshDoesNotRequestAnotherFrame",true)
                .put("outsideMaskTransparent",true).put("nativePaintRestoredAfterException",true)
                .put("clearVisibleWithoutNativeInput",true).put("clearVisibleWithTransparentNativeInput",true)
                .put("clearLiveInputChanges",true).put("clearNativePaintUntouched",true)
                .put("dialogViewDispatchPaintsBehindChildren",true).put("dialogGrayOverlayLeased",true)
                .put("dialogLeasedLiveContentUpdates",true).put("dialogNativeBackgroundsRestored",true)
                .put("oemViewSourceResolverTested",false).put("physicalDeviceTested",false)
            File(output,"report.json").writeText(report.toString(2))
            android.util.Log.i("LiveContentBackdropProbe","PASS $report");return report
        } finally {renderer.destroy();reader.close();root.discardDisplayList();live.close();source.discardDisplayList()}
    }
}
