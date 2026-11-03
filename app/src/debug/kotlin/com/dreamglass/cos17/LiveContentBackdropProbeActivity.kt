package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.widget.TextView
import com.coloros16.liquidglass.hook.LiveContentGlassNode
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
        fun render(name:String,hostX:Float=0f,hostY:Float=0f):Bitmap {
            val c=root.beginRecording(360,300);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            c.translate(hostX,hostY);live.draw(c);root.endRecording()
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
            val report=JSONObject().put("version",com.coloros16.liquidglass.BuildConfig.VERSION_NAME)
                .put("status","PASS").put("liveContentChangedWithoutEffectRebuild",true)
                .put("nativeSourceMotionWithoutEffectRebuild",true).put("nonzeroHostOriginAligned",true)
                .put("hostAndSourceOriginsIndependent",true)
                .put("contentRefreshDoesNotRequestAnotherFrame",true)
                .put("outsideMaskTransparent",true).put("nativePaintRestoredAfterException",true)
                .put("oemViewSourceResolverTested",false).put("physicalDeviceTested",false)
            File(output,"report.json").writeText(report.toString(2))
            android.util.Log.i("LiveContentBackdropProbe","PASS $report");return report
        } finally {renderer.destroy();reader.close();root.discardDisplayList();live.close();source.discardDisplayList()}
    }
}
