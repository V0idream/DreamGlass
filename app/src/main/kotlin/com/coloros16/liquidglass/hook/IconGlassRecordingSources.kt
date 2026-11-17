/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.WeakHashMap

/** Records only the explicitly selected icon/view draw, never a screen/window capture.
 * The native callback executes once; an immutable display list supplies one bounded GPU copy. */
internal object IconGlassRecordingSources {
    private class Entry {var bitmap:Bitmap?=null;var pending=false;var failedUntil=0L;var preview:RenderNode?=null}
    private data class Job(val entry:Entry,val node:RenderNode,val width:Int,val height:Int,val ready:()->Unit,val epoch:Long)
    private val entries=WeakHashMap<Any,Entry>() // UI thread only, no value points back to its key.
    private val lock=Any()
    private val jobs=java.util.ArrayDeque<Job>()
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-icon-recording")
    private val ui=Handler(Looper.getMainLooper())
    private var running=false
    @Volatile private var epoch=0L
    @Volatile private var sequence=0L
    private var accelerator:HardwareBackdropBlur?=null // Serial worker only; no idle rendering.
    private val recording=ThreadLocal<Boolean>()
    fun isRecording()=recording.get()==true
    fun get(key:Any):Bitmap?=entries[key]?.bitmap
    fun preview(key:Any):RenderNode?=entries[key]?.preview
    fun canRecord(key:Any)=entries[key]?.let {it.bitmap==null && !it.pending && it.failedUntil<SystemClock.uptimeMillis()} ?: true
    fun invalidate(key:Any) {entries.remove(key)}
    fun clear() {
        epoch++;synchronized(lock) {jobs.clear()};entries.clear()
        val ticket=sequence
        // HardwareRenderer.destroy invalidates retained child display lists. Do not
        // destroy it while a first-frame preview still uses that native recording.
        worker.execute {if(sequence==ticket) {accelerator?.close();accelerator=null}}
    }
    fun cancelPending() {
        epoch++;synchronized(lock) {jobs.clear()};entries.values.forEach {it.pending=false}
    }
    /** Returns null before invoking native code if no bounded slot is available. */
    fun record(key:Any,width:Int,height:Int,crop:Rect,ready:()->Unit,original:(Canvas)->Any?):Pair<RenderNode,Any?>? {
        if(width !in 1..2048 || height !in 1..2048 || crop.isEmpty || !canRecord(key))return null
        if(synchronized(lock) {jobs.size>=12})return null
        sequence++
        val entry=entries.getOrPut(key) {Entry()}
        val node=runCatching {RenderNode("DreamGlass original icon").apply {setPosition(0,0,width,height)}}.getOrNull() ?: return null
        val recording=runCatching {node.beginRecording(width,height)}.getOrNull() ?: return null
        val previous=this.recording.get();this.recording.set(true)
        val result=try {original(recording)}finally {
            if(previous==null)this.recording.remove() else this.recording.set(previous)
            node.endRecording()
        }
        val icon=RenderNode("DreamGlass icon region").apply {setPosition(0,0,crop.width(),crop.height())}
        val iconCanvas=icon.beginRecording(crop.width(),crop.height())
        try {iconCanvas.translate(-crop.left.toFloat(),-crop.top.toFloat());iconCanvas.drawRenderNode(node)}finally {icon.endRecording()}
        entry.pending=true;entry.preview=node
        synchronized(lock) {
            jobs.add(Job(entry,icon,crop.width(),crop.height(),ready,epoch))
            if(!running) {running=true;try {worker.execute(::drain)}catch(_:Throwable) {running=false;jobs.clear();entry.pending=false}}
        }
        return node to result
    }
    private fun drain() {
        try {
            while(true) {
                val job=synchronized(lock) {if(jobs.isEmpty())return else jobs.removeFirst()}
                var output:Bitmap?=null
                try {
                    if(job.epoch!=epoch)continue
                    val ratio=128f/maxOf(job.width,job.height)
                    val renderer=accelerator ?: HardwareBackdropBlur().also {accelerator=it}
                    val hardware=renderer.render(job.node,job.width,job.height,
                        (job.width*ratio).toInt().coerceAtLeast(1),(job.height*ratio).toInt().coerceAtLeast(1),0f) {job.epoch!=epoch}
                    output=try {hardware.copy(Bitmap.Config.ARGB_8888,false)}finally {hardware.recycle()}
                    val owned=checkNotNull(output)
                    if(ui.post {
                        if(job.epoch==epoch) {
                            job.entry.bitmap=owned;job.entry.pending=false;job.entry.preview=null
                            RuntimeWorkPolicy.guarded({}) {job.ready()}
                        } else owned.recycle()
                    })output=null
                } catch(_:Throwable) {
                    ui.post {if(job.epoch==epoch) {job.entry.pending=false;job.entry.failedUntil=SystemClock.uptimeMillis()+5000L}}
                } finally {output?.recycle()}
                // Display lists submitted to a real Canvas are not discarded/reused; native
                // recording references retain them until that frame has been consumed.
            }
        } finally {
            synchronized(lock) {
                running=false
                if(jobs.isNotEmpty()) {running=true;try {worker.execute(::drain)}catch(_:Throwable) {running=false;jobs.clear()}}
            }
        }
    }
}

/** Cheap first-frame fallback: never exposes a fully coloured native icon while preparing. */
internal object IconGlassFallback {
    val backdrop:Bitmap by lazy {Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff73899e.toInt())}}
    private val mono=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            .15f,.50f,.05f,0f,60f, .15f,.50f,.05f,0f,70f, .15f,.50f,.05f,0f,80f, 0f,0f,0f,.94f,0f)))
    }
    fun empty(canvas:Canvas,bounds:Rect) {
        val rect=RectF(bounds);val radius=minOf(rect.width(),rect.height())*.235f
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
                intArrayOf(0xd9b9cbd8.toInt(),0xdc667b93.toInt()),null,Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect,radius,radius,paint)
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=0x77ffffff
        canvas.drawRoundRect(rect,radius,radius,paint)
    }
    fun recorded(canvas:Canvas,node:RenderNode,crop:Rect) {
        val outside=canvas.save();canvas.clipOutRect(crop);canvas.drawRenderNode(node);canvas.restoreToCount(outside)
        val area=RectF(crop);val radius=minOf(area.width(),area.height())*.235f
        val outline=Path().apply {addRoundRect(area,radius,radius,Path.Direction.CW)}
        val inside=canvas.saveLayer(RectF(crop),mono)
        canvas.clipPath(outline);canvas.drawRenderNode(node);canvas.restoreToCount(inside)
    }
    fun draw(canvas:Canvas,bounds:Rect,source:Bitmap,material:IconGlassMaterial,alpha:Int):Boolean {
        if(material.draw(canvas,RectF(bounds),source,backdrop,RectF(0f,0f,8f,8f),
            IconGlassMaterial.Params(alpha=alpha,keepColor=false,reliefArtwork=true)))return true
        val paint=Paint(mono).apply {this.alpha=alpha}
        if(canvas.isHardwareAccelerated || source.config!=Bitmap.Config.HARDWARE) {
            canvas.drawBitmap(source,null,bounds,paint);return true
        }
        return false
    }
}
