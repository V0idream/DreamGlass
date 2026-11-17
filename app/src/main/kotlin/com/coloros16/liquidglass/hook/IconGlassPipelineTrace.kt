package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.graphics.Bitmap
import com.coloros16.liquidglass.BuildConfig
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics

/** Two bounded, event-driven debug records. Never samples the screen or schedules work. */
internal object IconGlassPipelineTrace {
    private val records=HashMap<String,LinkedHashMap<String,String>>()
    fun record(component:ComponentName?,stage:String,detail:String)=record(component?.flattenToString(),stage,detail)
    @Synchronized fun record(identity:String?,stage:String,detail:String) {
        if(!BuildConfig.DEBUG || identity==null)return
        val pkg=identity.substringBefore('/')
        if(pkg!="com.codex.floatingcamera" && pkg!="me.sukimon.mdl")return
        val values=records.getOrPut(pkg) {LinkedHashMap()}
        val text=detail.take(150)
        if(values.put(stage,text)==text)return
        while(values.size>4)values.remove(values.keys.first())
        RuntimeDiagnostics.update("icon_pipeline_$pkg",values.entries.joinToString("; ") {"${it.key}=${it.value}"}.take(500))
    }
    fun source(bitmap:Bitmap?)=when {
        bitmap==null->"null"
        bitmap.isRecycled->"recycled"
        else->"${bitmap.width}x${bitmap.height}/${bitmap.config}/id=${System.identityHashCode(bitmap)}/g=${bitmap.generationId}"
    }
}
