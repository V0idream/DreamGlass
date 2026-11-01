package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.coloros16.liquidglass.hook.MediaSurfaceGeometry
import com.coloros16.liquidglass.glass.SharedGlassParams
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import org.json.JSONObject
import java.io.File

/** Synthetic media draw/clip fixture. It does not move or emulate OEM media controls. */
class MediaGeometryProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply { text="Media rim GPU regression…";textSize=18f }
        setContentView(label)
        label.post { try { label.text=runProbe().toString(2) } catch(t:Throwable) {
            Log.e("MediaGeometryProbe","FAIL",t);label.text="FAIL $t"
        } }
    }
    private fun render(source:Bitmap,left:Int,top:Int,clip:Rect?,modern:Boolean):Bitmap {
        val g=MediaSurfaceGeometry.resolve(left,top,left+352,top+330)!!
        val p=SharedGlassParams.resolve(emptyMap(),"default",null,g.width,g.height,62f,false,modern)
        val shader=LiquidGlassShader.create(false)
        val texture=LiquidGlassShader.createSourceBitmapShader(source)
        LiquidGlassShader.setUniforms(shader,p,texture,texture,
            sourceRect=RectF(100+g.sourceLeft,100+g.sourceTop,100+g.sourceLeft+g.width,100+g.sourceTop+g.height))
        val reader=ImageReader.newInstance(440,430,PixelFormat.RGBA_8888,1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Media native-boundary fixture")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node)
            node.setPosition(0,0,440,430)
            val c=node.beginRecording(440,430);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            c.translate(44f+left,50f+top)
            if(clip!=null)c.clipRect(clip)
            c.drawRect(0f,0f,g.width,g.height,Paint().apply { this.shader=shader })
            node.endRecording();renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage()!!
            try { val buffer=image.hardwareBuffer!!
                try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888,false)!! }
                finally { buffer.close() }
            } finally { image.close() }
        } finally { renderer.destroy();reader.close();node.discardDisplayList() }
    }
    private fun runProbe():JSONObject {
        val source=Bitmap.createBitmap(640,640,Bitmap.Config.RGBA_F16)
        Canvas(source).drawPaint(Paint().apply { shader=LinearGradient(0f,0f,640f,640f,
            intArrayOf(Color.BLUE,Color.CYAN,Color.MAGENTA),null,Shader.TileMode.CLAMP) })
        var comparisons=0;var maxDelta=0;var covered=0
        for(modern in listOf(false,true))for(shift in listOf(0,-24,19)) {
            val full=render(source,shift,0,null,modern)
            val clip=Rect(12,0,333,312)
            val cut=render(source,shift,0,clip,modern)
            for(y in 0 until full.height)for(x in 0 until full.width) {
                val inClip=x in (44+shift+clip.left) until (44+shift+clip.right) && y in 50 until (50+clip.bottom)
                val expected=if(inClip)full.getPixel(x,y) else Color.TRANSPARENT
                val actual=cut.getPixel(x,y)
                // Hardware clips can leave 1/255 edge coverage. Compare premultiplied
                // output, so an almost transparent cyan pixel is not treated as opaque cyan.
                maxDelta=maxOf(maxDelta,kotlin.math.abs(Color.alpha(actual)-Color.alpha(expected)))
                for(bit in intArrayOf(0,8,16))maxDelta=maxOf(maxDelta,kotlin.math.abs(
                    ((actual ushr bit) and 255)*Color.alpha(actual)/255-
                    ((expected ushr bit) and 255)*Color.alpha(expected)/255))
                if(inClip && Color.alpha(actual)>220)covered++
            }
            if(!modern && shift==0) {
                val dir=File(filesDir,"media-geometry-probe").apply { mkdirs() }
                File(dir,"native-bounds.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG,100,it) }
                File(dir,"partial-clip.png").outputStream().use { cut.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            full.recycle();cut.recycle();comparisons++
        }
        source.recycle();check(maxDelta<=2 && covered>100000) { "delta=$maxDelta covered=$covered" }
        val result=JSONObject().put("comparisons",comparisons).put("clipVsUnclippedMaxPremultipliedChannelDelta",maxDelta)
            .put("nonemptyPixels",covered).put("physicalDeviceTested",false)
        Log.i("MediaGeometryProbe","PASS $result");return result
    }
}
