/* DreamGlass contributors, 2026. GPL-3.0. Bounded initial mask preparation only. */
package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import java.lang.ref.WeakReference

/** One serial worker, at most 96 pending icons, 128 prepared masks. No screen capture or timers.
 * resolve() only looks up/queues; drawable loading, rasterization and pixel analysis are off UI.
 * Vendor images are borrowed read-only and are never recycled by this object. */
internal object IconGlassMasks {
    data class Prepared(val foreground:Bitmap,val kind:String,val separated:Boolean=true,val relief:Boolean=false,val passthrough:Boolean=false,val detailed:Boolean=false)
    private data class Key(val component:String,val user:Int,val identity:Int,val generation:Int,
        val width:Int,val height:Int,val configuration:Int,val variant:String)
    private data class Cached(val original:WeakReference<Bitmap>,val value:Prepared)
    private data class Request(val key:Key,val context:Context,val component:ComponentName?,
        val original:Bitmap,val ready:()->Unit,val epoch:Long)
    private val lock=Any()
    private val cache=LinkedHashMap<Key,Cached>(64,.75f,true)
    private val pending=LinkedHashMap<Key,Request>()
    private val inFlight=HashMap<Key,Long>()
    private val failed=LinkedHashMap<Key,Long>(64,.75f,true)
    private val executor=RuntimeWorkPolicy.executor("DreamGlass-icon-masks")
    private var running=false
    @Volatile private var epoch=0L
    private const val SIZE=128

    fun resolve(context:Context,component:ComponentName?,userId:Int,original:Bitmap,onReady:()->Unit):Prepared? {
        if(original.isRecycled || original.width<=0 || original.height<=0)return null
        val key=Key(component?.flattenToString()?:"",userId,System.identityHashCode(original),original.generationId,
            original.width,original.height,context.resources.configuration.hashCode(),IconGlassDynamicIcons.variant(component?.flattenToString()))
        synchronized(lock) {
            cache[key]?.let {
                if(it.original.get()===original && !it.value.foreground.isRecycled) {
                    IconGlassPipelineTrace.record(component,"mask","ready ${it.value.kind}");return it.value
                }
                cache.remove(key)
            }
            failed[key]?.let {if(SystemClock.uptimeMillis()<it)return null else failed.remove(key)}
            // A draw occurring while prepare() owns this key must not enqueue duplicate work.
            // Keep the epoch: after clear(), the same icon still needs a fresh preparation.
            if(inFlight[key]==epoch)return null
            if(!pending.containsKey(key)) {
                if(pending.size>=96)pending.remove(pending.keys.first())
                pending[key]=Request(key,context.applicationContext?:context,component,original,onReady,epoch)
                IconGlassPipelineTrace.record(component,"mask","queued ${key.identity}/${key.generation}")
            }
            if(!running) {
                running=true
                try {executor.execute(::drain)} catch(_:Throwable) {running=false;pending.clear()}
            }
        }
        return null
    }

    /** Drop published references, not pixels: a recorded shader/display list can retain them. */
    fun clear() = synchronized(lock) {epoch++;cache.clear();pending.clear();failed.clear()}

    /** Screen-off/hidden: cancel queued work without forcing another mask pass on wake. */
    fun cancelPending() = synchronized(lock) {epoch++;pending.clear();failed.clear()}

