/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Runtime icon-cache replacement, never an APK/database rewrite. Native consumers and floating
 * icon animations share an actual generated bitmap; restoring uses identity-checked originals. */
internal object LauncherIconGlassAssets {
    private class NativeSizedBitmap(resources:android.content.res.Resources,bitmap:Bitmap,
        private val nativeWidth:Int,private val nativeHeight:Int):BitmapDrawable(resources,bitmap) {
        override fun getIntrinsicWidth()=nativeWidth
        override fun getIntrinsicHeight()=nativeHeight
    }
    private data class Key(val identity:Int,val generation:Int,val style:Int,val variant:String)
    private data class Asset(val source:Bitmap,val rendered:Bitmap,val epoch:Long)
    private data class Original(val source:Bitmap,val epoch:Long,val variant:String)
    private data class Request(val key:Key,val source:Bitmap,val mask:Bitmap,val wallpaper:Bitmap,
        val crop:RectF,val sharpWallpaper:Bitmap,val params:IconGlassMaterial.Params,val ready:()->Unit,val epoch:Long,val workEpoch:Long,val identity:String?)
    private data class Swap(val source:Bitmap,val rendered:Bitmap,val nativeAlpha:Paint?,val nativeFilter:Paint?,
        val drawing:Paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    private data class ItemSwap(val field:Field,val original:Any,val replacement:Any)
    private data class ViewSwap(val original:Drawable,val replacement:Drawable)
    private data class SlotSwap(val field:Field,val original:Drawable,val replacement:Drawable)
    private val ui=Handler(Looper.getMainLooper())
    private val lock=Any()
    private val cache=LinkedHashMap<Key,Asset>(64,.75f,true)
    private val pending=LinkedHashMap<Key,Request>()
    private val inFlight=HashMap<Key,Long>()
    private val failed=LinkedHashMap<Key,Long>()
    private val reverse=WeakHashMap<Bitmap,Original>()
    private val drawables=WeakHashMap<Drawable,Swap>()
    private val items=WeakHashMap<Any,ItemSwap>()
    private val infoOrigins=WeakHashMap<Any,Any>()
    private val viewSwaps=WeakHashMap<View,ViewSwap>()
    private val slotSwaps=WeakHashMap<Any,SlotSwap>()
    private val floatingAssets=WeakHashMap<Any,Bitmap>()
    private val componentAssets=LinkedHashMap<String,Bitmap>(192,.75f,true)
    private val infoComponents=WeakHashMap<Any,String>()
    private val rebinding=ThreadLocal<Boolean>()
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-generated-icons")
    private var running=false
    @Volatile private var epoch=0L
    @Volatile private var workEpoch=0L
    @Volatile private var active=false
    private var requestedActive=false
    private var bitmapField:Field?=null
    private var fastType:Class<*>?=null
    private var fastConstructor:Constructor<*>?=null
    private var fastIconColor:Field?=null
    private var infoType:Class<*>?=null
    private var itemType:Class<*>?=null
    private var itemBitmap:Field?=null
    private var infoIcon:Field?=null
    private var infoColor:Field?=null
    private var copyFields=emptyList<Field>()
    private var backdropRevision=Long.MIN_VALUE
    private var configured=false
    private fun main()=Looper.myLooper()==Looper.getMainLooper()
    private fun error(t:Throwable) {RuntimeDiagnostics.update("icon_assets_fallback","生成图标保留原生回退：${t.javaClass.simpleName}: ${t.message?.take(160)}")}
    fun setActive(enabled:Boolean) {requestedActive=enabled;active=enabled && configured;if(!active)restoreAndClear()}
    fun backdropChanged(revision:Long) {
        if(backdropRevision!=Long.MIN_VALUE && backdropRevision!=revision)restoreAndClear()
        backdropRevision=revision
    }
    fun cancelPending()=synchronized(lock) {workEpoch++;pending.clear();failed.clear()}
    fun restoreAndClear() {
        if(!main()) {ui.post {restoreAndClear()};return}
        synchronized(lock) {
            epoch++;workEpoch++;pending.clear();cache.clear();componentAssets.clear();floatingAssets.clear();failed.clear()
            drawables.forEach {(drawable,swap) ->runCatching {
                if(bitmapField?.get(drawable)===swap.rendered && !swap.source.isRecycled) {
                    bitmapField?.set(drawable,swap.source);drawable.invalidateSelf()
                }
            }}
            items.forEach {(item,swap) ->runCatching {if(swap.field.get(item)===swap.replacement)swap.field.set(item,swap.original)}}
            viewSwaps.forEach {(view,swap) ->runCatching {
                val current=if(view is ImageView)view.drawable else view.javaClass.getMethod("getIcon").invoke(view)
                if(current===swap.replacement) {
                    if(view is ImageView)view.setImageDrawable(swap.original)
                    else if(!IconGlassHostSlot.bind(view,swap.original))view.javaClass.getMethod("setIcon",Drawable::class.java).invoke(view,swap.original)
                }
            }}
            slotSwaps.forEach {(owner,swap) ->runCatching {
                if(swap.field.get(owner)===swap.replacement) {
                    swap.field.set(owner,swap.original);swap.original.invalidateSelf()
                }
            }}
            drawables.clear();items.clear();viewSwaps.clear();slotSwaps.clear()
            // Weak keys deliberately survive: clones/late animation results must still resolve
            // to their real original after a preference change. Values do not reference keys.
        }
    }
    fun current(bitmap:Bitmap)=synchronized(lock) {reverse[bitmap]?.let {it.epoch==epoch && active && it.variant==IconGlassDynamicIcons.variant(it.variant.substringBefore('|'))}==true}
    fun isGenerated(bitmap:Bitmap)=synchronized(lock) {reverse[bitmap]?.epoch==epoch && active}
    fun original(bitmap:Bitmap):Bitmap=synchronized(lock) {reverse[bitmap]?.source ?: bitmap}
    fun generated(bitmap:Bitmap):Bitmap?=if(active)available(bitmap) else null
    fun prefabFor(item:Any?):Bitmap? {
        if(!active)return null
        val id=identity(item) ?: return null
        return synchronized(lock) {componentAssets[id]}?.takeIf(::current)
    }
    fun identity(item:Any?):String?=runCatching {
        if(item==null)return@runCatching null
        val access=IconGlassIdentityAccess.forType(item.javaClass)
        val component=access.component?.invoke(item) as? android.content.ComponentName ?: return@runCatching null
        val user=access.user?.get(item) as? android.os.UserHandle
        val shortcut=runCatching {access.shortcut?.invoke(item) as? String}.getOrNull()
        component.flattenToString()+"@"+(user?.hashCode() ?: 0)+(shortcut?.takeIf {it.isNotEmpty()}?.let {"#"+it} ?: "")
    }.getOrNull()
    fun sourceForItem(item:Any?):Bitmap?=runCatching {
        if(item==null || itemType?.isInstance(item)!=true)return@runCatching null
        val info=itemBitmap?.get(item) ?: return@runCatching null
        val bitmap=infoIcon?.get(info) as? Bitmap ?: return@runCatching null
        original(bitmap).takeIf {!it.isRecycled && it.width>=16 && it.height>=16}
    }.getOrNull()
    fun bind(view:View,drawable:Drawable,item:Any?) {
        if(!active || !main() || rebinding.get()==true)return
        val id=identity(item) ?: return
        val info=runCatching {itemBitmap?.get(item)}.getOrNull()
        if(info!=null)synchronized(lock) {infoComponents[info]=id}
        val prefab=synchronized(lock) {componentAssets[id]}?.takeIf(::current) ?: return
        val source=sourceForItem(item) ?: return
        rebinding.set(true)
        try {apply(view,drawable,item,source,prefab)}finally {rebinding.remove()}
    }
    private fun available(bitmap:Bitmap):Bitmap?=synchronized(lock) {
        reverse[bitmap]?.takeIf {it.epoch==epoch && active && current(bitmap)}?.let {bitmap} ?: cache.entries.firstOrNull {
            it.value.source===bitmap && it.value.epoch==epoch && it.key.generation==bitmap.generationId && it.key.variant==IconGlassDynamicIcons.variant(it.key.variant.substringBefore('|'))
        }?.value?.rendered
    }
    fun request(source:Bitmap,mask:Bitmap,wallpaper:Bitmap,crop:RectF,params:IconGlassMaterial.Params,ready:()->Unit,identity:String?=null,sharpWallpaper:Bitmap=wallpaper):Bitmap? {
        if(!active || source.isRecycled || source.width<16 || source.height<16)return null
        val native=original(source)
        val fixed=params.copy(alpha=255,disabled=false)
        val key=Key(System.identityHashCode(native),native.generationId,fixed.hashCode(),IconGlassDynamicIcons.variant(identity))
        synchronized(lock) {
            if(identity!=null)componentAssets[identity]?.takeIf(::current)?.let {return it}
            cache[key]?.takeIf {it.source===native && it.epoch==epoch}?.let {return it.rendered}
            failed[key]?.let {if(SystemClock.uptimeMillis()<it)return null else failed.remove(key)}
            if(inFlight[key]==workEpoch)return null
            if(key !in pending) {
                if(pending.size>=96)pending.remove(pending.keys.first())
                pending[key]=Request(key,native,mask,wallpaper,RectF(crop),sharpWallpaper,fixed,ready,epoch,workEpoch,identity)
                IconGlassPipelineTrace.record(identity,"asset","queued")
            }
            if(!running) {running=true;try {worker.execute(::drain)}catch(t:Throwable) {running=false;pending.clear();error(t)}}
        }
        return null
    }
    private fun drain() {
        val renderer=IconGlassAssetRenderer.Session()
        try {
            while(true) {
                val request=synchronized(lock) {
                    val entry=pending.entries.firstOrNull() ?: return
                    pending.remove(entry.key);inFlight[entry.key]=entry.value.workEpoch;entry.value
                }
                var rendered:Bitmap?=null
                var publicationPending=false
                try {
                    if(request.epoch!=epoch || request.workEpoch!=workEpoch || !active)continue
                    rendered=renderer.render(request.mask,request.wallpaper,request.crop,request.params,
                        request.source.width.coerceIn(48,192),request.source.height.coerceIn(48,192),request.sharpWallpaper)
                    val result=rendered
                    val accepted=ui.post {
                        synchronized(lock) {
                            if(inFlight[request.key]==request.workEpoch)inFlight.remove(request.key)
                            if(active && request.epoch==epoch && request.workEpoch==workEpoch) {
                                val asset=Asset(request.source,result,request.epoch)
                                cache[request.key]=asset;reverse[result]=Original(request.source,request.epoch,request.key.variant)
                                request.identity?.let {componentAssets[it]=result}
                                IconGlassPipelineTrace.record(request.identity,"asset","published ${result.width}x${result.height}")
                                while(componentAssets.size>192)componentAssets.remove(componentAssets.keys.first())
                                while(cache.size>64)cache.remove(cache.keys.first())
                                RuntimeWorkPolicy.guarded(::error) {request.ready()}
                            } else result.recycle()
                        }
                    }
                    publicationPending=accepted
                    if(!accepted)rendered.recycle()
                    rendered=null
                } catch(t:Throwable) {
                    IconGlassPipelineTrace.record(request.identity,"asset","failed ${t.javaClass.simpleName}: ${t.message}")
                    rendered?.recycle()
                    synchronized(lock) {if(request.epoch==epoch) {
                        failed[request.key]=SystemClock.uptimeMillis()+5000L
                        while(failed.size>64)failed.remove(failed.keys.first())
                    }}
                    RuntimeWorkPolicy.guarded({}) {error(t)}
                } finally {if(!publicationPending)synchronized(lock) {if(inFlight[request.key]==request.workEpoch)inFlight.remove(request.key)}}
            }
        } finally {
            runCatching {renderer.close()}
            synchronized(lock) {
                running=false
                if(pending.isNotEmpty()) {running=true;try {worker.execute(::drain)}catch(_:Throwable) {running=false;pending.clear()}}
            }
        }
    }
    private fun swapDrawable(drawable:Drawable,source:Bitmap,rendered:Bitmap) {
        if(fastType?.isInstance(drawable)!=true)return
        bitmapField!!.set(drawable,rendered)
        synchronized(lock) {drawables[drawable]=swap(drawable,original(source),rendered)}
    }
    private fun newNativeDrawable(bitmap:Bitmap,original:Drawable):Drawable {
        val constructor=checkNotNull(fastConstructor)
        if(constructor.parameterCount==1)return constructor.newInstance(bitmap) as Drawable
        val color=if(fastType?.isInstance(original)==true)fastIconColor?.getInt(original) ?: 0 else 0
        return constructor.newInstance(bitmap,color) as Drawable
    }
    /** Folder preview params are drawable slots, not Views. Keep each stacked child separate. */
    fun applySlot(view:View,owner:Any,field:Field,drawable:Drawable,item:Any?,source:Bitmap,rendered:Bitmap):Drawable {
        if(!active || !main())return drawable
        val target=if(fastType?.isInstance(drawable)==true)drawable else {
            val replacement=newNativeDrawable(rendered,drawable)
            replacement.bounds=drawable.bounds;replacement.alpha=drawable.alpha
            replacement.state=drawable.state;replacement.level=drawable.level
            replacement.callback=drawable.callback
            val first=slotSwaps[owner]?.original ?: drawable
            field.set(owner,replacement);slotSwaps[owner]=SlotSwap(field,first,replacement)
            replacement
        }
        apply(view,target,item,source,rendered)
        return target
    }
    private fun swap(drawable:Drawable,source:Bitmap,rendered:Bitmap):Swap {
        val base=find(drawable.javaClass,"mPaint")?.get(drawable) as? Paint
        val alpha=find(drawable.javaClass,"mMonoPaint")?.get(drawable) as? Paint ?: base
        return Swap(source,rendered,alpha,base)
    }
    fun hostSlotBound(view:View,drawable:Drawable,rendered:Bitmap):Boolean =
        viewSwaps[view]?.replacement===drawable && fastType?.isInstance(drawable)==true && bitmapField?.get(drawable)===rendered

    fun apply(view:View,drawable:Drawable,item:Any?,source:Bitmap,rendered:Bitmap) {
        if(!active || !main())return
        if(IconGlassHostSlot.supported(view)) {
            val current=viewSwaps[view]
            if(current?.replacement!==drawable || bitmapField?.get(drawable)!==rendered) {
                val next=newNativeDrawable(rendered,drawable)
                next.alpha=drawable.alpha;next.state=drawable.state;next.level=drawable.level
                next.bounds=android.graphics.Rect(drawable.bounds);next.layoutDirection=drawable.layoutDirection
                // Copy only native foreground state, not OEM cover/picture/cache fields.
                for(name in listOf("mIsDisabled","mDisabledAlpha","mIsShowBadge","mBadgeOffsetX","mBadgeOffsetY","mBadgeSize"))
                    find(fastType!!,name)?.let {field ->runCatching {field.set(next,field.get(drawable))}}
                find(fastType!!,"mBadge")?.let {field ->runCatching {
                    val badge=field.get(drawable) as? Drawable
                    field.set(next,badge?.constantState?.newDrawable(view.resources)?.mutate() ?: badge)
                }}
                swapDrawable(next,source,rendered)
                val original=if(current?.replacement===drawable)current.original else drawable
                IconGlassHostSlot.bind(view,next)
                viewSwaps[view]=ViewSwap(original,next)
                RuntimeDiagnostics.mark("icon_host_slot_bound","已重绑 mIcon 与原生 compound/图标节点；不依赖绘制 Hook")
            }
        } else if(fastType?.isInstance(drawable)==true) {
            if(bitmapField?.get(drawable)!==rendered)swapDrawable(drawable,source,rendered)
        }
        else if(view is ImageView || runCatching {view.javaClass.getMethod("setIcon",Drawable::class.java)}.isSuccess) {
            // Preserve CENTER/CENTER_INSIDE sizing, including density/padding encoded by
            // OEM engine drawables. A fresh bitmap's default density must not move/crop it.
            if((drawable as? BitmapDrawable)?.bitmap!==rendered || viewSwaps[view]?.replacement!==drawable) {
                val next:Drawable=if(view is ImageView)NativeSizedBitmap(view.resources,rendered,drawable.intrinsicWidth,drawable.intrinsicHeight)
                    .apply {alpha=drawable.alpha} else newNativeDrawable(rendered,drawable)
                if(fastType?.isInstance(next)==true)swapDrawable(next,source,rendered)
                val action:(Drawable)->Unit=if(view is ImageView)({view.setImageDrawable(it)}) else ({d ->
                    view.javaClass.getMethod("setIcon",Drawable::class.java).invoke(view,d);Unit
                })
                val first=viewSwaps[view]?.original ?: drawable
                action(next);viewSwaps[view]=ViewSwap(first,next)
            }
        }
        primeItem(item,rendered)
        RuntimeDiagnostics.mark("icon_assets_applied","已替换运行时图标位图与ItemInfo缓存；原始APK/图标包未修改")
    }
    /** Seed the model cache while the folder is closed, before animation consumers bind. */
    fun primeItem(item:Any?,rendered:Bitmap) {
        if(!active || !main())return
        if(item!=null && itemType?.isInstance(item)==true) {
            val field=itemBitmap ?: return
            val current=field.get(item) ?: return
            val native=synchronized(lock) {infoOrigins[current] ?: current}
            val replacement=if(infoIcon?.get(current)===rendered)current else infoType!!.getConstructor(Bitmap::class.java,Int::class.javaPrimitiveType)
                .newInstance(rendered,infoColor!!.getInt(native))
            if(replacement!==current) {
                copyFields.forEach {it.set(replacement,it.get(native))}
                // Do not retain the platform monochrome layer: newIcon must use our generated bitmap.
                synchronized(lock) {
                    infoOrigins[replacement]=native
                    field.set(item,replacement)
                    items[item]=ItemSwap(field,native,replacement)
                }
            }
            identity(item)?.let {id ->synchronized(lock) {
                infoComponents[native]=id;infoComponents[replacement]=id;componentAssets[id]=rendered
                while(componentAssets.size>192)componentAssets.remove(componentAssets.keys.first())
            }}
        }
    }
    /** Native FastBitmapDrawable.draw still owns press scale, translation and badges. */
    fun drawCached(drawable:Drawable,canvas:Canvas,rect:Rect):Boolean {
        if(fastType?.isInstance(drawable)!=true)return false
        val bitmap=bitmapField!!.get(drawable) as? Bitmap ?: return false
        val asset=synchronized(lock) {reverse[bitmap]} ?: return false
        if(!active || asset.epoch!=epoch) {
            if(!asset.source.isRecycled)bitmapField!!.set(drawable,asset.source)
            return false
        }
        val state=synchronized(lock) {drawables[drawable] ?: swap(drawable,asset.source,bitmap).also {drawables[drawable]=it}}
        synchronized(state) {
            state.drawing.alpha=state.nativeAlpha?.alpha ?: drawable.alpha
            if(state.drawing.alpha<=0)return true
            state.drawing.colorFilter=state.nativeFilter?.colorFilter
            canvas.drawBitmap(bitmap,null,rect,state.drawing)
        }
        return true
    }
    internal fun configure(fast:Class<*>,info:Class<*>,item:Class<*>) {
        check(Drawable::class.java.isAssignableFrom(fast))
        val mutable=find(fast,"mBitmap") ?: error("Missing mutable native bitmap")
        check(mutable.type==Bitmap::class.java && !java.lang.reflect.Modifier.isFinal(mutable.modifiers))
        val icon=find(info,"icon") ?: error("Missing bitmap info source")
        val color=find(info,"color") ?: error("Missing bitmap info colour")
        val itemSource=find(item,"bitmap") ?: error("Missing item bitmap info")
        check(icon.type==Bitmap::class.java && itemSource.type==info)
        info.getConstructor(Bitmap::class.java,Int::class.javaPrimitiveType)
        val metadata=listOf("flags","badgeInfo").map {find(info,it) ?: error("Missing native metadata $it")}
        fastConstructor=IconGlassNativeFactories.drawableConstructor(fast,Bitmap::class.java)
        fastIconColor=find(fast,"mIconColor")?.takeIf {it.type==Int::class.javaPrimitiveType}
        fastType=fast;infoType=info;itemType=item;bitmapField=mutable
        // ColorOS 16 keeps the user on ItemInfo and passes it to ContentWriter.
        infoIcon=icon;infoColor=color;itemBitmap=itemSource
        copyFields=metadata+listOfNotNull(find(info,"mUserHandle"));configured=true
        active=requestedActive
        RuntimeDiagnostics.mark("icon_assets_configured","原生位图缓存字段已验证，准备器可用")
    }
    fun install(module:XposedInterface,loader:ClassLoader,ensureDrawable:(Class<*>)->Unit) {
        runCatching {
            val fast=Class.forName("com.android.launcher3.icons.FastBitmapDrawable",false,loader)
            val info=Class.forName("com.android.launcher3.icons.BitmapInfo",false,loader)
            val item=Class.forName("com.android.launcher3.model.data.ItemInfoWithIcon",false,loader)
            // Resolve persistence protection before enabling ItemInfo replacements.
            val forDatabase=item.declaredMethods.firstOrNull {
                it.name=="getIconForDatabase" && it.returnType==info && it.parameterTypes.contentEquals(arrayOf(info))
            }
            if(forDatabase!=null) {
                module.hook(forDatabase).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed();synchronized(lock) {infoOrigins[result]} ?: result
                }
            } else {
                val writer=Class.forName("com.android.launcher3.util.ContentWriter",false,loader)
                val putIcon=writer.getDeclaredMethod("putIcon",info,android.os.UserHandle::class.java)
                check(putIcon.returnType==writer)
                module.hook(putIcon).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val source=synchronized(lock) {infoOrigins[chain.getArg(0)]}
                    if(source==null)chain.proceed() else chain.proceed(arrayOf(source,chain.getArg(1)))
                }
                // ART may inline the short putIcon setter into the launcher model writer.
                runCatching {
                    val workspace=Class.forName("com.android.launcher3.model.data.WorkspaceItemInfo",false,loader)
                    module.deoptimize(workspace.getDeclaredMethod("onAddToDatabase",writer))
                }.onFailure(::error)
            }
            module.hook(info.getDeclaredMethod("canPersist")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val bitmap=infoIcon?.get(chain.getThisObject()) as? Bitmap
                if(bitmap!=null && synchronized(lock) {reverse.containsKey(bitmap)})false else chain.proceed()
            }
            configure(fast,info,item)
            val factories=IconGlassNativeFactories.bitmapInfoFactories(info,Context::class.java,fast)
            if(factories.isEmpty())RuntimeDiagnostics.mark("icon_assets_factory_missing","桌面未提供已知的 BitmapInfo 图标工厂")
            for(factory in factories)module.hook(factory)
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    if(result is Drawable)runCatching {
                        val bitmap=infoIcon!!.get(chain.getThisObject()) as? Bitmap ?: return@runCatching
                        val source=original(bitmap)
                        val component=synchronized(lock) {infoComponents[chain.getThisObject()]}
                        val cached=if(active)component?.let {synchronized(lock) {componentAssets[it]}}?.takeIf(::current)
                            ?: available(bitmap) ?: available(source) else null
                        if(cached!=null) {
                            if(bitmapField!!.get(result)!==cached)swapDrawable(result,source,cached)
                        }
                        else if(source!==bitmap && !source.isRecycled)bitmapField!!.set(result,source)
                        if(main())ensureDrawable(result.javaClass)
                    }.onFailure(::error)
                    result
                }
            runCatching {
                val iconCache=Class.forName("com.android.launcher3.icons.IconCache",false,loader)
                val apply=iconCache.declaredMethods.first {it.name=="applyCacheEntry" && it.parameterCount==2 && it.parameterTypes[1]==item}
                module.hook(apply).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    runCatching {
                        val owner=chain.getArg(1) ?: return@runCatching
                        val id=identity(owner) ?: return@runCatching
                        val bitmapInfo=itemBitmap!!.get(owner) ?: return@runCatching
                        synchronized(lock) {infoComponents[bitmapInfo]=id}
                    }.onFailure(::error)
                    result
                }
            }.onFailure(::error)
            val floating=Class.forName("com.android.launcher3.views.FloatingIconView",false,loader)
            val loadResult=find(floating,"mIconLoadResult") ?: error("Missing floating icon result")
            floating.declaredMethods.firstOrNull {it.name=="fetchIcon" && it.parameterCount==4 && it.parameterTypes[1]==View::class.java}
                ?.let {fetch ->module.hook(fetch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    if(active && result!=null)runCatching {
                        val owner=chain.getArg(2)
                        val info=if(owner!=null && item.isInstance(owner))itemBitmap!!.get(owner) else null
                        val icon=if(info!=null)infoIcon!!.get(info) as? Bitmap else null
                        val viewBitmap=((chain.getArg(1) as? ImageView)?.drawable as? BitmapDrawable)?.bitmap
                        val rendered=prefabFor(owner) ?: icon?.let(::available) ?: viewBitmap?.let(::available)
                        if(rendered!=null)synchronized(lock) {floatingAssets[result]=rendered}
                    }.onFailure(::error)
                    result
                }}
            module.hook(floating.getDeclaredMethod("setIcon",Drawable::class.java,Drawable::class.java,Drawable::class.java,Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    var args:Array<Any?>?=null
                    if(active)runCatching {
                        val native=chain.getArg(2) as? Drawable
                        val bitmap=if(native!=null && fast.isInstance(native))bitmapField!!.get(native) as? Bitmap else null
                        val cached=bitmap?.let(::available) ?: synchronized(lock) {floatingAssets[loadResult.get(chain.getThisObject())]}
                            ?.takeIf(::current) ?: return@runCatching
                        val context=(chain.getThisObject() as? View)?.context ?: return@runCatching
                        val replacement=AdaptiveIconDrawable(BitmapDrawable(context.resources,cached),ColorDrawable(Color.TRANSPARENT))
                        args=chain.getArgs().toTypedArray().also {it[0]=replacement;it[3]=0}
                        RuntimeDiagnostics.mark("icon_assets_animation","应用切换浮动图标已复用生成的玻璃位图")
                    }.onFailure(::error)
                    if(args!=null)chain.proceed(args!!) else chain.proceed()
                }
            RuntimeDiagnostics.mark("icon_assets_contract","运行时BitmapInfo、新图标工厂及FloatingIconView入口已安装；仅内存替换")
        }.onFailure(::error)
    }
    private fun find(type:Class<*>,name:String):Field? {
        var current:Class<*>?=type
        while(current!=null) {
            runCatching {current!!.getDeclaredField(name).apply {isAccessible=true}}.getOrNull()?.let {return it}
            current=current.superclass
        }
        return null
    }
}

