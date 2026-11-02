package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.widget.TextView
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.io.File
import org.json.JSONObject

/** GPU readback: actual-material border appears, centre stays unchanged, switching
 * off restores the exact original pixels. No wallpaper or user content is used.
 */
class GlassBoundsProbeActivity:Activity() {
    private val output by lazy {File(filesDir,"glass-bounds-probe").apply {mkdirs()}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Glass region debug GPU test…";setPadding(24,60,24,0)}
        setContentView(label)
        label.post {try {label.text=probe().toString(2)} catch(e:Throwable) {label.text="FAIL $e";android.util.Log.e("GlassBoundsProbe","FAIL",e)}}
    }
    private fun render(shader:RuntimeShader,p:LiquidGlassShader.Params,source:Bitmap):Bitmap {
        val w=p.viewportWidth.toInt();val h=p.viewportHeight.toInt()
        val texture=LiquidGlassShader.createSourceBitmapShader(source)
        LiquidGlassShader.setUniforms(shader,p,texture,texture,sourceRect=RectF(0f,0f,w.toFloat(),h.toFloat()))
        val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Actual glass bounds test")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,w,h)
            val canvas=node.beginRecording(w,h);canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),Paint().apply {this.shader=shader});node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage()!!
            try {val buffer=image.hardwareBuffer!!
                try {val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    try {return hardware.copy(Bitmap.Config.ARGB_8888,false)!!} finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        } finally {renderer.destroy();reader.close();node.discardDisplayList()}
    }
    private fun save(b:Bitmap,name:String) {File(output,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun probe():JSONObject {
        val source=Bitmap.createBitmap(400,400,Bitmap.Config.RGBA_F16)
        val canvas=Canvas(source);val paint=Paint()
        paint.shader=LinearGradient(0f,0f,400f,400f,Color.rgb(132,55,88),Color.rgb(30,86,145),Shader.TileMode.CLAMP);canvas.drawPaint(paint)
        var variants=0;var cyan=0
        for(metaball in listOf(false,true))for((i,size) in listOf(300f to 160f,180f to 180f,120f to 300f).withIndex()) {
            val shader=LiquidGlassShader.create(metaball)
            val p=LiquidGlassShader.Params(size.first,size.second,cornerRadius=minOf(size.first,size.second)/2f,debugGlassBounds=false)
            val off=render(shader,p,source);val on=render(shader,p.copy(debugGlassBounds=true),source);val again=render(shader,p,source)
            var changed=0;var found=0
            for(y in 0 until off.height)for(x in 0 until off.width) {
                check(off.getPixel(x,y)==again.getPixel(x,y)) {"Off state failed to restore"}
                val c=on.getPixel(x,y)
                if(c!=off.getPixel(x,y))changed++
                if(Color.alpha(c)>220 && Color.red(c)<10 && Color.green(c)>210 && Color.blue(c)>245)found++
            }
            check(changed>100 && found>100)
            check(off.getPixel(off.width/2,off.height/2)==on.getPixel(on.width/2,on.height/2))
            check(Color.alpha(on.getPixel(0,0))<3) {"Debug drew outside rounded mask"}
            if(!metaball && i==0){save(off,"normal");save(on,"debug-bounds");save(again,"restored")}
            off.recycle();on.recycle();again.recycle();variants++;cyan+=found
        }
        source.recycle()
        val result=JSONObject().put("variants",variants).put("baseAndMetaballCompiled",true)
            .put("capsuleCircleSliderCovered",true).put("cyanPixels",cyan).put("centreUnchanged",true)
            .put("offRestoresExactPixels",true).put("outsideMaskTransparent",true)
            .put("physicalDeviceTested",false).put("vendorHookTested",false)
        File(output,"report.json").writeText(result.toString(2));android.util.Log.i("GlassBoundsProbe","PASS $result")
        return result
    }
}
