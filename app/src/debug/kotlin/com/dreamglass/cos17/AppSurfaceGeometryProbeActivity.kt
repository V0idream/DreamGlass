package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.widget.TextView
import com.coloros16.liquidglass.hook.AppGlassEffect
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.io.File
import org.json.JSONObject

/** Skia GPU coordinates of inset masks and external blur. This fixture uses an
 * owned RenderNode input, NOT the OEM backdrop bridge or a vendor application. */
class AppSurfaceGeometryProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="GPU surface geometry…"};setContentView(label)
        label.post {try {label.text=probe().toString(2)} catch(e:Throwable) {
            android.util.Log.e("AppSurfaceGeometryProbe","FAIL",e);label.text="FAIL $e"
        }}
    }
    private fun render(blur:Float,opacity:Float=1f,debug:Boolean=true):Bitmap {
        val w=260;val h=220;val area=RectF(43f,31f,217f,191f)
        val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val root=RenderNode("root");val input=RenderNode("native-input")
        val material=AppGlassEffect()
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(root)
            root.setPosition(0,0,w,h);input.setPosition(0,0,w,h)
            val c=input.beginRecording(w,h)
            val pixels=IntArray(w*h) {index -> Color.rgb(index%w,index/w,80)}
            val source=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
            c.drawBitmap(source,0f,0f,null);input.endRecording()
            val params=LiquidGlassShader.Params(area.width(),area.height(),cornerRadius=64f,blurRadius=blur,
                refractionAmount=0f,highlight=0f,vibrancy=1f,debugGlassBounds=debug)
            input.setRenderEffect(material.create(params,area,opacity))
            val rc=root.beginRecording(w,h);rc.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);rc.drawRenderNode(input);root.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage()!!
            try {val buffer=image.hardwareBuffer!!
                try {val b=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    try {return b.copy(Bitmap.Config.ARGB_8888,false)!!} finally {b.recycle()}
                } finally {buffer.close()}
            } finally {image.close();source.recycle()}
        } finally {renderer.destroy();reader.close();root.discardDisplayList();input.discardDisplayList()}
    }
    private fun cyanBounds(bitmap:Bitmap):Rect {
        val r=Rect(bitmap.width,bitmap.height,0,0)
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
            val c=bitmap.getPixel(x,y)
            if(Color.alpha(c)>210 && Color.red(c)<15 && Color.green(c)>210 && Color.blue(c)>240) {
                r.left=minOf(r.left,x);r.top=minOf(r.top,y);r.right=maxOf(r.right,x+1);r.bottom=maxOf(r.bottom,y+1)
            }
        }
        return r
    }
    private fun probe():JSONObject {
        val output=File(filesDir,"app-surface-geometry-probe").apply {mkdirs()}
        val a=render(0f);val b=render(20f);val c=render(20f,.4f,false)
        for((name,image) in listOf("blur0" to a,"blur20" to b,"fade40" to c))
            File(output,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        val first=cyanBounds(a);val second=cyanBounds(b)
        check(!first.isEmpty && first==second) {"External blur shifted mask: $first vs $second"}
        check(kotlin.math.abs(first.left-43)<=2 && kotlin.math.abs(first.top-31)<=2 &&
            kotlin.math.abs(first.right-217)<=2 && kotlin.math.abs(first.bottom-191)<=2) {"Inset geometry mismatch $first"}
        for(image in listOf(a,b,c))check(Color.alpha(image.getPixel(10,10))==0) {"Opaque rectangle outside surface"}
        val center=a.getPixel(130,111)
        check(kotlin.math.abs(Color.red(center)-130)<4 && kotlin.math.abs(Color.green(center)-111)<4) {"Source shifted relative to mask"}
        check(Color.alpha(c.getPixel(130,111)) in 99..105) {"Native fill fade not preserved"}
        val report=JSONObject().put("insetMask",first.toShortString()).put("externalBlurPreservesGeometry",true)
            .put("sourceAligned",true).put("outsideTransparent",true).put("nativeFillFade",true)
            .put("vendorHookTested",false).put("oemBridgeTested",false)
        File(output,"report.json").writeText(report.toString(2))
        a.recycle();b.recycle();c.recycle()
        android.util.Log.i("AppSurfaceGeometryProbe","PASS $report");return report
    }
}
