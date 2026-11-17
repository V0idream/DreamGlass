/* DreamGlass contributors, 2026-11-13. GPL-3.0. */
package com.coloros16.liquidglass.hook

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.ImageView
import android.view.ViewGroup
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Binds generated bitmap slots before OEM consumers, with a View-slot compositor fallback.
 * Native TextView still draws text and focus decoration. Shared animation consumers retain
 * the generated bitmap when there is no live View surface.
 */
internal object LauncherIconGlassHook {
    private data class Contract(val host:Class<*>,val icon:Field,val display:Field,
        val fast:Class<*>,val bitmap:Field,val paint:Field?,val disabled:Method?,
        val bounds:Method?,val visible:Method?)
    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        val host=WeakReference(view)
        var observer=WeakReference(view.viewTreeObserver)
        val inverse=Matrix();val current=Matrix();val global=Matrix();val combined=Matrix()
        val screenRect=RectF();val bounds=RectF();val visibleRect=Rect()
        val materials=WeakHashMap<Drawable,IconGlassMaterial>()
        val artwork=WeakHashMap<Drawable,Artwork>()
        private val motionRect=RectF()
        private val lastMotion=RectF(Float.NaN,0f,0f,0f)
        var ancestryParent=WeakReference<View>(null);var ancestryRoot=WeakReference<View>(null);var ancestryRevision=-1
        var blocked=false;var desktop=false;var suggestion=false
        var engine=WeakReference<Any>(null)
        var tag:Any?=null;var container=Long.MIN_VALUE;var component:ComponentName?=null;var userId=0
        var signature=0;var revision=-1
        override fun onPreDraw():Boolean {
            val view=host.get() ?: run {
                observer.get()?.let {if(it.isAlive)it.removeOnPreDrawListener(this)}
                return true
            }
            if(!view.isAttachedToWindow)return true
            preferences()
            if(contract?.host?.isInstance(view)==true)runCatching {
                val qualified=enabled && inspect(view,this)
                if(qualified) {
                    (contract?.icon?.get(view) as? Drawable)?.let {prepareNative(view,this,it)}
                    // prepareNative already syncs the live effect; avoid a second transform,
                    // mask lookup and compositor check for every child in an expanding folder.
                    if(!liveMode)IconGlassLiveSurface.restore(view)
                } else IconGlassLiveSurface.restore(view)
                val packageName=component?.packageName
                if(packageName=="com.codex.floatingcamera" || packageName=="me.sukimon.mdl") {
                    val icon=contract?.icon?.get(view) as? Drawable
                    val bitmap=if(icon!=null && contract?.fast?.isInstance(icon)==true)contract?.bitmap?.get(icon) as? Bitmap else null
                    RuntimeDiagnostics.update("icon_example_"+component?.packageName,
                        "eligible=$qualified container=$container display=${contract?.display?.getInt(view)} drawable=${icon?.javaClass?.simpleName} generated=${bitmap?.let(LauncherIconGlassAssets::isGenerated)} live=${IconGlassLiveSurface.owns(view)}")
                }
            }.onFailure(::report)
            var dirty=revision!=prefsRevision
            revision=prefsRevision
            // The page scroll/3D transform belongs to ancestors, not view.left/translationX.
            // Native pre-draw callbacks run only for requested frames; no idle animation loop.
            if(enabled && liveMode && !IconGlassLiveSurface.owns(view) && view.isShown && view.windowVisibility==View.VISIBLE) {
                power(view.context)
                if(interactive && !locked && !powerSave) {
                    global.reset();view.transformMatrixToGlobal(global)
                    motionRect.set(0f,0f,view.width.toFloat(),view.height.toFloat());global.mapRect(motionRect)
                    if(IconGlassMotionPolicy.changed(lastMotion.left,lastMotion.top,lastMotion.right,lastMotion.bottom,
                            motionRect.left,motionRect.top,motionRect.right,motionRect.bottom)) {
                        lastMotion.set(motionRect);dirty=true
                    }
                }
            }
            if(dirty)view.invalidate()
            return true
        }
        override fun onViewAttachedToWindow(v:View) {
            observer=WeakReference(v.viewTreeObserver)
            if(v.viewTreeObserver.isAlive)v.viewTreeObserver.addOnPreDrawListener(this)
            v.postInvalidateOnAnimation()
        }
        override fun onViewDetachedFromWindow(v:View) {
            if(v.viewTreeObserver.isAlive)v.viewTreeObserver.removeOnPreDrawListener(this)
            IconGlassLiveSurface.restore(v)
            materials.values.forEach {it.clear()};materials.clear();artwork.clear();tag=null
            ancestryParent.clear();ancestryRoot.clear();ancestryRevision=-1;engine.clear()
        }
    }
    private data class Artwork(val source:Bitmap,val generation:Int,val mask:IconGlassMasks.Prepared,val variant:String)
    private data class Metadata(val component:ComponentName?,val user:Int,val container:Long)
    private data class Draw(val state:State,val drawable:Drawable,val canvas:Canvas,val base:Matrix,
        val item:Any?=null,val folderPreview:Boolean=false,val source:Bitmap?=null,val suggestionImage:Boolean=false,
        val component:ComponentName?=state.component,val userId:Int=state.userId)
    private val owner=ScopedDrawOwner<Draw>()
    private class FolderDraw(val host:View,val canvas:Canvas,val base:Matrix) {
        val slots=java.util.IdentityHashMap<Drawable,Draw>()
        var prefabsReady=false
    }
    private val folderOwner=ScopedDrawOwner<FolderDraw>()
    private fun drawOwner(drawable:Drawable?,canvas:Canvas?):Draw? {
        val scope=owner.current()
        if(scope?.drawable===drawable && scope?.canvas===canvas)return scope
        return folderOwner.current()?.takeIf {it.canvas===canvas}?.slots?.get(drawable)
    }
    private val hosts=WeakHashMap<View,State>() // UI thread only; values retain views weakly.
    private val itemMetadata=WeakHashMap<Any,Metadata>()
    private var scopeRevision=0
    private val nativeEngines=WeakHashMap<Any,WeakReference<View>>()
    private val nativeEngineMethods=HashSet<Method>()
    private val ui=Handler(Looper.getMainLooper())
    private var api:XposedInterface?=null
    private var contract:Contract?=null
    private var mounted=false
    private var readAt=-1000L
    private var enabled=false
    private var liveMode=false
    private var params=IconGlassMaterial.Params()
    private var prefsRevision=0
    private var powerAt=-1000L
    private var interactive=false
    private var locked=true
    private var powerSave=false
    private var lastErrorAt=-10_000L
    private var rendered=false
    private var screenReceiverAttempted=false
    private var invalidationPending=false
    private val hookedDraws=HashSet<Method>()
    private val drawableTypes=HashSet<Class<*>>()
    private val suggestionImages=WeakHashMap<ImageView,Boolean>()
    private val suggestionTexts=WeakHashMap<TextView,Boolean>()
    private val suggestionSurfaces=WeakHashMap<View,Drawable>()
    private val engineMethods=HashSet<Method>()
    private val suggestionRoots=WeakHashMap<View,EngineState>()
    private val verifiedSuggestionCards=WeakHashMap<View,Boolean>()
    private val suggestionReady=WeakHashMap<View,Boolean>()
    private fun suggestionRendered(view:View) {
        var node:View?=view
        repeat(32) {
            val current=node ?: return
            if(verifiedSuggestionCards[current]==true || isTitleCard(current)) {
                if(suggestionReady.put(current,true)!=true)current.postInvalidateOnAnimation()
                return
            }
            node=current.parent as? View
        }
    }
    private val artworkReady:()->Unit={
        ui.post {
            RuntimeWorkPolicy.guarded(::report) {LauncherIconGlassAssets.backdropChanged(IconGlassWallpaper.revision())}
            invalidateHosts()
        };Unit
    }
    private val prefListener=SharedPreferences.OnSharedPreferenceChangeListener {_,_ ->
        ui.post {runCatching {preferences(true)}.onFailure(::report)}
    }

    private fun main()=Looper.myLooper()==Looper.getMainLooper()
    private fun report(error:Throwable) {
        val now=SystemClock.uptimeMillis()
        if(now-lastErrorAt<5000L)return
        lastErrorAt=now
        RuntimeDiagnostics.update("icon_glass_fallback","图标保留原生绘制：${error.javaClass.simpleName}: ${error.message?.take(160)}")
        Lg.w("DG-IconGlass","icon material keeps native fallback",error)
    }
    private fun preferences(force:Boolean=false) {
        val now=SystemClock.uptimeMillis();if(!force && now-readAt<500L)return
        readAt=now
        val a=api ?: return
        try {
            val p=Prefs.read(a)
            val master=p.getBoolean(Prefs.KEY_MASTER,false)
            val glass=p.getBoolean(Prefs.KEY_GLASS_ENABLE,true)
            val feature=p.getBoolean(Prefs.KEY_ICON_GLASS_ENABLE,false)
            val member=Prefs.memberAllowed(a)
            val next=IconGlassPolicy.enabled(master,glass,feature,member)
            RuntimeDiagnostics.update("icon_glass_gate","master=$master glass=$glass feature=$feature member=$member enabled=$next")
            val libraryChanged=IconGlassLibraryRuntime.configure(p.getString(com.coloros16.liquidglass.icons.IconLibrary.REVISION,"") ?: "")
            if(libraryChanged) {IconGlassMasks.clear();LauncherIconGlassAssets.restoreAndClear()}
            IconGlassWallpaper.configureBlur(Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_BLUR,Prefs.DEFAULT_ICON_GLASS_BLUR))
            val nextLive=IconGlassMode.preferLive(p.getString(Prefs.KEY_ICON_GLASS_MODE,Prefs.DEFAULT_ICON_GLASS_MODE))
            val nextParams=IconGlassMaterial.Params(
                clarity=Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_CLARITY,76).coerceIn(0,100)/100f,
                refraction=Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_REFRACTION,56).coerceIn(0,100)/100f,
                thickness=Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_THICKNESS,45).coerceIn(0,100)/100f,
                keepColor=p.getBoolean(Prefs.KEY_ICON_GLASS_KEEP_COLOR,true))
            if(libraryChanged || next!=enabled || nextParams!=params || nextLive!=liveMode) {
                IconGlassLiveSurface.clear()
                if(nextParams!=params && enabled)LauncherIconGlassAssets.restoreAndClear()
                LauncherIconGlassAssets.setActive(next)
                val wasEnabled=enabled;enabled=next;params=nextParams;liveMode=nextLive;prefsRevision++
                if(!next && wasEnabled) {
                    IconGlassImageClip.clear();IconGlassMasks.clear();IconGlassWallpaper.clear();IconGlassRasterSources.clear();IconGlassRecordingSources.clear()
                    hosts.values.forEach {it.materials.values.forEach {material -> material.clear()};it.materials.clear()}
                }
                if(next && !wasEnabled)LauncherHook.syncIconWallpaperTransform()
                invalidateHosts()
            }
        } catch(t:Throwable) {
            if(enabled) {
                IconGlassLiveSurface.clear()
                enabled=false;prefsRevision++
                LauncherIconGlassAssets.setActive(false)
                IconGlassImageClip.clear();IconGlassMasks.clear();IconGlassWallpaper.clear();IconGlassRasterSources.clear();IconGlassRecordingSources.clear()
                hosts.values.forEach {it.materials.values.forEach {material -> material.clear()};it.materials.clear()};invalidateHosts()
            }
            report(t)
        }
    }
    private fun power(context:Context) {
        val now=SystemClock.uptimeMillis();if(now-powerAt<250L)return
        powerAt=now
        val manager=context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        interactive=manager?.isInteractive==true;powerSave=manager?.isPowerSaveMode!=false
        locked=(context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked!=false
    }
    private fun state(view:View):State=hosts[view] ?: State(view).also {
        hosts[view]=it;view.addOnAttachStateChangeListener(it)
        if(view.viewTreeObserver.isAlive)view.viewTreeObserver.addOnPreDrawListener(it)
    }
    private fun metadata(item:Any):Metadata=itemMetadata[item] ?: run {
        val component=runCatching {item.javaClass.getMethod("getTargetComponent").invoke(item) as? ComponentName}.getOrNull()
        val user=runCatching {(field(item.javaClass,"user")?.get(item) as? UserHandle)?.hashCode() ?: 0}.getOrDefault(0)
        val container=runCatching {(field(item.javaClass,"container")?.get(item) as? Number)?.toLong() ?: Long.MIN_VALUE}.getOrDefault(Long.MIN_VALUE)
        Metadata(component,user,container).also {itemMetadata[item]=it}
    }
    private fun inspect(view:View,state:State,item:Any?=view.tag,folderPreview:Boolean=false,suggestionImage:Boolean=false):Boolean {
        val c=contract ?: return false
        if(!enabled)return false
        val tag=item ?: return false
        if(state.tag!==tag) {
            val meta=metadata(tag)
            state.tag=tag;state.container=meta.container;state.component=meta.component;state.userId=meta.user
        }
        val direct=view.parent as? View;val root=view.rootView
        if(state.ancestryParent.get()!==direct || state.ancestryRoot.get()!==root || state.ancestryRevision!=scopeRevision) {
            state.ancestryParent=WeakReference(direct);state.ancestryRoot=WeakReference(root);state.ancestryRevision=scopeRevision
            state.blocked=false;state.desktop=false;state.suggestion=false
            var parent=direct;var depth=0
            while(parent!=null && depth++<32) {
                val node=parent
                if(IconGlassPolicy.blockedParent(node.javaClass.name))state.blocked=true
                if(node.javaClass.name=="com.android.launcher3.OplusHotseat" || node.javaClass.name=="com.android.launcher3.Hotseat")state.desktop=true
                if(verifiedSuggestionCards[node]==true || isTitleCard(node))state.suggestion=state.suggestion || verifiedSuggestionCards[node]==true || isSuggestionCard(node)
                parent=node.parent as? View
            }
        }
        power(view.context)
        val shown=view.isAttachedToWindow && view.isShown && view.windowVisibility==View.VISIBLE &&
            view.alpha>0f && view.display?.state==Display.STATE_ON && view.getGlobalVisibleRect(state.visibleRect)
        if(suggestionImage && !state.suggestion)return false
        val display=if(folderPreview || suggestionImage)0 else c.display.getInt(view)
        return IconGlassPolicy.eligible(display,state.container,shown,interactive,locked,powerSave,state.blocked,
            state.desktop,folderPreview,state.suggestion)
    }

    private fun isTitleCard(view:View):Boolean {
        var type:Class<*>?=view.javaClass
        while(type!=null) {if(type.name=="com.android.launcher3.card.TitleCardView")return true;type=type.superclass}
        return false
    }
    private fun isSuggestionCard(view:View):Boolean=IconGlassSuggestionContract.matches(view)
    private fun invalidateHosts() {
        if(!main()) {ui.post {invalidateHosts()};return}
        if(invalidationPending)return
        invalidationPending=true
        android.view.Choreographer.getInstance().postFrameCallback {
            invalidationPending=false
            hosts.keys.toList().forEach {view ->
                if(view.isAttachedToWindow && view.isShown && view.windowVisibility==View.VISIBLE)
                    view.invalidate()
            }
        }
    }

    /** Existing launcher wallpaper events provide coordinates, not screenshots. */
    fun wallpaperTransform(x:Float,y:Float,zoom:Float) {
        if(enabled)IconGlassWallpaper.onTransform(x,y,zoom)
    }

    fun install(module:XposedInterface,loader:ClassLoader) {
        if(mounted || Build.VERSION.SDK_INT<33)return
        api=module
        try {
            val host=Class.forName("com.android.launcher3.BubbleTextView",false,loader)
            val fast=Class.forName("com.android.launcher3.icons.FastBitmapDrawable",false,loader)
            check(TextView::class.java.isAssignableFrom(host) && Drawable::class.java.isAssignableFrom(fast))
            val icon=field(host,"mIcon") ?: error("missing icon slot")
            val display=field(host,"mDisplay") ?: error("missing display scope")
            val bitmap=field(fast,"mBitmap") ?: error("missing native bitmap")
            check(Drawable::class.java.isAssignableFrom(icon.type) && display.type==Int::class.javaPrimitiveType &&
                Bitmap::class.java.isAssignableFrom(bitmap.type))
            contract=Contract(host,icon,display,fast,bitmap,field(fast,"mPaint"),
                fast.methods.firstOrNull {it.name=="isDisabled" && it.parameterCount==0},
                runCatching {host.getMethod("getIconBounds",Rect::class.java)}.getOrNull(),
                runCatching {host.getMethod("getIconVisible")}.getOrNull())
            val draw=fast.getDeclaredMethod("drawInternal",Canvas::class.java,Rect::class.java)
            check(draw.returnType==Void.TYPE)
            mountSlot(module,draw)
            runCatching {module.deoptimize(fast.getDeclaredMethod("draw",Canvas::class.java))}.onFailure(::report)
            IconGlassLiveSurface.install(module,loader)
            mountHost(module,host)
            // ART can inline a BubbleTextView onDraw into its framework caller. This filtered
            // outer entry also prepares native cache assets when drawInternal is bypassed.
            mountHost(module,View::class.java,listOf("draw"))
            // An override that bypasses the base onDraw still gets its own scoped owner.
            for(name in listOf("com.android.launcher3.OplusBubbleTextView","com.android.launcher3.OplusStackedBubbleTextView"))
                runCatching {mountHost(module,Class.forName(name,false,loader))}
            runCatching {module.hook(host.getDeclaredMethod("setIcon",Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    if(main() && !IconGlassHostSlot.isBinding())runCatching {
                        val view=chain.getThisObject() as? View ?: return@runCatching
                        val registered=state(view)
                        ensureNativeEngine(view,registered)
                        (chain.getArg(0) as? Drawable)?.let {mountDrawableOverride(module,it.javaClass)}
                        (chain.getArg(0) as? Drawable)?.let {LauncherIconGlassAssets.bind(view,it,view.tag)}
                        hosts[view]?.materials?.let {m -> m.values.forEach {it.clear()};m.clear()};view.postInvalidateOnAnimation()
                    }.onFailure(::report)
                    result
                }}.onFailure(::report)
            // Binding/attachment is independent of compiled/inlined draw entries.
            for(method in host.declaredMethods.filter {it.name in setOf("applyIconAndLabel","reapplyItemInfo","onAttachedToWindow")}) {
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    if(main() && !IconGlassHostSlot.isBinding())runCatching {
                        val view=chain.getThisObject() as? View ?: return@runCatching
                        val registered=state(view);ensureNativeEngine(view,registered)
                        view.postInvalidateOnAnimation()
                    }.onFailure(::report)
                    result
                }
            }
            mountFolderPreviews(module,loader)
            mountBitmapDrawable(module)
            mountSuggestionImages(module,loader)
            mountGroupSuggestions(module,loader)
            LauncherIconGlassAssets.install(module,loader) {mountDrawableOverride(module,it)}
            mounted=true
            preferences(true)
            runCatching {Prefs.read(module).registerOnSharedPreferenceChangeListener(prefListener)}
            RuntimeDiagnostics.mark("icon_glass_hook","桌面／Dock 位图槽入口已安装，默认关闭；实际图标效果待核验")
        } catch(t:Throwable) {contract=null;report(t)}
    }

    private fun mountHost(module:XposedInterface,type:Class<*>,names:List<String> = listOf("onDraw","drawIcon","drawWithoutDot")) {
        for(name in names) {
        val method=runCatching {type.getDeclaredMethod(name,Canvas::class.java)}.getOrNull() ?: continue
        check(method.returnType==Void.TYPE)
        // Never deoptimize framework View.draw: that slows every view in Launcher.
        if(type!=View::class.java)runCatching {module.deoptimize(method)}.onFailure(::report)
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val view=chain.getThisObject() as? View
            if(view==null || contract?.host?.isInstance(view)!=true)return@intercept chain.proceed()
            val canvas=chain.getArg(0) as? Canvas
            if(!main() || IconGlassRecordingSources.isRecording() || canvas==null)return@intercept chain.proceed()
            val ancestor=owner.current()
            if(ancestor?.state?.host?.get()===view && ancestor.canvas===canvas) {
                if(name=="drawIcon")return@intercept drawPreparedViewIcon(chain,view,canvas,ancestor)
                return@intercept chain.proceed()
            }
            var context:Draw?=null
            try {
                preferences()
                RuntimeDiagnostics.mark("icon_glass_view_entry","桌面图标 View 绘制入口已执行")
                if(enabled) {
                    val icon=contract?.icon?.get(view) as? Drawable
                    val state=state(view)
                    if(inspect(view,state)) {
                        if(icon!=null)prepareNative(view,state,icon)
                        val drawable=contract?.icon?.get(view) as? Drawable
                        if(drawable!=null) {
                            mountDrawableOverride(module,drawable.javaClass)
                            val base=Matrix();canvas.getMatrix(base)
                            val staticSource=if(contract?.fast?.isInstance(drawable)!=true && drawable !is BitmapDrawable)
                                LauncherIconGlassAssets.sourceForItem(view.tag) ?: IconGlassRecordingSources.get(drawable) else null
                            context=Draw(state,drawable,canvas,base,source=staticSource)
                        }
                    }
                }
            } catch(t:Throwable) {report(t)}
            if(name=="drawIcon" && context!=null)return@intercept drawPreparedViewIcon(chain,view,canvas,context)
            // An inactive nested view must mask an ancestor's draw owner, even on exceptions.
            owner.run(context) {chain.proceed()}
        }
        }
    }
    private fun prepareNative(view:View,state:State,drawable:Drawable) {
        val c=contract ?: return
        runCatching {ensureNativeEngine(view,state)}.onFailure(::report)
        val native=if(c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null
        if(native!=null && LauncherIconGlassAssets.current(native)) {
            if(!LauncherIconGlassAssets.hostSlotBound(view,drawable,native))
                LauncherIconGlassAssets.apply(view,drawable,view.tag,LauncherIconGlassAssets.original(native),native)
            if(liveMode)syncLiveHost(view,state)
            return
        }
        val source=hostSource(view,drawable) ?: run {
            IconGlassPipelineTrace.record(state.component,"source","missing drawable=${drawable.javaClass.simpleName}");return
        }
        state.screenRect.set(iconBounds(view) ?: drawable.bounds)
        if(state.screenRect.isEmpty)return
        state.global.reset();view.transformMatrixToGlobal(state.global);state.global.mapRect(state.screenRect)
        observeScreen(view.context)
        RuntimeDiagnostics.mark("icon_glass_prepare","已从 View 入口请求图标准备，不依赖 Drawable 内层 Hook")
        LauncherIconGlassBinding.prepare(view,drawable,view.tag,source,state.component,state.userId,
            state.screenRect,params,artworkReady)
        if(liveMode)syncLiveHost(view,state)
    }
    private fun iconBounds(view:View):Rect?=runCatching {
        val bounds=Rect()
        (contract?.bounds ?: return@runCatching null).invoke(view,bounds)
        bounds.takeUnless {it.isEmpty}
    }.getOrNull()
    /** Vector and custom-engine icons need a source even without an ItemInfo bitmap. */
    private fun hostSource(view:View,drawable:Drawable):Bitmap? {
        val c=contract ?: return null
        return LauncherIconGlassAssets.sourceForItem(view.tag) ?: (drawable as? BitmapDrawable)?.bitmap ?:
            (if(c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null) ?:
            IconGlassRecordingSources.get(drawable) ?: IconGlassRecordingSources.get(view) ?:
            IconGlassRasterSources.resolve(view.context,drawable,artworkReady)
    }
    private fun syncLiveHost(view:View,state:State) {
        val c=contract ?: return
        if(!liveMode || !enabled ||
            runCatching {contract?.visible?.invoke(view)==false}.getOrDefault(false)) {
            IconGlassLiveSurface.restore(view);return
        }
        val drawable=c.icon.get(view) as? Drawable ?: return
        val raw=hostSource(view,drawable) ?: return
        val source=LauncherIconGlassAssets.original(raw)
        val cached=state.artwork[drawable]
        val mask=cached?.takeIf {it.source===source && it.generation==source.generationId && it.variant==IconGlassDynamicIcons.variant(state.component?.flattenToString())}?.mask ?:
            IconGlassMasks.resolve(view.context,state.component,state.userId,source,artworkReady)?.also {
                state.artwork[drawable]=Artwork(source,source.generationId,it,IconGlassDynamicIcons.variant(state.component?.flattenToString()))
            } ?: return
        val bounds=iconBounds(view) ?: return
        if(mask.passthrough) {IconGlassLiveSurface.restore(view);return}
        state.global.reset();view.transformMatrixToGlobal(state.global)
        state.screenRect.set(bounds);state.global.mapRect(state.screenRect)
        val frame=IconGlassWallpaper.resolve(view.context,state.screenRect,artworkReady)
        IconGlassLiveSurface.sync(view,mask.foreground,RectF(bounds),params.copy(reliefArtwork=mask.relief,detailedArtwork=mask.detailed),frame)
    }
    /** Paint at the real View icon slot, even when an OEM engine bypasses Drawable hooks. */
    private fun drawPreparedViewIcon(chain:XposedInterface.Chain,view:View,canvas:Canvas,scope:Draw):Any? {
        val bounds=iconBounds(view) ?: return owner.run(scope) {chain.proceed()}
        if(runCatching {contract?.visible?.invoke(view)==false}.getOrDefault(false))
            return owner.run(scope) {IconGlassLiveSurface.restore(view);chain.proceed()}
        if(liveMode)runCatching {syncLiveHost(view,scope.state)}.onFailure(::report)
        val surface=liveMode && canvas.isHardwareAccelerated && IconGlassLiveSurface.owns(view)
        val prefab=LauncherIconGlassAssets.prefabFor(view.tag) ?:
            LauncherIconGlassAssets.sourceForItem(view.tag)?.let(LauncherIconGlassAssets::generated)
        if(!surface && prefab==null) {
            if(contract?.fast?.isInstance(scope.drawable)!=true && scope.drawable !is BitmapDrawable)
                return drawFancyView(chain,view,canvas,scope)
            return owner.run(scope) {chain.proceed()}
        }
        val save=canvas.save();canvas.clipOutRect(bounds)
        val result=try {owner.run(null) {chain.proceed()}}finally {canvas.restoreToCount(save)}
        if(!surface && prefab!=null) {
            canvas.drawBitmap(prefab,null,bounds,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=scope.drawable.alpha})
            RuntimeDiagnostics.mark("icon_glass_view_slot","已从 View 的实际图标区域绘制，绕过厂商旧图标缓存")
        }
        return result
    }
    private fun ensureNativeEngine(view:View,state:State) {
        val c=contract ?: return
        if(state.engine.get()!=null)return
        val engine=field(view.javaClass,"mOPlusBtvExtV2")?.get(view) ?: return
        state.engine=WeakReference(engine);nativeEngines[engine]=WeakReference(view)
        var type:Class<*>?=engine.javaClass
        var method:Method?=null
        while(type!=null && method==null) {
            method=runCatching {type!!.getDeclaredMethod("onInterceptIconDraw",Canvas::class.java)}.getOrNull()
            type=type.superclass
        }
        val target=method?.takeIf {it.returnType==Boolean::class.javaPrimitiveType} ?: return
        if(!nativeEngineMethods.add(target))return
        api?.hook(target)?.setExceptionMode(ExceptionMode.PROTECTIVE)?.intercept {chain ->
            val host=nativeEngines[chain.getThisObject()]?.get()
            val drawable=host?.let {c.icon.get(it) as? Drawable}
            val bitmap=if(drawable!=null && c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null
            if(main() && enabled && host!=null && bitmap!=null && LauncherIconGlassAssets.isGenerated(bitmap) &&
                inspect(host,state(host))) {
                RuntimeDiagnostics.mark("icon_glass_native_engine","已让准备好的图标回到原生位图槽；避免主题引擎继续显示旧缓存")
                false
            } else chain.proceed()
        }
    }
    private fun mountDrawableOverride(module:XposedInterface,type:Class<*>) {
        if(!drawableTypes.add(type))return
        if(contract?.fast?.isAssignableFrom(type)!=true) {mountGenericDrawable(module,type);return}
        var current:Class<*>?=type
        while(current!=null && current!=contract?.fast) {
            runCatching {current!!.getDeclaredMethod("draw",Canvas::class.java)}.getOrNull()?.let {
                runCatching {module.deoptimize(it)}.onFailure(::report)
            }
            val method=runCatching {current!!.getDeclaredMethod("drawInternal",Canvas::class.java,Rect::class.java)}.getOrNull()
            if(method!=null)mountSlot(module,method)
            current=current.superclass
        }
    }
    private fun mountSlot(module:XposedInterface,method:Method) {
        if(!hookedDraws.add(method))return
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            if(IconGlassRecordingSources.isRecording())return@intercept chain.proceed()
            val canvas=chain.getArg(0) as? Canvas
            val rect=chain.getArg(1) as? Rect
            val drawable=chain.getThisObject() as? Drawable
            val scope=drawOwner(drawable,canvas)
            val owned=scope?.drawable===drawable && scope?.canvas===canvas
            if((!liveMode || !owned) && drawable!=null && canvas!=null && rect!=null &&
                runCatching {LauncherIconGlassAssets.drawCached(drawable,canvas,rect)}.getOrDefault(false))return@intercept null
            if(scope==null || scope.drawable!==chain.getThisObject() || scope.canvas!==canvas ||
                canvas==null || rect==null || !main())return@intercept chain.proceed()
            val painted=try {draw(scope,canvas,rect)} catch(t:Throwable) {report(t);false}
            if(painted || (enabled && fallback(scope,canvas,rect)))null else chain.proceed()
        }
    }
    private fun draw(scope:Draw,canvas:Canvas,rect:Rect):Boolean {
        if(IconGlassRecordingSources.isRecording())return false
        val c=contract ?: return false
        val state=scope.state;val view=state.host.get() ?: return false
        if(!enabled || !canvas.isHardwareAccelerated ||
            !IconGlassPolicy.validSize(rect.width().toFloat(),rect.height().toFloat()))return false
        val raw=scope.source ?: (scope.drawable as? BitmapDrawable)?.bitmap ?:
            (if(c.fast.isInstance(scope.drawable))c.bitmap.get(scope.drawable) as? Bitmap else IconGlassRecordingSources.get(scope.drawable)) ?: return false
        val original=LauncherIconGlassAssets.original(raw)
        // Low-res placeholders must remain eligible for the launcher's real icon load.
        if(original.isRecycled || original.width<16 || original.height<16)return false
        if(!liveMode)LauncherIconGlassAssets.prefabFor(scope.item ?: view.tag)?.let {prefab ->
            LauncherIconGlassAssets.apply(view,scope.drawable,scope.item ?: view.tag,original,prefab)
            if(LauncherIconGlassAssets.drawCached(scope.drawable,canvas,rect))return true
            canvas.drawBitmap(prefab,null,rect,Paint(Paint.FILTER_BITMAP_FLAG).apply {alpha=scope.drawable.alpha});return true
        }
        observeScreen(view.context)
        val cachedArtwork=state.artwork[scope.drawable]
        val mask=cachedArtwork?.takeIf {it.source===original && it.generation==original.generationId && it.variant==IconGlassDynamicIcons.variant(state.component?.flattenToString())}?.mask ?:
            IconGlassMasks.resolve(view.context,scope.component,scope.userId,original,artworkReady)?.also {
                state.artwork[scope.drawable]=Artwork(original,original.generationId,it,IconGlassDynamicIcons.variant(state.component?.flattenToString()))
            } ?: return fallback(scope,canvas,rect)
        // Compare matrices within this same Canvas to remove an arbitrary recording origin.
        // Native compound-drawable placement and FastBitmapDrawable transforms stay included.
        if(!scope.base.invert(state.inverse))return false
        canvas.getMatrix(state.current)
        state.combined.setConcat(state.inverse,state.current)
        state.screenRect.set(rect)
        state.combined.mapRect(state.screenRect)
        state.global.reset();view.transformMatrixToGlobal(state.global)
        state.global.mapRect(state.screenRect)
        val background=IconGlassWallpaper.resolve(view.context,state.screenRect,artworkReady)
        val backdrop=background?.bitmap ?: IconGlassFallback.backdrop
        val crop=background?.sourceRect ?: RectF(0f,0f,8f,8f)
        val nativePaint=if(scope.drawable is BitmapDrawable)scope.drawable.paint else if(c.fast.isInstance(scope.drawable))
            (field(scope.drawable.javaClass,"mMonoPaint") ?: c.paint)?.get(scope.drawable) as? Paint else null
        val alpha=nativePaint?.alpha ?: scope.drawable.alpha
        val disabled=if(c.fast.isInstance(scope.drawable))c.disabled?.invoke(scope.drawable) as? Boolean ?: false else !view.isEnabled
        state.bounds.set(rect)
        val generated=if(LauncherIconGlassAssets.isGenerated(raw))raw else LauncherIconGlassAssets.generated(original) ?:
            LauncherIconGlassAssets.request(original,mask.foreground,backdrop,crop,
                params.copy(reliefArtwork=mask.relief,detailedArtwork=mask.detailed,customImage=mask.passthrough),artworkReady,LauncherIconGlassAssets.identity(scope.item ?: view.tag),background?.sharpBitmap ?: backdrop)
        if(generated!=null) {
            if(raw!==generated)LauncherIconGlassAssets.apply(view,scope.drawable,scope.item ?: view.tag,original,generated)
            if(!liveMode || background==null || mask.passthrough) {
                if(LauncherIconGlassAssets.drawCached(scope.drawable,canvas,rect))return true
                canvas.drawBitmap(generated,null,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {this.alpha=alpha})
                return true
            }
        }
        if(mask.passthrough) {
            canvas.drawBitmap(mask.foreground,null,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {this.alpha=alpha})
            return true
        }
        val material=state.materials.getOrPut(scope.drawable) {IconGlassMaterial()}
        val result=material.draw(canvas,state.bounds,mask.foreground,backdrop,crop,
            params.copy(alpha=alpha,disabled=disabled,reliefArtwork=mask.relief,detailedArtwork=mask.detailed),background?.sharpBitmap ?: backdrop)
        if(result && !rendered) {
            rendered=true
            RuntimeDiagnostics.mark("icon_glass_render","3.0.0：图标曲边使用清晰壁纸折射；图案采用浅色分层玻璃")
        }
        if(result && scope.suggestionImage)suggestionRendered(view)
        if(result)RuntimeDiagnostics.mark("icon_glass_source_${mask.kind}","图标轮廓来源：${mask.kind}")
        return result || fallback(scope,canvas,rect)
    }

    private fun fallback(scope:Draw,canvas:Canvas,rect:Rect):Boolean {
        if(!enabled || IconGlassRecordingSources.isRecording())return false
        if(LauncherIconGlassAssets.drawCached(scope.drawable,canvas,rect))return true
        val c=contract ?: return false
        val raw=scope.source ?: (scope.drawable as? BitmapDrawable)?.bitmap ?:
            (if(c.fast.isInstance(scope.drawable))c.bitmap.get(scope.drawable) as? Bitmap else IconGlassRecordingSources.get(scope.drawable)) ?: return false
        val prefab=LauncherIconGlassAssets.prefabFor(scope.item ?: scope.state.host.get()?.tag) ?: LauncherIconGlassAssets.generated(raw)
        if(prefab!=null) {
            canvas.drawBitmap(prefab,null,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=scope.drawable.alpha});return true
        }
        val source=LauncherIconGlassAssets.original(raw)
        val material=scope.state.materials.getOrPut(scope.drawable) {IconGlassMaterial()}
        return IconGlassFallback.draw(canvas,rect,source,material,scope.drawable.alpha)
    }

    private fun drawFancyView(chain:XposedInterface.Chain,view:View,canvas:Canvas,scope:Draw):Any? {
        val bounds=iconBounds(view) ?: return owner.run(scope) {chain.proceed()}
        if(bounds.isEmpty)return owner.run(scope) {chain.proceed()}
        val source=scope.source ?: IconGlassRecordingSources.get(view)
        if(source!=null) {
            val save=canvas.save();canvas.clipOutRect(bounds)
            val result=try {owner.run(null) {chain.proceed()}}finally {canvas.restoreToCount(save)}
            runCatching {draw(scope.copy(source=source),canvas,bounds)}.onFailure(::report)
            return result
        }
        val recording=IconGlassRecordingSources.record(view,view.width,view.height,bounds,artworkReady) {target ->
            val args=chain.getArgs().toTypedArray();args[0]=target;owner.run(null) {chain.proceed(args)}
        }
        if(recording!=null) {IconGlassFallback.recorded(canvas,recording.first,bounds);return recording.second}
        IconGlassRecordingSources.preview(view)?.let {IconGlassFallback.recorded(canvas,it,bounds);return null}
        val save=canvas.save();canvas.clipOutRect(bounds)
        val result=try {owner.run(null) {chain.proceed()}}finally {canvas.restoreToCount(save)}
        IconGlassFallback.empty(canvas,bounds);return result
    }

    private fun mountGenericDrawable(module:XposedInterface,type:Class<*>) {
        var current:Class<*>?=type;var method:Method?=null
        while(current!=null && method==null) {
            method=runCatching {current!!.getDeclaredMethod("draw",Canvas::class.java)}.getOrNull();current=current.superclass
        }
        val drawMethod=method?.takeUnless {java.lang.reflect.Modifier.isAbstract(it.modifiers)} ?: return
        if(!hookedDraws.add(drawMethod))return
        module.hook(drawMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val canvas=chain.getArg(0) as? Canvas
            val scope=drawOwner(chain.getThisObject() as? Drawable,canvas)
            if(IconGlassRecordingSources.isRecording() || scope==null || scope.drawable!==chain.getThisObject() || canvas!==scope.canvas || canvas==null || !enabled)
                return@intercept chain.proceed()
            val source=scope.source ?: IconGlassRecordingSources.get(scope.drawable)
            if(source!=null && runCatching {draw(scope.copy(source=source),canvas,scope.drawable.bounds)}.getOrDefault(false))return@intercept null
            val rect=Rect(scope.drawable.bounds)
            val recorded=IconGlassRecordingSources.record(scope.drawable,rect.width(),rect.height(),Rect(0,0,rect.width(),rect.height()),artworkReady) {target ->
                target.translate(-rect.left.toFloat(),-rect.top.toFloat())
                val args=chain.getArgs().toTypedArray();args[0]=target;owner.run(null) {chain.proceed(args)}
            }
            if(recorded!=null) {
                val save=canvas.save();canvas.translate(rect.left.toFloat(),rect.top.toFloat())
                IconGlassFallback.recorded(canvas,recorded.first,Rect(0,0,rect.width(),rect.height()));canvas.restoreToCount(save)
                recorded.second
            } else {
                val preview=IconGlassRecordingSources.preview(scope.drawable)
                if(preview!=null) {
                    val save=canvas.save();canvas.translate(rect.left.toFloat(),rect.top.toFloat())
                    IconGlassFallback.recorded(canvas,preview,Rect(0,0,rect.width(),rect.height()));canvas.restoreToCount(save);null
                } else {
                    val save=canvas.save();canvas.clipOutRect(rect)
                    val result=try {owner.run(null) {chain.proceed()}}finally {canvas.restoreToCount(save)}
                    IconGlassFallback.empty(canvas,rect);result
                }
            }
        }
    }

    /** Static bitmap slot; custom engines use a bounded, one-time recording separately. */
    private fun mountBitmapDrawable(module:XposedInterface) {
        module.hook(BitmapDrawable::class.java.getDeclaredMethod("draw",Canvas::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val canvas=chain.getArg(0) as? Canvas
                val drawable=chain.getThisObject() as? BitmapDrawable
                val scope=drawOwner(drawable,canvas)
                val result=if(scope!=null && scope.drawable===drawable && scope.canvas===canvas && canvas!=null && main())
                    runCatching {draw(scope,canvas,drawable.bounds)}.getOrDefault(false) else false
                if(result)null else chain.proceed()
            }
    }

    private class EngineState(root:View,private val appIconsOnly:Boolean=false):ViewTreeObserver.OnGlobalLayoutListener,ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        private val target=WeakReference(root)
        private var observer=WeakReference(root.viewTreeObserver)
        private val images=ArrayList<WeakReference<ImageView>>()
        fun scan() {
            val root=target.get() ?: return
            images.clear()
            val queue=java.util.ArrayDeque<View>();queue.add(root)
            val leafTypes=LinkedHashSet<String>()
            var visited=0
            while(queue.isNotEmpty() && visited++<512) {
                val view=queue.removeFirst()
                if(view !is ViewGroup && leafTypes.size<6)leafTypes.add(view.javaClass.name)
                val accepted=view is ImageView && (!appIconsOnly || api?.let {IconGlassAppIconSlot.register(view,it)}==true)
                if(accepted)images.add(WeakReference(view as ImageView))
                if(accepted && view is ImageView && view !in suggestionImages) {
                    suggestionImages[view]=true;api?.let {mountEngineView(it,view.javaClass)}
                } else if(!appIconsOnly && view is TextView)suggestionTexts[view]=true
                else if(!appIconsOnly && view !is ViewGroup && view !in suggestionSurfaces &&
                    listOf("image","icon","bitmap").any {view.javaClass.simpleName.lowercase().contains(it)}) {
                    suggestionSurfaces[view]=BitmapDrawable(view.resources,IconGlassFallback.backdrop)
                    api?.let {mountEngineView(it,view.javaClass)}
                }
                if(view is ViewGroup)for(i in 0 until minOf(view.childCount,128))queue.add(view.getChildAt(i))
            }
            RuntimeDiagnostics.update("icon_suggestion_scan","images=${images.size}; leaves=${leafTypes.joinToString()}")
        }
        override fun onPreDraw():Boolean {
            RuntimeWorkPolicy.guarded(::report) {images.forEach {it.get()?.let(::prepareSuggestionImage)}}
            return true
        }
        override fun onGlobalLayout() {RuntimeWorkPolicy.guarded(::report) {scan()}}
        override fun onViewAttachedToWindow(v:View) {
            observer=WeakReference(v.viewTreeObserver)
            RuntimeWorkPolicy.guarded(::report) {v.viewTreeObserver.addOnGlobalLayoutListener(this);v.viewTreeObserver.addOnPreDrawListener(this);scan()}
        }
        override fun onViewDetachedFromWindow(v:View) {
            observer.get()?.let {if(it.isAlive){it.removeOnGlobalLayoutListener(this);it.removeOnPreDrawListener(this)}}
            images.forEach {it.get()?.let(IconGlassLiveSurface::restore)}
            suggestionRoots.remove(v);v.removeOnAttachStateChangeListener(this)
        }
    }

    private fun mountGroupSuggestions(module:XposedInterface,loader:ClassLoader) {
        runCatching {
            val group=Class.forName("com.android.launcher3.card.groupcard.GroupCardView",false,loader)
            val dispatch=group.getDeclaredMethod("dispatchDraw",Canvas::class.java)
            module.deoptimize(dispatch)
            module.hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val card=chain.getThisObject() as? View
                if(main())preferences()
                if(main() && enabled && card!=null && card !in suggestionRoots)runCatching {
                    // Only appicon URI ImageViews inside this owner are enrolled; weather,
                    // illustrations and other card contents retain the original rendering.
                    verifiedSuggestionCards[card]=true;scopeRevision++
                    val state=EngineState(card,appIconsOnly=true);suggestionRoots[card]=state
                    card.viewTreeObserver.addOnGlobalLayoutListener(state)
                    card.viewTreeObserver.addOnPreDrawListener(state);card.addOnAttachStateChangeListener(state)
                    state.scan();card.postInvalidateOnAnimation()
                }.onFailure(::report)
                chain.proceed()
            }
            RuntimeDiagnostics.mark("icon_group_suggestion_contract","GroupCardView 已接入；仅替换 appicon URI 图片")
        }.onFailure(::report)
    }

    /** The recommendation engine supplies ImageViews instead of BubbleTextViews. Register
     * descendants only when the verified wrapper adds its actual engine root; never target
     * arbitrary widgets, thumbnails, loading/error layouts, or unrelated ImageViews. */
    private fun mountSuggestionImages(module:XposedInterface,loader:ClassLoader) {
        runCatching {
            val cardType=Class.forName("com.android.launcher3.card.TitleCardView",false,loader)
            fun register(card:View) {
                if(!isSuggestionCard(card) || card in suggestionRoots)return
                val state=EngineState(card);suggestionRoots[card]=state;scopeRevision++
                card.viewTreeObserver.addOnGlobalLayoutListener(state);card.viewTreeObserver.addOnPreDrawListener(state);card.addOnAttachStateChangeListener(state);state.scan()
            }
            // Registration after attachment/layout also covers cards whose item/engine was
            // not ready during onAddChildView, and engines with a private getView method.
            val saving=field(cardType,"mSavingSnapshot")
            val waiting=field(cardType,"mWaitUiData")
            val dispatch=cardType.getDeclaredMethod("dispatchDraw",Canvas::class.java)
            module.deoptimize(dispatch)
            module.hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val card=chain.getThisObject() as? View
                if(!main() || card==null)return@intercept chain.proceed()
                runCatching {register(card)}.onFailure(::report)
                // An old whole-card snapshot otherwise paints original icons over live children.
                // Keep native loading/error snapshots until real child artwork has been rendered.
                val bypass=enabled && suggestionReady[card]==true && waiting?.get(card)!=true &&
                    saving?.get(card)==false && !IconGlassRecordingSources.isRecording()
                if(bypass)saving?.set(card,true)
                try {chain.proceed()}finally {if(bypass)saving?.set(card,false)}
            }
            val wrapper=Class.forName("com.android.launcher3.card.appsuggestnopadding.SuggestNoPaddingViewWrapper",false,loader)
            val cardField=field(wrapper,"titleCardView") ?: error("No suggestion card owner")
            val add=wrapper.getDeclaredMethod("onAddChildView",View::class.java)
            module.hook(add).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(main())runCatching {
                    val child=chain.getArg(0) as? View ?: return@runCatching
                    val card=cardField.get(chain.getThisObject()) as? View ?: return@runCatching
                    card.post {RuntimeWorkPolicy.guarded(::report) {if(card.isAttachedToWindow)register(card)}}
                    if(!isSuggestionCard(card))return@runCatching
                    // The verified native wrapper supplies its actual engine root here.
                    child.post {
                        RuntimeWorkPolicy.guarded(::report) {if(child.isAttachedToWindow && child !in suggestionRoots) {
                            val state=EngineState(child);suggestionRoots[child]=state
                            child.viewTreeObserver.addOnGlobalLayoutListener(state);child.viewTreeObserver.addOnPreDrawListener(state)
                            child.addOnAttachStateChangeListener(state);state.scan()
                            child.invalidate()
                        }}
                    }
                }.onFailure(::report)
                result
            }
            module.hook(ImageView::class.java.getDeclaredMethod("onDraw",Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val view=chain.getThisObject() as? ImageView
                    val canvas=chain.getArg(0) as? Canvas
                    val painted=if(view!=null && canvas!=null && main() && view in suggestionImages)
                        runCatching {
                            if(IconGlassLiveSurface.owns(view)) {
                                if(canvas.isHardwareAccelerated)true else IconGlassImageBinding.drawFallback(view,canvas)
                            } else drawSuggestionImage(view,canvas)
                        }.onFailure(::report).getOrDefault(false) else false
                    if(painted)null else if(view!=null && canvas!=null && main() && view in suggestionImages)
                        drawEngineFallback(chain,view,canvas) else chain.proceed()
                }
            // Recommendations can update an existing ImageView in place. Invalidate only
            // that captured source after an actual native image change, never our prefab swap.
            for(setter in ImageView::class.java.declaredMethods.filter {it.name in
                setOf("setImageDrawable","setImageBitmap","setImageResource","setImageURI") && it.returnType==Void.TYPE}) {
                module.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val view=chain.getThisObject() as? ImageView
                    val tracked=main() && view!=null && view in suggestionImages
                    val before=if(tracked)view?.drawable else null
                    val args=chain.getArgs().toTypedArray()
                    if(tracked && view!=null && setter.name=="setImageDrawable")
                        args[0]=IconGlassAppIconSlot.binding(view,args[0] as? Drawable)
                    val result=chain.proceed(args)
                    if(tracked && view!=null && before!==view.drawable)runCatching {
                        val drawable=view.drawable
                        val bitmap=(drawable as? BitmapDrawable)?.bitmap ?: if(contract?.fast?.isInstance(drawable)==true)
                            contract?.bitmap?.get(drawable) as? Bitmap else null
                        if(bitmap==null || !LauncherIconGlassAssets.isGenerated(bitmap))IconGlassRecordingSources.invalidate(view)
                    }.onFailure(::report)
                    result
                }
            }
            module.hook(TextView::class.java.getDeclaredMethod("onDraw",Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val view=chain.getThisObject() as? TextView;val canvas=chain.getArg(0) as? Canvas
                    var context:Draw?=null
                    if(main() && view!=null && canvas!=null && view in suggestionTexts)runCatching {
                        preferences()
                        val drawable=(view.compoundDrawables.toList()+view.compoundDrawablesRelative.toList())
                            .filterNotNull().firstOrNull {it.bounds.width()>=view.resources.displayMetrics.density*28f}
                            ?: return@runCatching
                        val state=state(view);val tag=view.tag ?: drawable
                        if(enabled && inspect(view,state,tag,suggestionImage=true)) {
                            mountDrawableOverride(module,drawable.javaClass)
                            val base=Matrix();canvas.getMatrix(base)
                            context=Draw(state,drawable,canvas,base,tag,source=LauncherIconGlassAssets.sourceForItem(view.tag),suggestionImage=true)
                        }
                    }.onFailure(::report)
                    owner.run(context) {chain.proceed()}
                }
            RuntimeDiagnostics.mark("icon_glass_suggestion_contract","小布建议引擎图标入口已安装，按原生推荐卡片归属处理应用图标")
        }.onFailure(::report)
    }
    /** Called by the already-verified native suggestion background owner. */
    internal fun registerVerifiedSuggestion(card:View) {
        if(!main() || !isTitleCard(card))return
        if(verifiedSuggestionCards.put(card,true)!=true)scopeRevision++
        if(card !in suggestionRoots) {
            val state=EngineState(card);suggestionRoots[card]=state
            card.viewTreeObserver.addOnGlobalLayoutListener(state);card.viewTreeObserver.addOnPreDrawListener(state)
            card.addOnAttachStateChangeListener(state);state.scan();card.postInvalidateOnAnimation()
        }
    }
    private fun prepareSuggestionImage(view:ImageView) {
        preferences()
        val state=state(view);val drawable=view.drawable ?: run {IconGlassLiveSurface.restore(view);return}
        val tag=view.tag ?: drawable
        val bounds=IconGlassImageGeometry.bounds(view) ?: run {IconGlassLiveSurface.restore(view);return}
        if(!enabled || !inspect(view,state,tag,suggestionImage=true)) {IconGlassLiveSurface.restore(view);IconGlassImageClip.restore(view);return}
        IconGlassAppIconSlot.component(view)?.let {state.component=it}
        val c=contract ?: return
        val raw=(drawable as? BitmapDrawable)?.bitmap ?:
            (if(c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null) ?:
            IconGlassRasterSources.resolve(view.context,drawable,artworkReady) ?: return
        val source=LauncherIconGlassAssets.original(raw)
        val generated=IconGlassImageBinding.prepare(view,source,state.component,state.userId,params,artworkReady)
        if(generated!=null) {
            suggestionRendered(view)
            RuntimeDiagnostics.mark("icon_suggestion_bound","小布建议图片已在 pre-draw 直接替换，不依赖 onDraw 拦截")
        }
        if(liveMode) {
            val mask=IconGlassMasks.resolve(view.context,state.component,state.userId,source,artworkReady) ?: return
            if(mask.passthrough) {IconGlassLiveSurface.restore(view);return}
            val global=RectF(bounds);val transform=Matrix();view.transformMatrixToGlobal(transform);transform.mapRect(global)
            val frame=IconGlassWallpaper.resolve(view.context,global,artworkReady)
            IconGlassLiveSurface.sync(view,mask.foreground,bounds,params.copy(reliefArtwork=mask.relief,detailedArtwork=mask.detailed),frame)
        } else IconGlassLiveSurface.restore(view)
    }

    private fun drawSuggestionImage(view:ImageView,canvas:Canvas):Boolean {
        if(IconGlassRecordingSources.isRecording())return false
        preferences()
        val density=view.resources.displayMetrics.density
        if(!enabled || !canvas.isHardwareAccelerated || minOf(view.width,view.height)<density*28f)return false
        val drawable=view.drawable ?: return false
        val state=state(view);val tag=view.tag ?: drawable
        if(!inspect(view,state,tag,suggestionImage=true))return false
        observeScreen(view.context)
        val c=contract ?: return false
        val source=(drawable as? BitmapDrawable)?.bitmap ?:
            (if(c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null) ?:
            IconGlassRasterSources.resolve(view.context,drawable,artworkReady) ?: return false
        if(drawable.bounds.isEmpty || !IconGlassPolicy.validSize(drawable.bounds.width().toFloat(),drawable.bounds.height().toFloat()))return false
        val base=Matrix();canvas.getMatrix(base)
        val count=canvas.save()
        return try {
            val slot=IconGlassImageGeometry.bounds(view) ?: return false
            canvas.clipPath(Path().apply {addRoundRect(slot,minOf(slot.width(),slot.height())*.235f,
                minOf(slot.width(),slot.height())*.235f,Path.Direction.CW)})
            canvas.translate(view.paddingLeft.toFloat(),view.paddingTop.toFloat())
            canvas.concat(view.imageMatrix)
            draw(Draw(state,drawable,canvas,base,tag,false,source,true),canvas,drawable.bounds).also {
                if(it)suggestionRendered(view)
            }
        } finally {canvas.restoreToCount(count)}
    }

    private fun mountEngineView(module:XposedInterface,type:Class<*>) {
        var current:Class<*>?=type;var draw:Method?=null
        while(current!=null && current!=View::class.java && draw==null) {
            draw=runCatching {current!!.getDeclaredMethod("onDraw",Canvas::class.java)}.getOrNull();current=current.superclass
        }
        val method=draw ?: return
        if(method.declaringClass==ImageView::class.java || method.declaringClass==TextView::class.java || !engineMethods.add(method))return
        module.deoptimize(method)
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val view=chain.getThisObject() as? View;val canvas=chain.getArg(0) as? Canvas
            if(view==null || canvas==null || !main() || (view !in suggestionImages && view !in suggestionSurfaces))return@intercept chain.proceed()
            drawEngineFallback(chain,view,canvas)
        }
    }
    private fun drawEngineFallback(chain:XposedInterface.Chain,view:View,canvas:Canvas):Any? {
            if(IconGlassRecordingSources.isRecording())return chain.proceed()
            preferences()
            if(!enabled || !canvas.isHardwareAccelerated || minOf(view.width,view.height)<view.resources.displayMetrics.density*32f ||
                !IconGlassPolicy.validSize(view.width.toFloat(),view.height.toFloat()))return chain.proceed()
            if(view is ImageView && runCatching {drawSuggestionImage(view,canvas)}.getOrDefault(false))return null
            val source=IconGlassRecordingSources.get(view)
            val drawable=suggestionSurfaces.getOrPut(view) {BitmapDrawable(view.resources,IconGlassFallback.backdrop)}
            val state=state(view);val tag=view.tag ?: drawable
            if(!inspect(view,state,tag,suggestionImage=true))return chain.proceed()
            if(source!=null) {
                val prefab=LauncherIconGlassAssets.generated(source)
                if(!liveMode && prefab!=null) {canvas.drawBitmap(prefab,null,Rect(0,0,view.width,view.height),Paint(Paint.FILTER_BITMAP_FLAG));return null}
                val base=Matrix();canvas.getMatrix(base);drawable.setBounds(0,0,view.width,view.height)
                if(runCatching {draw(Draw(state,drawable,canvas,base,tag,source=source,suggestionImage=true),canvas,drawable.bounds)}.getOrDefault(false))return null
            }
            val recorded=IconGlassRecordingSources.record(view,view.width,view.height,Rect(0,0,view.width,view.height),artworkReady) {target ->
                val args=chain.getArgs().toTypedArray();args[0]=target;chain.proceed(args)
            }
            if(recorded!=null) {IconGlassFallback.recorded(canvas,recorded.first,Rect(0,0,view.width,view.height));return recorded.second}
            IconGlassRecordingSources.preview(view)?.let {IconGlassFallback.recorded(canvas,it,Rect(0,0,view.width,view.height));return null}
            IconGlassFallback.empty(canvas,Rect(0,0,view.width,view.height));return null
    }

    /** Large folder preview icons are manually painted by the manager, not child TextViews.
     * Bypass only its pre-rasterized preview cache while our visible folder owns the Canvas. */
    private fun mountFolderPreviews(module:XposedInterface,loader:ClassLoader) {
        runCatching {
            val folder=Class.forName("com.android.launcher3.folder.FlexibleFolderIcon",false,loader)
            val manager=Class.forName("com.android.launcher3.folder.PreviewItemManager",false,loader)
            val big=Class.forName("com.android.launcher3.folder.big.BigFolderPreviewItemManager",false,loader)
            val paramsType=Class.forName("com.android.launcher3.folder.PreviewItemDrawingParams",false,loader)
            val stacked=Class.forName("com.android.launcher3.folder.big.stacked.BFPreviewItemDrawingParams",false,loader)
            val isBig=folder.getMethod("isBigFolderIcon")
            val managerHost=field(manager,"mIcon") ?: error("Missing preview host")
            val intrinsic=field(manager,"mIntrinsicIconSize") ?: error("Missing preview size")
            val previewCaches=field(manager,"mPreviewCacheDrawables")
            val slots=IconGlassPreviewSlots(field(paramsType,"drawable") ?: error("Missing preview drawable"),
                field(paramsType,"item") ?: error("Missing preview item"),
                field(paramsType,"transX") ?: error("Missing preview x"),
                field(paramsType,"transY") ?: error("Missing preview y"),
                field(paramsType,"scale") ?: error("Missing preview scale"),stacked,stacked.getMethod("getCurrentStackedParams"))
            // Own the manager's real draw entry. This also covers closed folders whose parent
            // dispatch was compiled/inlined or whose preview RenderNode is already cached.
            for(type in listOf(manager,big)) {
                val draw=type.getDeclaredMethod("draw",Canvas::class.java,java.lang.Boolean::class.java)
                module.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val canvas=chain.getArg(0) as? Canvas
                    val host=managerHost.get(chain.getThisObject()) as? View
                    val inherited=folderOwner.current()
                    if(inherited!=null && inherited.host===host && inherited.canvas===canvas)return@intercept chain.proceed()
                    var scope:FolderDraw?=null
                    if(main() && canvas?.isHardwareAccelerated==true && host!=null && !IconGlassRecordingSources.isRecording())runCatching {
                        preferences()
                        if(enabled && isBig.invoke(host)==true && inspect(host,state(host),host.tag,true)) {
                            val base=Matrix();canvas.getMatrix(base);scope=FolderDraw(host,canvas,base)
                        }
                    }.onFailure(::report)
                    folderOwner.run(scope) {chain.proceed()}
                }
                // drawParams can inline its tiny drawCache predicate.
                type.declaredMethods.filter {it.name in setOf("draw","drawParams","drawParamsWithAlpha","drawPreviewItem")}
                    .forEach {runCatching {module.deoptimize(it)}.onFailure(::report)}
            }
            module.hook(manager.getDeclaredMethod("drawCache",Canvas::class.java,Int::class.javaPrimitiveType,java.lang.Boolean::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val scope=folderOwner.current()
                    if(scope!=null && enabled && managerHost.get(chain.getThisObject())===scope.host &&
                        chain.getArg(0)===scope.canvas && (liveMode || !scope.prefabsReady))false else chain.proceed()
                }
            val drawParams=manager.getDeclaredMethod("drawParams",Canvas::class.java,Int::class.javaPrimitiveType,
                java.util.ArrayList::class.java,Float::class.javaPrimitiveType,java.lang.Boolean::class.java)
            module.hook(drawParams).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val scope=folderOwner.current()
                if(scope!=null && enabled && managerHost.get(chain.getThisObject())===scope.host && chain.getArg(0)===scope.canvas)runCatching {
                    val roots=chain.getArg(2) as? Iterable<*> ?: return@runCatching
                    val size=intrinsic.getFloat(chain.getThisObject())
                    var allReady=true;var visited=0;var changed=false
                    slots.visit(roots) {slot ->
                        val item=slot.item ?: return@visit
                        visited++
                        val meta=metadata(item);val state=state(scope.host)
                        val c=contract ?: return@visit
                        val raw=LauncherIconGlassAssets.sourceForItem(item) ?: (slot.drawable as? BitmapDrawable)?.bitmap ?:
                            (if(c.fast.isInstance(slot.drawable))c.bitmap.get(slot.drawable) as? Bitmap else null) ?:
                            IconGlassRecordingSources.get(slot.drawable)
                        if(raw==null) {
                            allReady=false;mountDrawableOverride(module,slot.drawable.javaClass)
                            scope.slots[slot.drawable]=Draw(state,slot.drawable,scope.canvas,scope.base,item,true,
                                component=meta.component,userId=meta.user)
                            return@visit
                        }
                        val old=(slot.drawable as? BitmapDrawable)?.bitmap ?:
                            (if(c.fast.isInstance(slot.drawable))c.bitmap.get(slot.drawable) as? Bitmap else null)
                        val source=LauncherIconGlassAssets.original(raw)
                        state.screenRect.set(slot.x,slot.y,slot.x+size*slot.scale,slot.y+size*slot.scale)
                        state.global.reset();scope.host.transformMatrixToGlobal(state.global);state.global.mapRect(state.screenRect)
                        val generated=LauncherIconGlassBinding.prepareBitmap(scope.host.context,item,source,meta.component,meta.user,
                            state.screenRect,params,artworkReady)
                        if(generated==null)allReady=false
                        if(generated!=null && old!==generated)changed=true
                        val drawable=if(generated!=null)LauncherIconGlassAssets.applySlot(scope.host,slot.owner,slot.field,
                            slot.drawable,item,source,generated) else slot.drawable
                        mountDrawableOverride(module,drawable.javaClass)
                        scope.slots[drawable]=Draw(state,drawable,scope.canvas,scope.base,item,true,source,
                            component=meta.component,userId=meta.user)
                    }
                    scope.prefabsReady=visited>0 && allReady
                    // Once all children contain prefabs, keep the native RenderNode cache.
                    // Invalidate it once per actual bitmap replacement, never every swipe frame.
                    if(changed)(previewCaches?.get(chain.getThisObject()) as? Map<*,*>)?.values?.forEach {
                        (it as? Drawable)?.invalidateSelf()
                    }
                    RuntimeDiagnostics.mark("icon_glass_preview_slots","闭合大文件夹和右下角堆叠图标已逐槽接入")
                }.onFailure(::report)
                chain.proceed()
            }
            // This override directly draws each child; it never calls drawInternal itself.
            val stackDrawable=Class.forName("com.android.launcher3.folder.big.stacked.StackedDrawable",false,loader)
            stackDrawable.declaredMethods.filter {it.name=="draw" || it.name=="drawWithAnim"}
                .forEach {runCatching {module.deoptimize(it)}.onFailure(::report)}
            RuntimeDiagnostics.mark("icon_glass_folder_contract","大文件夹管理器、静态预览缓存和堆叠子图标入口已安装")
        }.onFailure(::report)
    }
    private fun observeScreen(context:Context) {
        if(screenReceiverAttempted)return
        val app=context.applicationContext ?: return
        screenReceiverAttempted=true
        try {
            app.registerReceiver(object:BroadcastReceiver() {
                override fun onReceive(context:Context,intent:Intent) {
                    if(intent.action==Intent.ACTION_SCREEN_OFF) {
                        powerAt=-1000L;interactive=false;IconGlassLiveSurface.clear()
                        IconGlassMasks.cancelPending();IconGlassWallpaper.pause();IconGlassRasterSources.cancelPending()
                        LauncherIconGlassAssets.cancelPending()
                        IconGlassRecordingSources.cancelPending()
                    } else if(enabled) {
                        powerAt=-1000L;power(context)
                        if(interactive && !locked && !powerSave)hosts.forEach {(view,state) ->
                            if(view.isShown && view.windowVisibility==View.VISIBLE &&
                                IconGlassDynamicIcons.kind(state.component?.packageName).isNotEmpty())view.invalidate()
                        }
                    }
                }
            },IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_TIME_TICK);addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED);addAction(Intent.ACTION_DATE_CHANGED)
            })
        } catch(t:Throwable) {report(t)}
    }
    private fun field(type:Class<*>,name:String):Field? {
        var current:Class<*>?=type
        while(current!=null) {
            runCatching {current!!.getDeclaredField(name).apply {isAccessible=true}}.getOrNull()?.let {return it}
            current=current.superclass
        }
        return null
    }
}
