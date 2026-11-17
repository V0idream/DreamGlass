package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject
import java.io.File

/** Exercises production View-entry preparation. The native fixture deliberately has no
 * drawInternal method and no Xposed hook; its draw reads mBitmap directly. */
class IconGlassViewBindingProbeActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private lateinit var label:TextView
    private val reports=JSONObject()
    private var finished=false
    private var mode=0
    private val modes=listOf("prefab","live")
    private val source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xff147e46.toInt())
        Canvas(this).drawCircle(64f,64f,36f,Paint().apply {color=Color.WHITE})
    }
    private var sourceGeneration=0
    private val wall=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff355d95.toInt())}
    private lateinit var host:View
    private lateinit var item:IconGlassNativeCacheProbeActivity.Item
    private lateinit var native:IconGlassNativeCacheProbeActivity.Fast
    private lateinit var originalInfo:IconGlassNativeCacheProbeActivity.Info
    private val image=Bitmap.createBitmap(384,128,Bitmap.Config.ARGB_8888)
    private val ready:()->Unit={ui.post {attempt()};Unit}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        label=TextView(this).apply {text="View-entry native bitmap binding…";setPadding(20,60,20,20)}
        setContentView(label)
        sourceGeneration=source.generationId
        // Regression for activation occurring before the native cache ABI is configured.
        LauncherIconGlassAssets.setActive(true)
        LauncherIconGlassAssets.configure(IconGlassNativeCacheProbeActivity.Fast::class.java,
            IconGlassNativeCacheProbeActivity.Info::class.java,IconGlassNativeCacheProbeActivity.Item::class.java)
        Canvas(image).drawBitmap(source,0f,0f,null)
        beginMode()
        ui.postDelayed({if(!finished)finishReport(IllegalStateException("View-entry pipeline timed out"))},15000)
    }
    private fun beginMode() {
        LauncherIconGlassAssets.setActive(true)
        originalInfo=IconGlassNativeCacheProbeActivity.Info(source,0)
        item=IconGlassNativeCacheProbeActivity.Item(originalInfo)
        native=IconGlassNativeCacheProbeActivity.Fast(source).apply {setBounds(0,0,128,128)}
        host=View(this).apply {tag=item}
        attempt()
    }
    private fun attempt() {
        if(finished)return
        try {
            val bound=LauncherIconGlassBinding.prepare(host,native,item,source,null,0,RectF(0f,0f,128f,128f),
                IconGlassMaterial.Params(),ready,IconGlassWallpaper.Frame(wall,RectF(0f,0f,128f,128f)))
            if(!bound)return
            val generated=native.mBitmap
            check(generated!==source && LauncherIconGlassAssets.isGenerated(generated))
            check(item.bitmap.icon===generated)
            val output=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            native.draw(Canvas(output)) // No draw hook, shader hook or substitute rendering.
            check(output.sameAs(generated)) {"Native consumer did not draw the bound bitmap"}
            check(!output.sameAs(source)) {"Native consumer still displays the original"}
            Canvas(image).drawBitmap(output,128f*(mode+1),0f,null)
            val duplicateSource=source.copy(Bitmap.Config.ARGB_8888,false)!!
            val rebound=IconGlassNativeCacheProbeActivity.Item(IconGlassNativeCacheProbeActivity.Info(duplicateSource,0))
            val reboundIcon=IconGlassNativeCacheProbeActivity.Fast(duplicateSource)
            LauncherIconGlassAssets.bind(View(this),reboundIcon,rebound)
            check(reboundIcon.mBitmap===generated) {"Page rebind lost the generated fallback"}
            reports.put(modes[mode],JSONObject().put("nativeDrawDisplaysGeneratedBitmap",true)
                .put("innerDrawableHookUsed",false).put("itemCacheBound",true).put("copiedBitmapRebindUsesPrefab",true))
            LauncherIconGlassAssets.setActive(false)
            check(native.mBitmap===source && item.bitmap===originalInfo)
            check(source.generationId==sourceGeneration && !source.isRecycled)
            mode++
            if(mode<modes.size)beginMode() else finishReport(null)
        } catch(t:Throwable) {finishReport(t)}
    }
    private fun finishReport(error:Throwable?) {
        if(finished)return
        finished=true
        LauncherIconGlassAssets.setActive(false)
        reports.put("passed",error==null).put("activationBeforeConfigureHandled",error==null)
            .put("sourceRestoredAndUnchanged",error==null).put("vendorLauncherHookExecuted",false)
            .put("physicalDeviceTested",false).put("liveShaderExecutedInThisProbe",false)
        if(error!=null)reports.put("error",error.stackTraceToString())
        val dir=File(filesDir,"icon-glass-view-binding-probe").apply {mkdirs()}
        File(dir,"report.json").writeText(reports.toString(2))
        File(dir,"native-comparison.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        label.text=reports.toString(2)
    }
}
