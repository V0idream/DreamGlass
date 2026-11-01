package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.util.Log
import android.widget.TextView
import com.coloros16.liquidglass.glass.SharedGlassParams
import com.coloros16.liquidglass.glass.IosGlassPreset
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.io.File
import org.json.JSONObject
import kotlin.math.abs

/** Debug-only pixel comparison on the GPU, with the same geometry/source/parameters.
 * Reference assets are historical DreamGlass shaders; no device wallpaper is read.
 */
class ClassicOpticsProbeActivity:Activity() {
    private val output by lazy { File(filesDir,"classic-optics-probe").apply { mkdirs() } }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply { text="Classic optics GPU regression…";textSize=19f;setPadding(24,60,24,0) }
        setContentView(label)
        label.post { try { label.text=runProbe().toString(2) } catch(t:Throwable) { Log.e("ClassicOpticsProbe","FAIL",t);label.text="FAIL $t" } }
    }
    private fun render(shader:RuntimeShader,p:LiquidGlassShader.Params,source:Bitmap,dim:Float=0f):Bitmap {
        val w=p.viewportWidth.toInt();val h=p.viewportHeight.toInt()
        val texture=LiquidGlassShader.createSourceBitmapShader(source)
        LiquidGlassShader.setUniforms(shader,p,texture,texture,sourceRect=RectF(100f,100f,100f+w,100f+h),maskAlpha=dim)
        val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Classic optics regression")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,w,h)
            val c=node.beginRecording(w,h);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            c.drawRect(0f,0f,w.toFloat(),h.toFloat(),Paint().apply { this.shader=shader });node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage()!!
            try { val buffer=image.hardwareBuffer!!
                try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888,false)!! }
                finally { buffer.close() }
            } finally { image.close() }
        } finally { renderer.destroy();reader.close();node.discardDisplayList() }
    }
    private fun save(b:Bitmap,name:String) { File(output,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
    private fun runProbe():JSONObject {
        val source=Bitmap.createBitmap(768,768,Bitmap.Config.RGBA_F16)
        val c=Canvas(source);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader=LinearGradient(0f,0f,768f,768f,intArrayOf(0xff234d7b.toInt(),0xffe6b9b0.toInt(),0xff258d93.toInt()),null,Shader.TileMode.CLAMP)
        c.drawPaint(paint);paint.shader=null;paint.strokeWidth=2f;paint.textSize=27f
        for(i in 0..768 step 32) { paint.color=Color.argb(180,245,245,245);c.drawLine(i.toFloat(),0f,i.toFloat(),768f,paint);c.drawLine(0f,i.toFloat(),768f,i.toFloat(),paint) }
        for(y in 125..700 step 100) { paint.color=0xff17263c.toInt();c.drawText("DreamGlass 01234567",75f,y.toFloat(),paint) }
        val old=RuntimeShader(assets.open("probes/baseline-1.0.7.agsl").bufferedReader().use { it.readText() })
        val modern=RuntimeShader(assets.open("probes/baseline-1.1.4.agsl").bufferedReader().use { it.readText() })
        var comparisons=0;var maxDelta=0;var modernMaxDelta=0;var modernChanged=0;var nonempty=0
        for(profile in listOf("default",IosGlassPreset.ID))for(metaball in listOf(false,true))
            for((index,size) in listOf(Triple(160f,160f,80f),Triple(360f,160f,80f),Triple(100f,360f,50f),Triple(440f,240f,60f),Triple(400f,160f,48f),Triple(180f,180f,40f)).withIndex()) {
                val p=SharedGlassParams.resolve(emptyMap(),profile,null,size.first,size.second,size.third,true)
                val reference=render(old,p,source);val restored=render(LiquidGlassShader.create(metaball),p,source)
                val newer=render(modern,p,source)
                val selectedModern=render(LiquidGlassShader.create(metaball),p.copy(compactOptics=true),source)
                var delta=0;var modernDelta=0;var changed=0;var coverage=0
                for(y in 0 until reference.height)for(x in 0 until reference.width) {
                    val a=reference.getPixel(x,y);val b=restored.getPixel(x,y);val d=newer.getPixel(x,y);val e=selectedModern.getPixel(x,y)
                    for(shift in intArrayOf(0,8,16,24)) {
                        delta=maxOf(delta,abs(((a ushr shift) and 255)-((b ushr shift) and 255)))
                        modernDelta=maxOf(modernDelta,abs(((d ushr shift) and 255)-((e ushr shift) and 255)))
                    }
                    if(Color.alpha(a)>220) { coverage++;if(abs(Color.red(a)-Color.red(d))+abs(Color.green(a)-Color.green(d))+abs(Color.blue(a)-Color.blue(d))>15)changed++ }
                }
                check(delta<=1 && modernDelta<=1 && coverage>500) { "Classic mismatch profile=$profile shape=$index metaball=$metaball delta=$delta coverage=$coverage" }
                maxDelta=maxOf(maxDelta,delta);modernMaxDelta=maxOf(modernMaxDelta,modernDelta);modernChanged+=changed;nonempty+=coverage;comparisons++
                if(profile=="default" && !metaball && index==3) {
                    save(reference,"v107-reference");save(restored,"restored-default");save(newer,"v114-reference")
                }
                reference.recycle();restored.recycle();newer.recycle();selectedModern.recycle()
            }
        check(modernChanged>10000) { "Negative control did not distinguish the newer compact lens" }
        val white=Bitmap.createBitmap(768,768,Bitmap.Config.RGBA_F16).apply { eraseColor(Color.WHITE) }
        val clean=LiquidGlassShader.Params(160f,160f,cornerRadius=80f,blurRadius=0f,refractionAmount=0f,
            highlight=0f,depth=0f,dispersion=0f,vibrancy=1f)
        val shader=LiquidGlassShader.create(false)
        val plain=render(shader,clean,white)
        val dimPlain=render(shader,clean,white,.65f)
        val dimPolished=render(shader,clean.copy(surfacePolish=true),white,.65f)
        check(abs(Color.red(dimPlain.getPixel(80,80))-Color.red(dimPolished.getPixel(80,80)))<=1)
        check(Color.red(dimPolished.getPixel(80,80)) < Color.red(plain.getPixel(80,80))-80)
        val dark=render(shader,clean.copy(grayBaseLuma=.1f),white)
        val light=render(shader,clean.copy(grayBaseLuma=.9f),white)
        check(Color.red(light.getPixel(80,80))-Color.red(dark.getPixel(80,80))>80)
        check(Color.alpha(dark.getPixel(0,0))<3)
        save(dark,"gray-dark");save(light,"gray-light");save(dimPolished,"dim-polished")
        for(b in listOf(plain,dimPlain,dimPolished,dark,light))b.recycle()
        white.recycle()
        source.recycle()
        val report=JSONObject().put("readabilityDimConsistentAcrossPolish",true).put("grayShadeEndpointsDistinct",true).put("grayCornerAlphaRetained",true).put("profiles",2).put("shapeAndVariantComparisons",comparisons)
            .put("v114ReferenceMaxChannelDelta",modernMaxDelta).put("selectableProfilesCompared",comparisons*2)
            .put("v107ReferenceMaxChannelDelta",maxDelta).put("newerOpticsDifferentPixels",modernChanged)
            .put("nonemptyReferencePixels",nonempty).put("inputConfig","RGBA_F16")
            .put("baseAndMetaballVariantsCompiled",true).put("physicalDeviceTested",false)
        File(output,"summary.json").writeText(report.toString(2));Log.i("ClassicOpticsProbe","PASS $report")
        return report
    }
}