/** One GPU rendering/readback per prepared asset; final native bitmap is software ARGB_8888
 * so vendor icon caching/animation helpers can safely inspect it. No screen reads occur. */
internal object IconGlassAssetRenderer {
    fun render(mask:Bitmap,wallpaper:Bitmap,crop:RectF,params:IconGlassMaterial.Params,width:Int,height:Int):Bitmap =
        Session().use {it.render(mask,wallpaper,crop,params,width,height)}

    /** Thread-confined batch resources, released when the queue drains. No idle renderer or
     * repeating work. Reuse also avoids compiling the same AGSL program for every folder item. */
    class Session:AutoCloseable {
        private var reader:ImageReader?=null
        private var renderer:HardwareRenderer?=null
        private var node:RenderNode?=null
        private val material=IconGlassMaterial()
        private var width=0
        private var height=0
        private fun configure(w:Int,h:Int) {
            if(reader!=null && width==w && height==h)return
            closeSurface()
            val nextReader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader=nextReader
            val nextRenderer=HardwareRenderer();renderer=nextRenderer
            val nextNode=RenderNode("DreamGlass cached icon batch");node=nextNode
            nextRenderer.isOpaque=false;nextRenderer.setSurface(nextReader.surface);nextRenderer.setContentRoot(nextNode)
            nextNode.setPosition(0,0,w,h);width=w;height=h
        }
        fun render(mask:Bitmap,wallpaper:Bitmap,crop:RectF,params:IconGlassMaterial.Params,width:Int,height:Int,sharpWallpaper:Bitmap=wallpaper):Bitmap {
        if(params.customImage) {
            val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(output)
            canvas.clipPath(Path().apply {addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),width*.235f,height*.235f,Path.Direction.CW)})
            canvas.drawBitmap(mask,null,Rect(0,0,width,height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        }
        configure(width,height)
        val reader=checkNotNull(reader);val renderer=checkNotNull(renderer);val node=checkNotNull(node)
        try {
            val canvas=node.beginRecording(width,height)
            try {
                canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR)
                check(material.draw(canvas,RectF(0f,0f,width.toFloat(),height.toFloat()),mask,wallpaper,crop,params,sharpWallpaper))
            } finally {node.endRecording()}
            val status=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED)==0)
            val image=reader.acquireNextImage() ?: error("No generated icon output")
            try {
                val buffer=image.hardwareBuffer ?: error("No generated icon buffer")
                try {
                    val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB)) ?: error("Cannot wrap generated icon")
                    return try {hardware.copy(Bitmap.Config.ARGB_8888,false) ?: error("Cannot prepare native icon bitmap")}finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        } catch(error:Throwable) {
            // An unhealthy surface cannot affect the next item in the batch.
            closeSurface();throw error
        }
        }
        private fun closeSurface() {
            try {renderer?.destroy()} finally {
                renderer=null
                try {reader?.close()} finally {reader=null;node?.discardDisplayList();node=null;width=0;height=0}
            }
        }
        override fun close() {try {closeSurface()} finally {material.clear()}}
    }
}
