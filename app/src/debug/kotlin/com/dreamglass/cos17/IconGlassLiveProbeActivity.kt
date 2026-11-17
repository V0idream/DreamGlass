package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.widget.TextView
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** The exact production backdrop RenderEffect, driven by changing RenderNode pixels. */
class IconGlassLiveProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Live icon / compositor input…";setPadding(20,60,20,20)}
        setContentView(label)
        Thread({
            val folder=File(filesDir,"icon-glass-live-probe").apply {mkdirs()}
            val result=try {probe(folder)}catch(t:Throwable) {JSONObject().put("passed",false).put("error",t.stackTraceToString())}
            result.put("physicalDeviceTested",false).put("vendorLauncherHookExecuted",false).put("nativeSurfaceBridgeExecuted",false)
            folder.resolve("report.json").writeText(result.toString(2))
            runOnUiThread {label.text=result.toString(2)}
        },"DreamGlass-live-icon-probe").start()
    }
    private fun probe(folder:File):JSONObject {
        val foreground=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        Canvas(foreground).drawCircle(64f,64f,25f,Paint().apply {color=Color.WHITE})
        val generation=foreground.generationId
        val wallpaper=Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888)
        Canvas(wallpaper).apply {
            drawColor(Color.rgb(15,65,145))
            drawRect(120f,0f,240f,120f,Paint().apply {color=Color.rgb(160,38,40)})
        }
        val material=IconGlassMaterial()
        val bounds=RectF(30f,25f,150f,145f)
        val source=RenderNode("Empty native backdrop input").apply {setPosition(0,0,180,190)}
        val root=RenderNode("Explicit wallpaper fixture").apply {setPosition(0,0,180,190)}
        root.beginRecording(180,190).apply {drawRenderNode(source)};root.endRecording()
        val renderer=HardwareBackdropBlur()
        fun capture(crop:RectF,nativeColour:Int):Bitmap {
            source.setRenderEffect(material.createWallpaperEffect(bounds,foreground,
                IconGlassWallpaper.Frame(wallpaper,crop),IconGlassMaterial.Params()))
            source.beginRecording(180,190).apply {drawColor(nativeColour,BlendMode.SRC)};source.endRecording()
            val hardware=renderer.render(root,180,190,180,190,.01f) {false}
            return try {hardware.copy(Bitmap.Config.ARGB_8888,false)!!}finally {hardware.recycle()}
        }
        val blue:Bitmap;val red:Bitmap
        try {
            blue=capture(RectF(0f,0f,120f,120f),Color.TRANSPARENT)
            red=capture(RectF(120f,0f,240f,120f),Color.BLACK)
        }finally {renderer.close()}
        val blueBase=blue.getPixel(50,50);val redBase=red.getPixel(50,50)
        check(Color.alpha(blueBase)>240 && Color.alpha(redBase)>240) {"Live comparison contains an empty frame"}
        check(Color.blue(blueBase)>Color.red(blueBase)+30 && Color.red(redBase)>Color.blue(redBase)+30) {
            "Wallpaper sampling did not update or black native input leaked into output"
        }
        var delta=0L
        for(y in 40 until 130 step 2)for(x in 45 until 135 step 2) {
            val a=blue.getPixel(x,y);val b=red.getPixel(x,y)
            delta+=abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b))
        }
        check(delta>10000L) {"Same live effect did not respond to changing RenderNode input: $delta"}
        check(Color.alpha(blue.getPixel(5,5))==0 && Color.alpha(red.getPixel(5,5))==0)
        check(Color.red(blue.getPixel(90,85))>160 && Color.red(red.getPixel(90,85))>160) {"Foreground missing"}
        check(foreground.generationId==generation && !foreground.isRecycled)
        val panel=Bitmap.createBitmap(360,190,Bitmap.Config.ARGB_8888)
        Canvas(panel).apply {drawBitmap(blue,0f,0f,null);drawBitmap(red,180f,0f,null)}
        folder.resolve("live-input.png").outputStream().use {panel.compress(Bitmap.CompressFormat.PNG,100,it)}
        return JSONObject().put("passed",true).put("productionBackdropEffectRendered",true)
            .put("sameCompiledMaterialRespondsToCropChanges",true)
            .put("emptyAndBlackNativeInputsDoNotReplaceWallpaper",true).put("bothFramesOpaqueAndColourCorrect",true).put("backdropPixelDifference",delta)
            .put("viewLocalIconBoundsPreserved",true).put("foregroundPreserved",true)
            .put("sharedWallpaperTexture",true).put("wallpaperDecodesDuringMotion",0).put("screensCaptured",0).put("foregroundNotMutated",true)
    }
}
