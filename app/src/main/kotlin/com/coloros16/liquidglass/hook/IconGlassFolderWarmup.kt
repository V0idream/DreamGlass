package com.coloros16.liquidglass.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Prepare a closed folder's first screen in bounded idle slices, never in touch/draw.
 * Mask extraction and GPU readback remain on their existing serial workers. */
internal object IconGlassFolderWarmup {
    private class Job(view:View) {
        val host=WeakReference(view)
        var info=WeakReference<Any>(null)
        var count=-1
        var preferences=-1
        var wallpaper=-1L
        var items=emptyList<WeakReference<Any>>()
        var cursor=0
        var queued=false
        var allowed:()->Boolean={false}
        var prepare:((View,Any,()->Unit)->Unit)?=null
    }
    private val ui=Handler(Looper.getMainLooper())
    private val jobs=WeakHashMap<View,Job>()
    private val fields=WeakHashMap<Class<*>,Field?>()
    fun observe(view:View,preferences:Int,wallpaper:Long,allowed:()->Boolean,
        prepare:(View,Any,()->Unit)->Unit) {
        val info=view.tag ?: return
        val field=if(fields.containsKey(info.javaClass))fields[info.javaClass] else {
            var type:Class<*>?=info.javaClass;var found:Field?=null
            while(type!=null && found==null) {
                found=runCatching {type!!.getDeclaredField("contents").apply {isAccessible=true}}.getOrNull()
                type=type.superclass
            }
            fields[info.javaClass]=found;found
        } ?: return
        val contents=field.get(info) as? Collection<*> ?: return
        val job=jobs.getOrPut(view) {Job(view)}
        job.allowed=allowed;job.prepare=prepare
        if(job.info.get()!==info || job.count!=contents.size || job.preferences!=preferences || job.wallpaper!=wallpaper) {
            job.info=WeakReference(info);job.count=contents.size;job.preferences=preferences;job.wallpaper=wallpaper
            job.items=contents.asSequence().filterNotNull().take(24).map {WeakReference(it)}.toList();job.cursor=0
        }
        if(job.cursor<job.items.size)enqueue(job)
    }
    private fun enqueue(job:Job) {
        if(job.queued)return
        job.queued=true
        ui.post {
            Looper.myQueue().addIdleHandler {
                job.queued=false
                val view=job.host.get()
                if(view!=null && view.isAttachedToWindow && view.isShown && job.allowed()) {
                    val done:()->Unit={ui.post {
                        job.cursor=0;enqueue(job)
                        job.host.get()?.postInvalidateOnAnimation()
                    };Unit}
                    repeat(4) {
                        if(job.cursor<job.items.size) {
                            val item=job.items[job.cursor++].get()
                            if(item!=null)runCatching {job.prepare?.invoke(view,item,done)}
                        }
                    }
                    if(job.cursor<job.items.size)enqueue(job)
                }
                false
            }
        }
    }
}
