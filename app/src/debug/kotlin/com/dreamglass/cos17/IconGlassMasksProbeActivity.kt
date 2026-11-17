package com.dreamglass.cos17

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import com.coloros16.liquidglass.hook.IconGlassMasks
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Local synthetic fixtures only. Does not read installed apps, a real wallpaper or screen. */
class IconGlassMasksProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Icon source pipeline / cache / ownership…";setPadding(20,60,20,20)}
        setContentView(label)
        Thread({
            val folder=File(filesDir,"icon-glass-masks-probe").apply {mkdirs()}
            val result=try {probe()}catch(t:Throwable) {JSONObject().put("passed",false).put("error",t.stackTraceToString())}
            result.put("physicalDeviceTested",false).put("vendorLauncherHookExecuted",false)
            File(folder,"report.json").writeText(result.toString(2))
            runOnUiThread {label.text=result.toString(2)}
        },"DreamGlass-icon-masks-probe").start()
    }

    private fun synthetic():Bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xff2050c0.toInt())
        val c=Canvas(this);val p=Paint().apply {color=Color.WHITE}
        c.drawRect(94f,48f,162f,208f,p);c.drawRect(48f,94f,208f,162f,p)
    }

    private fun hardware(source:Bitmap):Bitmap {
        val reader=ImageReader.newInstance(256,256,PixelFormat.RGBA_8888,1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Icon source ownership fixture")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node)
            node.setPosition(0,0,256,256)
            val canvas=node.beginRecording(256,256)
            canvas.drawBitmap(source,0f,0f,null);node.endRecording()
            val status=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED)==0)
            val image=reader.acquireNextImage()?:error("Missing hardware source")
            try {
                val buffer=image.hardwareBuffer?:error("Missing hardware buffer")
                try {return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))?:error("Cannot wrap hardware source")}
                finally {buffer.close()}
            } finally {image.close()}
        } finally {renderer.destroy();reader.close();node.discardDisplayList()}
    }

    private fun probe():JSONObject {
        val context=applicationContext;val user=Process.myUserHandle().hashCode()
        val callbacks=AtomicInteger()
        val callback:()->Unit={callbacks.incrementAndGet();Unit}
        fun resolve(source:Bitmap,component:ComponentName?=null)=IconGlassMasks.resolve(context,component,user,source,callback)
        fun await(source:Bitmap,component:ComponentName?=null):IconGlassMasks.Prepared {
            val deadline=SystemClock.uptimeMillis()+5000L
            while(SystemClock.uptimeMillis()<deadline) {
                resolve(source,component)?.let {return it}
                Thread.sleep(2)
            }
            error("Mask worker did not publish a result")
        }
        IconGlassMasks.clear()
        val original=synthetic();val generation=original.generationId
        val originalPixels=IntArray(256*256).also {original.getPixels(it,0,256,0,0,256,256)}
        resolve(original)
        // Repeated draws while the first worker is active must not duplicate that request.
        repeat(100) {resolve(original)}
        val first=await(original)
        check(first.kind=="flat-edge-separated" && first.separated)
        check(first.foreground.getPixel(0,0)==Color.TRANSPARENT)
        check(Color.alpha(first.foreground.getPixel(64,64))==255)
        Thread.sleep(120)
        check(callbacks.get()==1) {"Duplicate preparation callback count=${callbacks.get()}"}
        check(resolve(original)===first)
        val after=IntArray(256*256).also {original.getPixels(it,0,256,0,0,256,256)}
        check(originalPixels.contentEquals(after) && generation==original.generationId && !original.isRecycled)

        val complex=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(128*128) {i ->0xff000000.toInt() or (((i*37)%256) shl 16) or (((i*67)%256) shl 8) or ((i*91)%256)}
        complex.setPixels(pixels,0,128,0,0,128,128)
        val preserved=await(complex)
        check(preserved.kind.startsWith("subject-") && preserved.separated && preserved.relief)
        val retained=IntArray(128*128).also {preserved.foreground.getPixels(it,0,128,0,0,128,128)}
        check(!pixels.contentEquals(retained)) {"Complex artwork still retained its coloured image"}
        retained.forEach {check((it shr 16 and 255)==(it and 255)) {"Complex subject was not converted to tonal relief"}}

        // Curated semantic shape is limited to an explicit, known system camera package.
        val camera=await(original,ComponentName("com.oplus.camera","Camera"))
        check(camera.kind=="curated-camera")
        val unknown=await(original,ComponentName("com.dreamglass.nonexistent.fixture","Activity"))
        check(!unknown.kind.startsWith("curated-"))

        val sourceHardware=hardware(original)
        check(sourceHardware.config==Bitmap.Config.HARDWARE)
        val hardwareMask=await(sourceHardware)
        check(hardwareMask.separated && hardwareMask.kind=="flat-edge-separated")
        check(!sourceHardware.isRecycled && sourceHardware.config==Bitmap.Config.HARDWARE)

        IconGlassMasks.cancelPending()
        check(resolve(original)===first) {"Screen-off cancellation discarded reusable masks"}
        IconGlassMasks.clear()
        resolve(original);IconGlassMasks.clear();resolve(original)
        val fresh=await(original)
        check(fresh!==first && !first.foreground.isRecycled && !fresh.foreground.isRecycled)
        check(resolve(original)===fresh) {"An old epoch replaced the newly prepared cache entry"}

        val staticSource=android.graphics.drawable.GradientDrawable().apply {
            shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(Color.CYAN);setBounds(7,11,75,83)
        }
        val staticBounds=android.graphics.Rect(staticSource.bounds)
        val raster=com.coloros16.liquidglass.hook.IconGlassRasterSources
        raster.clear()
        val deadline=SystemClock.uptimeMillis()+5000L
        var staticBitmap:Bitmap?=null
        while(staticBitmap==null && SystemClock.uptimeMillis()<deadline) {
            staticBitmap=raster.resolve(context,staticSource,callback);Thread.sleep(2)
        }
        check(staticBitmap!=null && staticSource.bounds==staticBounds) {"Static recommendation source mutated original bounds"}
        check(raster.resolve(context,staticSource,callback)===staticBitmap)
        raster.cancelPending();check(raster.resolve(context,staticSource,callback)===staticBitmap)
        raster.clear();check(!staticBitmap.isRecycled) {"Published recommendation source recycled"}

        return JSONObject().put("passed",true).put("legacyFlatBackgroundSeparated",true)
            .put("cacheReusesPreparedIdentity",true).put("inFlightRepeatedDrawsCoalesced",true)
            .put("sourcePixelsAndGenerationUnchanged",true).put("complexArtworkBecomesTonalSubject",true)
            .put("explicitCuratedPackageOnly",true).put("hardwareSourcePreparedWithoutReadbackCrash",true)
            .put("publishedMasksNeverRecycledByClear",true).put("clearEpochDoesNotStarveSameKey",true)
            .put("screenOffCancellationPreservesCache",true)
            .put("staticRecommendationDrawableClonedWithoutMutation",true)
            .put("staticRecommendationCacheAndLifetimePreserved",true)
    }
}
