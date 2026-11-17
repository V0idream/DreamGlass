package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.widget.TextView
import com.coloros16.liquidglass.hook.*
import java.io.File
import org.json.JSONObject

/** Own synthetic icon drawing only: no screen capture or installed application inputs. */
class IconGlassRecordingProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Recording fallback / dedicated AGC camera…";setPadding(20,60,20,20)}
        setContentView(label)
        val folder=File(filesDir,"icon-glass-recording-probe").apply {mkdirs()}
        fun finish(result:JSONObject) {
            result.put("physicalDeviceTested",false).put("vendorLauncherHookExecuted",false)
            folder.resolve("report.json").writeText(result.toString(2));runOnUiThread {label.text=result.toString(2)}
        }
        var calls=0;var completed=false
        val key=Any();val crop=Rect(16,16,144,144)
        try {
            val result=IconGlassRecordingSources.record(key,160,180,crop,{
                if(!completed) {
                    completed=true
                    val image=checkNotNull(IconGlassRecordingSources.get(key))
                    Thread({try {
                        check(calls==1 && !IconGlassRecordingSources.isRecording())
                        check(image.width==128 && image.height==128 && image.getPixel(64,64)==Color.MAGENTA)
                        val preview=checkNotNull(previewNode)
                        check(checkNotNull(originalNode).hasDisplayList() && preview.hasDisplayList()) {"First-frame display lists were destroyed by source preparation"}
                        val hardware=HardwareBackdropBlur().use {it.render(preview,160,180,160,180,.01f) {false}}
                        val mono=try {hardware.copy(Bitmap.Config.ARGB_8888,false)!!}finally {hardware.recycle()}
                        folder.resolve("first-frame.png").outputStream().use {mono.compress(Bitmap.CompressFormat.PNG,100,it)}
                        val tint=mono.getPixel(80,80)
                        check(kotlin.math.abs(Color.red(tint)-Color.blue(tint))<30) {"Original colours exposed in first frame"}
                        val outside=mono.getPixel(8,170)
                        check(Color.alpha(outside)>=253 && Color.red(outside)<=2 && Color.green(outside)>=253 && Color.blue(outside)>=253) {
                            "Native label/outside crop changed: ${Integer.toHexString(outside)}"
                        }
                        folder.resolve("first-frame.png").outputStream().use {mono.compress(Bitmap.CompressFormat.PNG,100,it)}
                        runOnUiThread {
                            IconGlassRecordingSources.clear()
                            check(IconGlassRecordingSources.get(key)==null)
                            check(!image.isRecycled) // Published frames can still reference the source.
                            finish(JSONObject().put("passed",true).put("nativeDrawCalledOnce",true)
                                .put("recordingReentrancyGuard",true).put("sourceCroppedBeforeReadback",true)
                                .put("firstFrameOriginalColoursSuppressed",true).put("outsideIconPreserved",true)
                                .put("publishedSourceNotRecycled",true))
                        }
                    }catch(t:Throwable) {finish(JSONObject().put("passed",false).put("error",t.stackTraceToString()))}},"DreamGlass-recording-probe").start()
                }
            }) {canvas ->
                calls++;check(IconGlassRecordingSources.isRecording())
                canvas.drawColor(Color.CYAN)
                canvas.drawRect(crop,Paint().apply {color=Color.MAGENTA})
                "native-result"
            }
            check(result!=null && result.second=="native-result" && calls==1)
            originalNode=result.first
            check(!IconGlassRecordingSources.isRecording() && !IconGlassRecordingSources.canRecord(key))
            check(IconGlassRecordingSources.record(key,160,180,crop,{}) {calls++}==null)
            previewNode=RenderNode("Tinted first icon frame").apply {setPosition(0,0,160,180)}
            val canvas=previewNode!!.beginRecording(160,180)
            try {IconGlassFallback.recorded(canvas,result.first,crop)}finally {previewNode!!.endRecording()}
            label.postDelayed({if(!completed) {completed=true;finish(JSONObject().put("passed",false).put("error","Source publication timed out"))}},12000)
        }catch(t:Throwable) {completed=true;finish(JSONObject().put("passed",false).put("error",t.stackTraceToString()))}
    }
    private var previewNode:RenderNode?=null
    private var originalNode:RenderNode?=null
}
