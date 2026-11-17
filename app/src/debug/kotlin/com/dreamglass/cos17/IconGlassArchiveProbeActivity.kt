package com.dreamglass.cos17

import android.app.Activity
import android.content.ComponentName
import android.graphics.*
import android.os.Bundle
import android.widget.TextView
import com.coloros16.liquidglass.hook.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reads user-supplied archive resources; never installs or executes their application code. */
class IconGlassArchiveProbeActivity:Activity() {
    /** A retained OEM icon cache that ignores setIcon: reproduces the old binding gap. */
    class CachedHost(context:android.content.Context,source:android.graphics.drawable.Drawable):android.view.View(context) {
        @JvmField var mIcon=source
        var cached=source
        fun getIcon()=mIcon
        fun getIconVisible()=true
        fun setIcon(ignored:android.graphics.drawable.Drawable) { /* simulated OEM veto */ }
        fun applyCompoundDrawables(drawable:android.graphics.drawable.Drawable) {cached=drawable}
        override fun onDraw(canvas:Canvas) {cached.setBounds(0,0,128,128);cached.draw(canvas)}
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Supplied APK icon resources / production masks…";setPadding(20,60,20,20)}
        setContentView(label)
        LauncherIconGlassAssets.configure(IconGlassNativeCacheProbeActivity.Fast::class.java,
            IconGlassNativeCacheProbeActivity.Info::class.java,IconGlassNativeCacheProbeActivity.Item::class.java)
        LauncherIconGlassAssets.setActive(true)
        Thread({
            val folder=File(filesDir,"icon-glass-archive-probe").apply {mkdirs()}
            val result=try {probe(folder)}catch(t:Throwable) {JSONObject().put("passed",false).put("error",t.stackTraceToString())}
            result.put("physicalDeviceTested",false).put("vendorLauncherHookExecuted",false)
                .put("sampleApplicationsInstalledOrExecuted",false)
            folder.resolve("report.json").writeText(result.toString(2))
            runOnUiThread {label.text=result.toString(2)}
        },"DreamGlass-archive-icon-probe").start()
    }
    @Suppress("DEPRECATION")
    private fun probe(folder:File):JSONObject {
        val panel=Bitmap.createBitmap(384,256,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(panel);val examples=JSONArray()
        val wall=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff4b638f.toInt())}
        for((row,file) in listOf("floating.apk","mpdl.apk").withIndex()) {
            val archive=File(filesDir,"icon-examples/$file")
            val info=checkNotNull(packageManager.getPackageArchiveInfo(archive.path,0)) {"Cannot read $file"}
            val app=checkNotNull(info.applicationInfo).apply {sourceDir=archive.path;publicSourceDir=archive.path}
            val drawable=packageManager.getApplicationIcon(app)
            val source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,128,128);drawable.draw(Canvas(source))
            val generation=source.generationId
            val component=ComponentName(info.packageName,"Main")
            val params=IconGlassMaterial.Params()
            val end=System.currentTimeMillis()+15000L
            var mask:IconGlassMasks.Prepared?=null;var rendered:Bitmap?=null
            while(rendered==null && System.currentTimeMillis()<end) {
                mask=IconGlassMasks.resolve(this,component,android.os.Process.myUserHandle().hashCode(),source,{})
                if(mask!=null)rendered=LauncherIconGlassAssets.request(source,mask.foreground,wall,
                    RectF(0f,0f,128f,128f),params.copy(reliefArtwork=mask.relief),{})
                if(rendered==null)Thread.sleep(20)
            }
            val ready=checkNotNull(rendered) {"Missing generated icon for ${info.packageName}"}
            val prepared=checkNotNull(mask)
            var visible=0;var changed=0
            for(y in 0 until 128)for(x in 0 until 128) {
                if(Color.alpha(prepared.foreground.getPixel(x,y))>40)visible++
                if(source.getPixel(x,y)!=ready.getPixel(x,y))changed++
            }
            check(visible in 150..15000 && changed>4000) {"Icon not converted: ${info.packageName}, $visible/$changed"}
            check(LauncherIconGlassAssets.isGenerated(ready) && source.generationId==generation)
            val done=java.util.concurrent.CountDownLatch(1);var failure:Throwable?=null
            var nativeOutput:Bitmap?=null
            runOnUiThread {
                try {
                    val original=IconGlassNativeCacheProbeActivity.Fast(source)
                    val host=CachedHost(this,original).apply {layout(0,0,128,128)}
                    LauncherIconGlassAssets.apply(host,original,null,source,ready)
                    check(host.mIcon!==original && host.cached===host.mIcon) {"Retained native icon node was not rebound"}
                    nativeOutput=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).also {host.draw(Canvas(it))}
                    check(nativeOutput!!.getPixel(64,64)==ready.getPixel(64,64)) {"Native cached consumer kept original icon"}
                    // A recycled native host must restore the newest application icon.
                    val recycled=CachedHost(this,original).apply {layout(0,0,128,128)}
                    LauncherIconGlassAssets.apply(recycled,original,null,source,ready)
                    val external=IconGlassNativeCacheProbeActivity.Fast(source)
                    recycled.mIcon=external;recycled.cached=external
                    LauncherIconGlassAssets.apply(recycled,external,null,source,ready)
                    LauncherIconGlassAssets.setActive(false)
                    check(recycled.mIcon===external && recycled.cached===external) {"Recycled host restored another application's icon"}
                    check(host.mIcon===original && host.cached===original) {"Vetoing host did not restore exact original"}
                    LauncherIconGlassAssets.setActive(true)
                }catch(t:Throwable) {failure=t}finally {done.countDown()}
            }
            check(done.await(10,java.util.concurrent.TimeUnit.SECONDS));failure?.let {throw it}
            canvas.drawBitmap(source,0f,row*128f,null)
            canvas.drawBitmap(prepared.foreground,128f,row*128f,null)
            canvas.drawBitmap(checkNotNull(nativeOutput),256f,row*128f,null)
            examples.put(JSONObject().put("package",info.packageName).put("sourceType",drawable.javaClass.name)
                .put("maskKind",prepared.kind).put("foregroundPixels",visible).put("convertedPixels",changed)
                .put("sourcePreserved",true).put("generatedFallbackAvailable",true)
                .put("setIconVetoBypassedAndRetainedNodeRebound",true).put("nativeDrawingWithoutHook",true)
                .put("exactOriginalRestored",true).put("recycledHostKeepsNewestOriginal",true))
        }
        folder.resolve("archive-icons.png").outputStream().use {panel.compress(Bitmap.CompressFormat.PNG,100,it)}
        return JSONObject().put("passed",true).put("examples",examples)
    }
}
