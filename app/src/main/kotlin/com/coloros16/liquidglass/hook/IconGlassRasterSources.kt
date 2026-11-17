/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import java.lang.ref.WeakReference

/** Static engine/vector icons only. Clone before recording; never mutate the vendor drawable.
 * A single bounded worker prepares sources once, not per-frame or by screen capture. */
internal object IconGlassRasterSources {
    private data class Key(val identity:Int,val configuration:Int)
    private data class Value(val source:WeakReference<Drawable>,val bitmap:Bitmap)
    private data class Request(val drawable:Drawable,val context:Context,val ready:()->Unit,val epoch:Long)
    private val lock=Any()
    private val cache=LinkedHashMap<Key,Value>(64,.75f,true)
    private val requests=LinkedHashMap<Key,Request>()
    private val flight=HashMap<Key,Long>()
    private val failed=LinkedHashSet<Key>()
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-static-icon")
    private var epoch=0L
    private var running=false
    fun resolve(context:Context,drawable:Drawable,ready:()->Unit):Bitmap? {
        if(drawable is Animatable)return null
        val key=Key(System.identityHashCode(drawable),context.resources.configuration.hashCode())
        synchronized(lock) {
            cache[key]?.takeIf {it.source.get()===drawable}?.let {return it.bitmap}
            if(key in failed || flight[key]==epoch)return null
            if(key !in requests) {
                if(requests.size>=12)requests.remove(requests.keys.first())
                requests[key]=Request(drawable,context.applicationContext ?: context,ready,epoch)
            }
            if(!running) {
                running=true
                try {worker.execute(::drain)} catch(_:Throwable) {running=false;requests.clear()}
            }
        }
        return null
    }
    fun clear()=synchronized(lock) {epoch++;cache.clear();requests.clear();failed.clear()}
    fun cancelPending()=synchronized(lock) {epoch++;requests.clear();failed.clear()}
    private fun drain() {
        try {
            while(true) {
                val (key,request)=synchronized(lock) {
                    val next=requests.entries.firstOrNull() ?: return
                    requests.remove(next.key);flight[next.key]=next.value.epoch
                    next.key to next.value
                }
                var output:Bitmap?=null
                var publish=false
                try {
                    if(synchronized(lock) {request.epoch!=epoch})continue
                    val copy=request.drawable.constantState?.newDrawable(request.context.resources)?.mutate()
                        ?.takeIf {it!==request.drawable} ?: error("No independent static icon state")
                    output=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
                    copy.setBounds(0,0,128,128);copy.draw(Canvas(output))
                    synchronized(lock) {
                        if(request.epoch==epoch) {
                            cache[key]=Value(WeakReference(request.drawable),checkNotNull(output))
                            while(cache.size>64)cache.remove(cache.keys.first())
                            publish=true
                        }
                    }
                } catch(_:Throwable) {
                    synchronized(lock) {
                        if(request.epoch==epoch) {failed.add(key);while(failed.size>64)failed.remove(failed.first())}
                    }
                } finally {
                    if(!publish)output?.recycle()
                    synchronized(lock) {if(flight[key]==request.epoch)flight.remove(key)}
                }
                if(publish)RuntimeWorkPolicy.guarded({}) {request.ready()}
            }
        } finally {
            synchronized(lock) {
                running=false
                if(requests.isNotEmpty()) {
                    running=true
                    try {worker.execute(::drain)} catch(_:Throwable) {running=false;requests.clear()}
                }
            }
        }
    }
}