    private fun drain() {
        runCatching {Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)}
        try {
            while(true) {
                val request=synchronized(lock) {
                    val entry=pending.entries.firstOrNull()?:return
                    pending.remove(entry.key)
                    entry.value.also {inFlight[it.key]=it.epoch}
                }
                try {
                    if(request.epoch!=epoch)continue
                    val value=try {prepare(request)} catch(error:Throwable) {
                        IconGlassPipelineTrace.record(request.component,"mask","failed ${error.javaClass.simpleName}: ${error.message}")
                        if(com.coloros16.liquidglass.BuildConfig.DEBUG && request.component?.packageName in setOf("com.codex.floatingcamera","me.sukimon.mdl"))
                            android.util.Log.e("DG-IconMask", "prepare ${request.component}",error)
                        null
                    }
                    if(value==null) {
                        synchronized(lock) {
                            if(request.epoch==epoch) {
                                if(failed.size>=64)failed.remove(failed.keys.first())
                                failed[request.key]=SystemClock.uptimeMillis()+2_000L
                            }
                        }
                        continue
                    }
                    val publish=synchronized(lock) {
                        if(request.epoch!=epoch)false else {
                            if(cache.size>=128 && !cache.containsKey(request.key))cache.remove(cache.keys.first())
                            cache[request.key]=Cached(WeakReference(request.original),value);true
                        }
                    }
                    if(publish) {
                        IconGlassPipelineTrace.record(request.component,"mask","published ${value.kind}")
                        RuntimeWorkPolicy.guarded({}) {
                            RuntimeDiagnostics.mark("icon_mask_"+value.kind,"桌面图标前景已准备："+value.kind)
                        }
                        RuntimeWorkPolicy.guarded({}) {request.ready()}
                    } else value.foreground.recycle() // Unpublished, so no shader can retain this image.
                } finally {
                    synchronized(lock) {
                        if(inFlight[request.key]==request.epoch)inFlight.remove(request.key)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                running=false
                if(pending.isNotEmpty()) {
                    running=true
                    try {executor.execute(::drain)}catch(_:Throwable) {running=false;pending.clear()}
                }
            }
        }
    }

    private fun prepare(request:Request):Prepared? {
        val prepared=prepareRaw(request) ?: return null
        if(prepared.passthrough || prepared.detailed)return prepared
        val image=prepared.foreground
        val pixels=IntArray(image.width*image.height)
        image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
        val tonal=if(prepared.relief)null else IconSubjectExtractor.tonalize(pixels)
        val centered=IconSubjectExtractor.center(tonal ?: pixels,image.width,image.height)
        val normalized=Bitmap.createBitmap(centered,image.width,image.height,Bitmap.Config.ARGB_8888)
        image.recycle() // Owned and never published; original/vendor images were not passed here.
        return prepared.copy(foreground=normalized,relief=prepared.relief || tonal!=null)
    }
    private fun prepareRaw(request:Request):Prepared? {
        IconGlassLibraryRuntime.create(request.context,request.component?.packageName,SIZE)?.let {return it}
        IconGlassDynamicIcons.create(request.component?.packageName,SIZE)?.let {return it}
        val icon=loadActivityIcon(request)
        IconGlassDefaultIcon.create(request.context,icon,SIZE)?.let {return it}
        if(icon is AdaptiveIconDrawable) {
            // Let the adaptive parent establish its children's real overscan/inset bounds.
            // Drawing a foreground directly into 0..SIZE shrinks its intended logo geometry.
            val adaptive=runCatching {
                (icon.constantState?.newDrawable(request.context.resources)?.mutate() as? AdaptiveIconDrawable)
                    ?.takeIf {it!==icon}?.apply {setBounds(0,0,SIZE,SIZE)}
            }.getOrNull()
            if(adaptive!=null) {
                if(Build.VERSION.SDK_INT>=33) {
                    adaptive.monochrome?.let {mono ->
                        usefulDrawable(mono,request.context,Rect(mono.bounds))?.let {return Prepared(it,"adaptive-monochrome")}
                    }
                }
                // COS themes can return a nominal AdaptiveIconDrawable with no foreground.
                // Java's platform type does not make this non-null at runtime.
                adaptive.foreground?.let {foreground ->
                    usefulDrawable(foreground,request.context,Rect(foreground.bounds))
                        ?.let {return Prepared(it,"adaptive-foreground")}
                }
            }
        }
        if(request.original.isRecycled || request.epoch!=epoch)return null
        // copy() owns a software buffer even when Launcher supplied a HARDWARE bitmap.
        val copy=request.original.copy(Bitmap.Config.ARGB_8888,false)?:return null
        val scaled=try {Bitmap.createScaledBitmap(copy,SIZE,SIZE,true)}catch(error:Throwable) {copy.recycle();throw error}
        if(scaled!==copy)copy.recycle()
        try {
            val pixels=IntArray(SIZE*SIZE);scaled.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE)
            val mask=IconMaskPolicy.extract(pixels,SIZE,SIZE)
            val subject=if(!mask.separated)IconSubjectExtractor.extract(pixels,SIZE,SIZE) else null
            val bitmap=Bitmap.createBitmap(subject?.pixels ?: mask.pixels,SIZE,SIZE,Bitmap.Config.ARGB_8888)
            return Prepared(bitmap,subject?.kind ?: mask.kind,true,subject!=null)
        } finally {scaled.recycle()}
    }

    private fun loadActivityIcon(request:Request):Drawable? {
        val component=request.component?:return null
        return runCatching {
            if(request.key.user!=Process.myUserHandle().hashCode()) {
                val apps=request.context.getSystemService(LauncherApps::class.java)?:return@runCatching null
                apps.getActivityList(component.packageName,UserHandle.getUserHandleForUid(request.key.user*100000))
                    .firstOrNull {component.className.isEmpty() || it.componentName==component}
                    ?.getIcon(request.context.resources.displayMetrics.densityDpi)
            } else if (component.className.isEmpty()) {
                request.context.packageManager.getApplicationIcon(component.packageName)
            } else {
                request.context.packageManager.getActivityIcon(component)
            }
        }.getOrNull()
    }

    private fun rasterize(input:Drawable,context:Context,targetBounds:Rect):Bitmap {
        // No mutation of an uncloneable host drawable. Fall back to the borrowed original
        // bitmap path rather than racing another thread's drawable bounds/state.
        val drawable=input.constantState?.newDrawable(context.resources)?.mutate()
            ?: throw IllegalArgumentException("Icon drawable cannot be cloned safely")
        check(drawable!==input) {"Icon constant state returned the original drawable"}
        val bounds=Rect(drawable.bounds)
        val result=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888)
        try {
            drawable.bounds=targetBounds.takeUnless {it.isEmpty}?:Rect(0,0,SIZE,SIZE)
            drawable.draw(Canvas(result));return result
        }
        catch(error:Throwable) {result.recycle();throw error}
        finally {drawable.bounds=bounds}
    }

    private fun usefulAlpha(bitmap:Bitmap):Boolean {
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return IconMaskPolicy.hasUsefulAlpha(pixels,bitmap.width,bitmap.height)
    }

    private fun usefulDrawable(drawable:Drawable,context:Context,targetBounds:Rect):Bitmap? {
        var image:Bitmap?=null
        return try {
            image=rasterize(drawable,context,targetBounds)
            if(usefulAlpha(image))image else {image.recycle();null}
        } catch(_:Throwable) {image?.recycle();null}
    }
}
