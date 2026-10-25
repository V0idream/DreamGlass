package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.graphics.ColorSpace
import android.graphics.RectF
import android.os.SystemClock
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Four bounded style slots, one running computation, latest pending capture per style.
 * All pixels/filtering are on a background thread; published F16 bitmaps are never recycled. */
internal class ButtonBackdropWorker(private val invalidate:()->Unit,private val failed:(Throwable)->Unit) {
    private data class Geometry(val width:Int,val height:Int,val left:Float,val top:Float,val screenWidth:Float,val screenHeight:Float)
    private data class Style(val geometry:Geometry,val radius:Float)
    private data class Key(val sourceId:Int,val generation:Int,val style:Style)
    private data class Request(val key:Key,val source:Bitmap)
    private val lock=Any()
    private val executor=ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>(1),
        { task -> Thread(task,"DreamGlass-f16-blur").apply { isDaemon=true } }).apply { allowCoreThreadTimeOut(true) }
    private val requested=LinkedHashMap<Style,Key>(4,.75f,true)
    private val pending=LinkedHashMap<Style,Request>()
    private val ready=HashMap<Style,Bitmap>()
    private var geometry:Geometry?=null
    private var running=false
    @Volatile private var retryAtMs=0L

    fun resolve(source:Bitmap,screen:RectF,radius:Float):Bitmap? {
        if(source.isRecycled || screen.width()<=0 || screen.height()<=0 || !radius.isFinite() || radius<=0)return null
        if(SystemClock.uptimeMillis()<retryAtMs)return null
        val geo=Geometry(source.width,source.height,screen.left,screen.top,screen.width(),screen.height())
        val style=Style(geo,radius);val key=Key(System.identityHashCode(source),source.generationId,style)
        synchronized(lock) {
            if(geometry!=geo) { geometry=geo;requested.clear();pending.clear();ready.clear() }
            if(requested[style]!=key) {
                if(!requested.containsKey(style) && requested.size>=4) {
                    val oldest=requested.keys.first();requested.remove(oldest);pending.remove(oldest);ready.remove(oldest)
                }
                requested[style]=key;pending[style]=Request(key,source)
                if(!running) {
                    running=true
                    try { executor.execute(::drain) } catch(t:Throwable) { running=false;requested.clear();pending.clear();throw t }
                }
            }
            return ready[style]
        }
    }

    private fun drain() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch(_:Throwable) { }
        while(true) {
            val request=synchronized(lock) {
                val next=pending.entries.firstOrNull()
                if(next==null) { running=false;return }
                pending.remove(next.key);next.value
            }
            try {
                val bitmap=compute(request)
                val publish=synchronized(lock) {
                    val style=request.key.style
                    if(geometry==style.geometry && requested.containsKey(style)) { ready[style]=bitmap;true } else false
                }
                if(publish)invalidate()
            } catch(t:Throwable) {
                retryAtMs=SystemClock.uptimeMillis()+1000L
                synchronized(lock) { requested.clear();pending.clear() }
                failed(t)
            }
        }
    }

    private fun compute(request:Request):Bitmap {
        val source=request.source;val style=request.key.style;val geo=style.geometry
        var small=if(source.config==Bitmap.Config.HARDWARE)source.copy(Bitmap.Config.ARGB_8888,false) else source
        try {
            val size=ButtonBackdropBlur.workingSize(source.width,source.height,geo.screenWidth,geo.screenHeight,style.radius)
            while(small.width/2>=size[0] && small.height/2>=size[1] && (small.width>size[0] || small.height>size[1])) {
                val next=Bitmap.createScaledBitmap(small,(small.width/2).coerceAtLeast(1),(small.height/2).coerceAtLeast(1),true)
                if(small!==source && small!==next)small.recycle();small=next
            }
            if(small.width!=size[0] || small.height!=size[1]) {
                val next=Bitmap.createScaledBitmap(small,size[0],size[1],true)
                if(small!==source && small!==next)small.recycle();small=next
            }
            val pixels=IntArray(small.width*small.height)
            small.getPixels(pixels,0,small.width,0,0,small.width,small.height)
            val sigma=style.radius*(small.width/geo.screenWidth+small.height/geo.screenHeight)*.5f
            return FloatBackdropBitmap.create(ButtonBackdropBlur.applyPremultiplied(pixels,small.width,small.height,sigma),small.width,small.height)
        } finally { if(small!==source)small.recycle() }
    }
}

/** RGBA half floats in the bitmap's declared sRGB space, with premultiplied channels. */
internal object FloatBackdropBitmap {
    // These are raw IEEE-754 binary16 bits written to a ByteBuffer, not widened numeric halves.
    // Android's debug probe verifies the resulting fractional channels and premultiplied alpha.
    @SuppressLint("HalfFloat")
    fun create(argb:FloatArray,width:Int,height:Int):Bitmap {
        require(argb.size==width*height*4)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.RGBA_F16,true,ColorSpace.get(ColorSpace.Named.SRGB))
        val buffer=ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
        for(y in 0 until height) {
            buffer.position(y*bitmap.rowBytes)
            for(x in 0 until width) {
                val i=(y*width+x)*4;val alpha=argb[i].coerceIn(0f,255f)
                for(c in 1..3)buffer.putShort(Half.toHalf(argb[i+c].coerceIn(0f,alpha)/255f))
                buffer.putShort(Half.toHalf(alpha/255f))
            }
        }
        buffer.rewind();bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}
