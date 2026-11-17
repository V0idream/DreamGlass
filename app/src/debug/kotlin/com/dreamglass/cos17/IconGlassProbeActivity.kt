package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.widget.TextView
import com.coloros16.liquidglass.hook.IconGlassMaterial
import java.io.File
import org.json.JSONObject
import kotlin.math.abs

/** Local synthetic GPU fixture; no installed app icon, wallpaper or screen is read. */
class IconGlassProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Icon glass GPU geometry / foreground / lifecycle…";setPadding(20,60,20,20)}
        setContentView(label)
        Thread({
            val folder=File(filesDir,"icon-glass-probe").apply {mkdirs()}
            val result=try {probe(folder)} catch(t:Throwable) {
                JSONObject().put("passed",false).put("error",t.stackTraceToString())
            }
            result.put("physicalDeviceTested",false).put("vendorLauncherHookExecuted",false)
            File(folder,"report.json").writeText(result.toString(2))
            runOnUiThread {label.text=result.toString(2)}
        },"DreamGlass-icon-gpu-probe").start()
    }

    private fun wallpaper(light:Boolean):Bitmap = Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader=LinearGradient(0f,0f,512f,512f,
                if(light)intArrayOf(0xfff3dec5.toInt(),0xff91c6cd.toInt(),0xfff7f3e8.toInt())
                else intArrayOf(0xff082839.toInt(),0xff315e82.toInt(),0xff63335f.toInt()),null,Shader.TileMode.CLAMP)
        }
        c.drawRect(0f,0f,512f,512f,p)
        p.shader=null;p.color=if(light)0xffc8a99d.toInt() else 0xff41a9b2.toInt()
        c.drawCircle(280f,240f,95f,p)
        p.color=if(light)0xffe9efe4.toInt() else 0xff172e57.toInt()
        c.drawRoundRect(130f,0f,170f,512f,15f,15f,p)
    }

    private fun glyph():Bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xff40d68a.toInt();style=Paint.Style.STROKE;strokeWidth=8f}
        c.drawRoundRect(29f,39f,99f,91f,10f,10f,p)
        c.drawCircle(64f,65f,15f,p)
        c.drawLine(44f,35f,53f,26f,p);c.drawLine(53f,26f,76f,26f,p);c.drawLine(76f,26f,85f,35f,p)
    }

    /** Opaque, multicolour artwork intentionally has no trustworthy foreground silhouette. */
    private fun artwork():Bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this);val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=Color.rgb(240,30,50);c.drawRect(0f,0f,64f,64f,p)
        p.color=Color.rgb(25,45,235);c.drawRect(64f,0f,128f,64f,p)
        p.color=Color.rgb(25,205,120);c.drawRect(0f,64f,64f,128f,p)
        p.color=Color.rgb(220,160,35);c.drawRect(64f,64f,128f,128f,p)
        p.color=Color.rgb(250,245,205);c.drawCircle(94f,94f,17f,p)
        p.color=Color.rgb(40,70,110);c.drawCircle(94f,94f,8f,p)
    }

    private fun render(material:IconGlassMaterial,foreground:Bitmap,wallpaper:Bitmap,
                       source:RectF,params:IconGlassMaterial.Params,
                       bounds:RectF=RectF(40f,30f,200f,190f),matrix:Matrix?=null):Bitmap {
        val reader=ImageReader.newInstance(260,230,PixelFormat.RGBA_8888,1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val engine=HardwareRenderer();val node=RenderNode("Icon material fixture")
        try {
            engine.isOpaque=false;engine.setSurface(reader.surface);engine.setContentRoot(node);node.setPosition(0,0,260,230)
            val canvas=node.beginRecording(260,230)
            canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
            if(matrix!=null)canvas.concat(matrix)
            check(material.draw(canvas,bounds,foreground,wallpaper,source,params)) {"GPU material rejected valid fixture"}
            node.endRecording()
            val status=engine.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED)==0)
            val image=reader.acquireNextImage() ?: error("GPU output unavailable")
            try {
                val buffer=image.hardwareBuffer!!
                try {
                    val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    try {return hardware.copy(Bitmap.Config.ARGB_8888,false)!!} finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        } finally {engine.destroy();reader.close();node.discardDisplayList()}
    }

    private fun difference(a:Bitmap,b:Bitmap):Long {
        var score=0L
        for(y in 35 until 185 step 2)for(x in 45 until 195 step 2) {
            val ca=a.getPixel(x,y);val cb=b.getPixel(x,y)
            score+=abs(Color.red(ca)-Color.red(cb))+abs(Color.green(ca)-Color.green(cb))+abs(Color.blue(ca)-Color.blue(cb))
        }
        return score
    }

    private fun subjectPreview(folder:File,wallpaper:Bitmap):Boolean {
        val originals=(0..3).map {kind ->Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
            val c=Canvas(this);val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader=LinearGradient(0f,0f,128f,128f,
                intArrayOf(0xff2851c0.toInt(),0xff9c387c.toInt(),0xff24bda0.toInt()),null,Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,128f,128f,p);p.shader=null
            when(kind) {
                0 -> {
                    p.color=Color.WHITE;c.drawOval(22f,24f,90f,82f,p)
                    p.color=0xff71e460.toInt();c.drawOval(52f,54f,111f,103f,p)
                    p.color=0xff263c41.toInt();for(x in listOf(43f,68f))c.drawCircle(x,48f,5f,p)
                    for(x in listOf(71f,93f))c.drawCircle(x,74f,4f,p)
                }
                1 -> {
                    c.drawColor(Color.WHITE);p.color=0xffe13950.toInt()
                    c.drawRoundRect(7f,10f,30f,34f,3f,3f,p)
                    p.color=Color.WHITE;c.drawRect(12f,15f,17f,29f,p);c.drawRect(20f,15f,25f,29f,p)
                }
                2 -> {
                    p.color=0xff182c38.toInt();c.drawRoundRect(21f,31f,108f,97f,12f,12f,p)
                    p.color=0xffb9cbd0.toInt();c.drawRect(40f,22f,75f,35f,p)
                    p.color=0xffa2a8be.toInt();c.drawCircle(65f,65f,27f,p)
                    p.color=0xff24405a.toInt();c.drawCircle(65f,65f,20f,p)
                    p.color=0xff7ecddd.toInt();c.drawCircle(59f,57f,9f,p)
                    p.color=0xffffd360.toInt();c.drawRect(94f,40f,101f,47f,p)
                }
                else -> {
                    p.color=0xff242048.toInt();c.drawOval(24f,12f,106f,115f,p)
                    p.color=0xffedcbaa.toInt();c.drawOval(42f,33f,93f,99f,p)
                    p.color=0xffbaa0d6.toInt();c.drawOval(25f,15f,102f,53f,p)
                    p.color=0xff253054.toInt();c.drawOval(48f,57f,59f,66f,p);c.drawOval(76f,57f,87f,66f,p)
                    p.color=0xffb84c68.toInt();c.drawRoundRect(60f,80f,77f,85f,3f,3f,p)
                }
            }
        }}
        val panel=Bitmap.createBitmap(780,700,Bitmap.Config.ARGB_8888)
        val c=Canvas(panel);c.drawColor(0xff11212b.toInt())
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=16f}
        val ready:()->Unit={}
        for((index,original) in originals.withIndex()) {
            val deadline=android.os.SystemClock.uptimeMillis()+5000L
            var prepared:com.coloros16.liquidglass.hook.IconGlassMasks.Prepared?=null
            while(prepared==null && android.os.SystemClock.uptimeMillis()<deadline) {
                prepared=com.coloros16.liquidglass.hook.IconGlassMasks.resolve(applicationContext,null,
                    android.os.Process.myUserHandle().hashCode(),original,ready)
                if(prepared==null)Thread.sleep(2)
            }
            val mask=checkNotNull(prepared) {"Synthetic subject preparation timed out"}
            val output=com.coloros16.liquidglass.hook.IconGlassAssetRenderer.render(mask.foreground,wallpaper,
                RectF(70f,90f,330f,350f),IconGlassMaterial.Params(reliefArtwork=mask.relief),128,128)
            val x=36f+index*192f
            c.drawBitmap(original,x,36f,null);c.drawText("Original",x,187f,label)
            c.drawBitmap(mask.foreground,x,251f,null);c.drawText(mask.kind.take(19),x-8f,405f,label)
            c.drawBitmap(output,x,477f,null);c.drawText("Cached glass icon",x-7f,633f,label)
        }
        File(folder,"subjects.png").outputStream().use {panel.compress(Bitmap.CompressFormat.PNG,100,it)}
        return true
    }

    private fun probe(folder:File):JSONObject {
        val material=IconGlassMaterial();val mark=glyph()
        val dark=com.coloros16.liquidglass.hook.IconGlassBackdrop.prepare(wallpaper(false))
        val light=com.coloros16.liquidglass.hook.IconGlassBackdrop.prepare(wallpaper(true))
        val source=RectF(70f,90f,330f,350f);val defaults=IconGlassMaterial.Params()
        val generation=mark.generationId;val wallpaperGeneration=dark.generationId
        val normal=render(material,mark,dark,source,defaults)
        check(Color.alpha(normal.getPixel(20,100))==0 && Color.alpha(normal.getPixel(42,32))==0)
        check(Color.alpha(normal.getPixel(120,110))>=253)
        val blank=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val baseOnly=render(material,blank,dark,source,defaults)
        val foregroundScore=difference(normal,baseOnly);check(foregroundScore>12000L) {"Foreground glass missing: $foregroundScore"}
        val shifted=render(material,mark,dark,RectF(160f,90f,420f,350f),defaults)
        val shiftScore=difference(normal,shifted);check(shiftScore>18000L) {"Wallpaper source mapping stale"}
        val half=render(material,mark,dark,source,defaults.copy(alpha=128))
        val halfPixel=half.getPixel(120,110);val fullPixel=normal.getPixel(120,110)
        check(Color.alpha(halfPixel) in 126..130) {"Native alpha applied twice"}
        check(abs(Color.red(fullPixel)-Color.red(halfPixel))<=4 && abs(Color.blue(fullPixel)-Color.blue(halfPixel))<=4)
        val hidden=render(material,mark,dark,source,defaults.copy(alpha=0))
        check(Color.alpha(hidden.getPixel(120,110))==0)
        val bright=render(material,mark,light,source,defaults)
        check(difference(normal,bright)>250000L) {"Light wallpaper not rebound"}
        val lightBase=render(material,blank,light,source,defaults)
        fun luma(c:Int)=.2126*Color.red(c)+.7152*Color.green(c)+.0722*Color.blue(c)
        val contrast=luma(bright.getPixel(78,111))-luma(lightBase.getPixel(78,111))
        check(contrast>35) {"White relief glyph lacks contrast on light backdrop: $contrast"}
        val muted=render(material,mark,dark,source,defaults.copy(disabled=true))
        check(difference(normal,muted)>20000L)
        val clear=render(material,mark,dark,source,defaults.copy(clarity=.12f,refraction=.95f,thickness=.8f,keepColor=false))
        check(difference(normal,clear)>14000L) {"Material controls have no effect"}
        val moved=render(material,mark,dark,source,defaults,RectF(80f,50f,240f,210f))
        check(Color.alpha(moved.getPixel(45,110))==0 && Color.alpha(moved.getPixel(160,130))>=253)
        val depth=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
            val c=Canvas(this);val p=Paint()
            p.color=Color.rgb(45,45,45);c.drawRect(22f,22f,64f,106f,p)
            p.color=Color.rgb(235,235,235);c.drawRect(64f,22f,106f,106f,p)
            p.color=Color.rgb(120,120,120);c.drawCircle(86f,87f,14f,p)
        }
        val preserved=render(material,depth,dark,source,defaults.copy(reliefArtwork=true))
        val flattened=render(material,depth,dark,source,defaults.copy(reliefArtwork=false))
        val depthDifference=abs(luma(preserved.getPixel(95,100))-luma(preserved.getPixel(153,100)))
        check(depthDifference>35) {"Colour-to-relief depth levels disappeared: $depthDifference"}
        val detailDifference=abs(luma(preserved.getPixel(147,139))-luma(preserved.getPixel(169,139)))
        check(detailDifference>20) {"Relief internal lens/detail was discarded: $detailDifference"}
        check(difference(preserved,flattened)>15000L) {"Complex subject became an undifferentiated white shape"}
        check(abs(Color.red(preserved.getPixel(55,110))-Color.red(baseOnly.getPixel(55,110)))<3) {
            "Complex artwork covered the entire glass substrate"
        }
        val checker=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply {
            val pixels=IntArray(256*256) {i ->if(((i%256)/3+(i/256)/3)%2==0)Color.BLACK else Color.WHITE}
            setPixels(pixels,0,256,0,0,256,256)
        }
        val frosted=com.coloros16.liquidglass.hook.IconGlassBackdrop.prepare(checker)
        val readable=frosted.copy(Bitmap.Config.ARGB_8888,false)!!
        var high=0;var low=255
        for(y in 32..220 step 2)for(x in 32..220 step 2) {
            val value=Color.red(readable.getPixel(x,y));high=maxOf(high,value);low=minOf(low,value)
        }
        check(high-low<20) {"Shared GPU backdrop retains busy high-frequency detail: ${high-low}"}
        val originalMatrix=Matrix().apply {setScale(.75f,.75f);postTranslate(20f,10f)}
        val transformed=render(material,mark,dark,source,defaults.copy(alpha=128),matrix=originalMatrix)
        check(Color.alpha(transformed.getPixel(110,93)) in 126..130 && Color.alpha(transformed.getPixel(180,100))==0)
        val transformedCenter=transformed.getPixel(110,93)
        check(abs(Color.red(transformedCenter)-Color.red(fullPixel))<=8 &&
            abs(Color.blue(transformedCenter)-Color.blue(fullPixel))<=8) {"Native transform changed local wallpaper coordinates"}
        val software=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        check(!material.draw(Canvas(software),RectF(0f,0f,32f,32f),mark,dark,source,defaults))
        check(software.getPixel(16,16)==Color.TRANSPARENT)
        check(!mark.isRecycled && !dark.isRecycled && generation==mark.generationId && wallpaperGeneration==dark.generationId)
        val generated=com.coloros16.liquidglass.hook.IconGlassAssetRenderer.render(mark,dark,source,defaults,160,160)
        check(generated.config==Bitmap.Config.ARGB_8888)
        val nativeBitmap=Bitmap.createBitmap(260,230,Bitmap.Config.ARGB_8888)
        Canvas(nativeBitmap).drawBitmap(generated,null,Rect(40,30,200,190),Paint(Paint.FILTER_BITMAP_FLAG))
        check(difference(normal,nativeBitmap)<4000L) {"Cached native icon differs from glass material"}
        val nativeHalf=Bitmap.createBitmap(260,230,Bitmap.Config.ARGB_8888)
        Canvas(nativeHalf).drawBitmap(generated,null,Rect(40,30,200,190),Paint().apply {alpha=128})
        check(Color.alpha(nativeHalf.getPixel(120,110)) in 126..130) {"Cached animation alpha applied twice"}
        material.clear()
        val afterClear=render(material,mark,dark,source,defaults)
        check(difference(normal,afterClear)<1000L)
        val panel=Bitmap.createBitmap(780,810,Bitmap.Config.ARGB_8888)
        val c=Canvas(panel);c.drawColor(0xff11212b.toInt())
        val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=17f}
        listOf("Dark / foreground" to normal,"Light" to bright,"Shifted wallpaper" to shifted,
            "Alpha 128" to half,"Low clarity / strong bevel" to clear,"Disabled state" to muted,
            "Complex / tonal relief" to preserved,"Flat mask comparison" to flattened,
            "Native scale / translate / alpha" to transformed).forEachIndexed {i,item ->
            val x=(i%3)*260f;val y=(i/3)*270f;c.drawBitmap(item.second,x,y,null);c.drawText(item.first,x+15f,y+247f,text)
        }
        File(folder,"comparison.png").outputStream().use {panel.compress(Bitmap.CompressFormat.PNG,100,it)}
        val subjectsRendered=subjectPreview(folder,dark)
        return JSONObject().put("passed",true).put("productionAgslRendered",true)
            .put("offsetBoundsPreserved",true).put("transparentCorners",true).put("foregroundGlassVisible",true)
            .put("foregroundDifference",foregroundScore).put("wallpaperShiftDifference",shiftScore)
            .put("sourceCoordinatesUpdate",true).put("nativeAlphaAppliedOnce",true).put("zeroAlphaTransparent",true)
            .put("lightWallpaperUpdates",true).put("materialControlsChangeOutput",true).put("disabledStateChangesOutput",true)
            .put("differentColoursHaveDifferentReliefDepths",true).put("subjectInternalDetailPreserved",true)
            .put("complexSubjectLeavesGlassMargin",true).put("reliefLumaDifference",depthDifference).put("lightGlyphLumaContrast",contrast)
            .put("generatedNativeBitmapMatchesMaterial",true).put("cachedNativeAlphaAppliedOnce",true)
            .put("productionSubjectPipelineVisualized",subjectsRendered)
            .put("sharedGpuBlurSuppressesBusyBackdrop",true).put("blurredCheckerLumaRange",high-low)
            .put("nativeCanvasTransformAndSamplingPreserved",true)
            .put("softwareFallbackUntouched",true).put("borrowedBitmapsUnchanged",true).put("clearAndRecreate",true)
    }
}
