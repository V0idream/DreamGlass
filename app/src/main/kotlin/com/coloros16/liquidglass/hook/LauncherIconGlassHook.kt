/* DreamGlass contributors, 2026-11-13. GPL-3.0. */
package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.config.SafeAreas

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
        val bounds:Method?,val visible:Method?,val content:Method?)
    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        val host=WeakReference(view)
        var observer=WeakReference(view.viewTreeObserver)
        val inverse=Matrix();val current=Matrix();val global=Matrix();val combined=Matrix()
        val screenRect=RectF();val bounds=RectF();val visibleRect=Rect()
        val materials=WeakHashMap<Drawable,IconGlassMaterial>()
        val artwork=WeakHashMap<Drawable,Artwork>()
        var liveMaterialPainted=false
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
            // Native View.draw owns binding. Repeating it here used to prepare every
            // folder child twice per frame before its drawable was even painted.
            if(com.coloros16.liquidglass.BuildConfig.DEBUG &&
                component?.packageName in setOf("com.codex.floatingcamera","me.sukimon.mdl"))runCatching {
                val qualified=enabled && inspect(view,this)
                val icon=contract?.icon?.get(view) as? Drawable
                val bitmap=if(icon!=null && contract?.fast?.isInstance(icon)==true)contract?.bitmap?.get(icon) as? Bitmap else null
                RuntimeDiagnostics.update("icon_example_"+component?.packageName,
                    "eligible=$qualified container=$container drawable=${icon?.javaClass?.simpleName} generated=${bitmap?.let(LauncherIconGlassAssets::isGenerated)} live=foreground")
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
    private class Artwork(val source:Bitmap,val generation:Int,val mask:IconGlassMasks.Prepared,val variant:String,
        val component:ComponentName?,val userId:Int) {
        private var settings:IconGlassMaterial.Params?=null
        private var last:IconGlassMaterial.Params?=null
        fun style(base:IconGlassMaterial.Params,alpha:Int=255,disabled:Boolean=false):IconGlassMaterial.Params {
            last?.let {if(settings===base && it.alpha==alpha && it.disabled==disabled)return it}
            settings=base
            return base.copy(alpha=alpha,disabled=disabled,reliefArtwork=mask.relief,
                detailedArtwork=mask.detailed,customImage=mask.passthrough).also {last=it}
        }
    }
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
    private val nativeRenderMethods=HashSet<Method>()
    private val ui=Handler(Looper.getMainLooper())
    private var api:XposedInterface?=null
    private var contract:Contract?=null
    private var mounted=false
    private var readAt=-1000L
    private var enabled=false
    private var liveMode=false
    private val safeAreaMapper=SafeAreas.ViewMapper()
    private val safeIconBounds=RectF()
    private fun mayProtect(view: View, bounds: Rect): Boolean {
        if(!SafeAreas.active(SafeAreas.Scene.DESKTOP))return false
        safeIconBounds.set(bounds)
        return safeAreaMapper.desktop(view,safeIconBounds)!=null
    }
    private var params=IconGlassMaterial.Params()
    private var prefsRevision=0
    private var libraryHint: String?=null
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
    private var suggestionCardPredicate:Method?=null
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
    private val libraryReady: () -> Unit = {
        ui.post {
            if (enabled) {
                prefsRevision++
                hosts.values.forEach { it.artwork.clear() }
                IconGlassLiveSurface.clear()
                IconGlassMasks.clear()
                LauncherIconGlassAssets.restoreAndClear()
                invalidateHosts()
            }
        }
        Unit
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
            val hint = runCatching { p.getString(com.coloros16.liquidglass.icons.IconLibrary.REVISION, "") }.getOrNull()
            if (hint != null && hint != libraryHint) {
                libraryHint = hint
                if (enabled && next) IconGlassLibraryRuntime.requestRefresh()
            }
            IconGlassWallpaper.configureBlur(Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_BLUR,Prefs.DEFAULT_ICON_GLASS_BLUR))
            val nextLive=IconGlassMode.preferLive(p.getString(Prefs.KEY_ICON_GLASS_MODE,Prefs.DEFAULT_ICON_GLASS_MODE))
            val nextParams=IconGlassMaterial.Params(
                clarity=Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_CLARITY,76).coerceIn(0,100)/100f,
                refraction=Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_REFRACTION,56).coerceIn(0,100)/100f,
                thickness=Prefs.readIntCompat(p,Prefs.KEY_ICON_GLASS_THICKNESS,45).coerceIn(0,100)/100f,
                keepColor=p.getBoolean(Prefs.KEY_ICON_GLASS_KEEP_COLOR,true))
            if(next!=enabled || nextParams!=params || nextLive!=liveMode) {
                IconGlassLiveSurface.clear()
                if(nextParams!=params && enabled)LauncherIconGlassAssets.restoreAndClear()
                LauncherIconGlassAssets.setActive(next)
                val wasEnabled=enabled;enabled=next;params=nextParams;liveMode=nextLive;prefsRevision++
                IconGlassLibraryRuntime.setEnabled(next)
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
                IconGlassLibraryRuntime.setEnabled(false)
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
        val component=IconGlassIdentityAccess.component(item)
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
    private fun isSuggestionCard(view:View):Boolean=IconGlassSuggestionContract.matches(view,suggestionCardPredicate)
    internal fun safeAreasChanged() = invalidateHosts()

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
                runCatching {host.getMethod("getIconVisible")}.getOrNull(),
                runCatching {host.getMethod("drawWithoutDot",Canvas::class.java)}.getOrNull())
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
                        val registered=state(view)
                        if (view.tag != null) itemMetadata.remove(view.tag)
                        registered.tag=null
                        ensureNativeEngine(view,registered)
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
                if(name=="drawWithoutDot")return@intercept drawNativeContent(chain,view,canvas,ancestor)
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
            if(name=="drawWithoutDot" && context!=null)return@intercept drawNativeContent(chain,view,canvas,context)
            // An inactive nested view must mask an ancestor's draw owner, even on exceptions.
            owner.run(context) {
                // Suppress the owned before-icon pass while preserving the native
                // after-icon overlays, including notification dots and counts.
                IconGlassNativeDrawScope.run(nativeManager(context)) {chain.proceed()}
            }
        }
        }
    }
    private fun prepareNative(view:View,state:State,drawable:Drawable) {
        val c=contract ?: return
        runCatching {ensureNativeEngine(view,state)}.onFailure(::report)
        val native=if(c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null
        if(native!=null && LauncherIconGlassAssets.currentFor(native,view.tag)) {
            if(!LauncherIconGlassAssets.hostSlotBound(view,drawable,native))
                LauncherIconGlassAssets.apply(view,drawable,view.tag,LauncherIconGlassAssets.original(native),native)
            return
        }
        if(native!=null)LauncherIconGlassAssets.restoreStaleBinding(drawable,native)
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
    /** Paint at the real View icon slot, even when an OEM engine bypasses Drawable hooks. */
    private fun drawPreparedViewIcon(chain:XposedInterface.Chain,view:View,canvas:Canvas,scope:Draw):Any? {
        val bounds=iconBounds(view) ?: return owner.run(scope) {chain.proceed()}
        if(runCatching {contract?.visible?.invoke(view)==false}.getOrDefault(false))
            return owner.run(scope) {IconGlassLiveSurface.restore(view);chain.proceed()}
        // A generated FastBitmapDrawable already occupies the native compound slot.
        // Keep OEM icon animation, native Paint alpha, press scale and badges together.
        // Live content is routed through drawWithoutDot by the native engine hook below;
        // it must not depend on drawInternal surviving TextView/ART compiled call sites.
        if(contract?.fast?.isInstance(scope.drawable)==true) {
            IconGlassLiveSurface.restore(view)
            return owner.run(scope) {chain.proceed()}
        }
        val prefab=LauncherIconGlassAssets.prefabFor(view.tag) ?:
            LauncherIconGlassAssets.sourceForItem(view.tag)?.let {LauncherIconGlassAssets.generated(it,view.tag)}
        if(prefab==null) {
            if(contract?.fast?.isInstance(scope.drawable)!=true && scope.drawable !is BitmapDrawable)
                return drawFancyView(chain,view,canvas,scope)
            return owner.run(scope) {chain.proceed()}
        }
        val save=canvas.save();canvas.clipOutRect(bounds)
        val result=try {owner.run(null) {chain.proceed()}}finally {canvas.restoreToCount(save)}
        run {
            if(!mayProtect(view,bounds) || !runCatching {draw(scope,canvas,bounds)}.getOrDefault(false))
                canvas.drawBitmap(prefab,null,bounds,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=scope.drawable.alpha})
            RuntimeDiagnostics.mark("icon_glass_view_slot","已从 View 的实际图标区域绘制，绕过厂商旧图标缓存")
        }
        return result
    }
    private fun nativeManager(scope:Draw?):Any? {
        val c=contract ?: return null
        val drawable=scope?.drawable ?: return null
        if(!c.fast.isInstance(drawable))return null
        val bitmap=c.bitmap.get(drawable) as? Bitmap ?: return null
        if(!LauncherIconGlassAssets.isGenerated(bitmap))return null
        val engine=scope.state.engine.get() ?: return null
        return field(engine.javaClass,"mRenderManager")?.get(engine)
    }

    /** This is the same content entry used by the OEM animator, after its Canvas transforms.
     * Paint the GPU material here even if native TextView bypasses drawInternal. Native text,
     * press transforms, badges, outer animation clipping and opacity remain in this slot.
     */
    private fun drawNativeContent(chain:XposedInterface.Chain,view:View,canvas:Canvas,scope:Draw):Any? {
        val c=contract ?: return owner.run(scope) {chain.proceed()}
        if((!liveMode && !SafeAreas.active(SafeAreas.Scene.DESKTOP)) || !enabled || !canvas.isHardwareAccelerated || !c.fast.isInstance(scope.drawable) ||
            runCatching {c.visible?.invoke(view)==false}.getOrDefault(false))return owner.run(scope) {chain.proceed()}
        val bitmap=c.bitmap.get(scope.drawable) as? Bitmap
        val bounds=iconBounds(view)
        if(bitmap==null || !LauncherIconGlassAssets.isGenerated(bitmap) || bounds==null || (!liveMode && !mayProtect(view,bounds)))
            return owner.run(scope) {chain.proceed()}
        val type=scope.drawable.javaClass
        val scale=(field(type,"mScale")?.get(scope.drawable) as? Number)?.toFloat() ?: 1f
        val tx=(field(type,"mTransX")?.get(scope.drawable) as? Number)?.toFloat() ?: 0f
        val ty=(field(type,"mTransY")?.get(scope.drawable) as? Number)?.toFloat() ?: 0f
        if(!scale.isFinite() || scale<=0f || !tx.isFinite() || !ty.isFinite())return owner.run(scope) {chain.proceed()}
        IconGlassLiveSurface.restore(view)
        val iconSave=canvas.save()
        val painted=try {
            canvas.translate(tx,ty)
            canvas.scale(scale,scale,bounds.exactCenterX(),bounds.exactCenterY())
            owner.run(scope) {runCatching {draw(scope,canvas,bounds)}.onFailure(::report).getOrDefault(false)}
        }finally {canvas.restoreToCount(iconSave)}
        if(!painted)return owner.run(scope) {chain.proceed()}
        // Exclude only the native bitmap. Relying on its inner hook to suppress a prefab
        // would paint the stale image on top of the freshly drawn live material again.
        val halfWidth=bounds.width()*scale*.5f;val halfHeight=bounds.height()*scale*.5f
        val textSave=canvas.save()
        canvas.clipOutRect(bounds.exactCenterX()+tx-halfWidth,bounds.exactCenterY()+ty-halfHeight,
            bounds.exactCenterX()+tx+halfWidth,bounds.exactCenterY()+ty+halfHeight)
        val result=try {owner.run(null) {chain.proceed()}}finally {canvas.restoreToCount(textSave)}
        if(field(type,"mIsShowBadge")?.get(scope.drawable)==true) {
            val badge=field(type,"mBadge")?.get(scope.drawable) as? Drawable
            if(badge!=null) {
                val save=canvas.save()
                try {
                    canvas.translate(tx,ty)
                    canvas.scale(scale,scale,bounds.exactCenterX(),bounds.exactCenterY())
                    canvas.translate((bounds.left-scope.drawable.bounds.left).toFloat(),
                        (bounds.top-scope.drawable.bounds.top).toFloat())
                    owner.run(null) {badge.draw(canvas)}
                }finally {canvas.restoreToCount(save)}
            }
        }
        if(scope.state.liveMaterialPainted)
            RuntimeDiagnostics.mark("icon_live_native_content","3.0.1：原生动画内容入口已执行当前坐标 GPU 着色，不依赖内层 Drawable Hook")
        else RuntimeDiagnostics.mark("icon_live_native_fallback","本帧使用预制或自定义图像；实时资源未完成时由回调刷新")
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
        val module=api ?: return
        val renderManager=field(engine.javaClass,"mRenderManager")?.get(engine) ?: return
        val render=renderManager.javaClass.methods.firstOrNull {it.name=="renderIcon" &&
            it.parameterTypes.contentEquals(arrayOf(Canvas::class.java,Boolean::class.javaPrimitiveType)) &&
            it.returnType==Void.TYPE} ?: return
        if(nativeRenderMethods.add(render)) {
            module.hook(render).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(IconGlassNativeDrawScope.suppresses(chain.getThisObject(),chain.getArg(1)==true))null else chain.proceed()
            }
            mountNativePreviewCover(module,render)
        }
        runCatching {module.deoptimize(target)}.onFailure(::report)
        module.hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val host=nativeEngines[chain.getThisObject()]?.get()
            val drawable=host?.let {c.icon.get(it) as? Drawable}
            val bitmap=if(drawable!=null && c.fast.isInstance(drawable))c.bitmap.get(drawable) as? Bitmap else null
            if(main() && enabled && host!=null && bitmap!=null && LauncherIconGlassAssets.isGenerated(bitmap) &&
                inspect(host,state(host))) {
                val manager=field(chain.getThisObject().javaClass,"mRenderManager")?.get(chain.getThisObject())
                RuntimeDiagnostics.mark("icon_glass_native_engine","仅屏蔽旧主题节点，保留原生图标动画及返回值")
                IconGlassNativeDrawScope.run(manager) {
                    val intercepted=chain.proceed() as Boolean
                    val scope=owner.current()
                    val canvas=chain.getArg(0) as? Canvas
                    IconGlassNativeDrawScope.finish(intercepted,(liveMode || iconBounds(host)?.let {mayProtect(host,it)}==true) && scope!=null && scope.state.host.get()===host &&
                        scope.canvas===canvas && canvas?.isHardwareAccelerated==true && c.content!=null) {
                        // The animator did not take over: use its exact native content entry
                        // once, then tell drawIcon not to draw the cached TextView icon again.
                        c.content!!.invoke(host,canvas)
                    }
                }
            } else chain.proceed()
        }
    }

    private fun mountNativePreviewCover(module:XposedInterface,render:Method) {
        // Folder animations can repaint a cached icon during the same after-icon
        // pass. Filter that renderer at its own entry, outside the badge renderer.
        runCatching {
            val manager=render.declaringClass
            val type=Class.forName("com.android.launcher3.iconrender.impl.FolderIconPreviewRender",false,manager.classLoader)
            val draw=type.getDeclaredMethod("renderAfterIconDrawn",Canvas::class.java,manager)
            check(draw.returnType==Void.TYPE)
            module.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(IconGlassNativeDrawScope.owns(chain.getArg(1)))null else chain.proceed()
            }
            runCatching {module.deoptimize(render)}.onFailure(::report)
        }.onFailure(::report)
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
            if((!owned || (!liveMode && !SafeAreas.active(SafeAreas.Scene.DESKTOP))) && drawable!=null && canvas!=null && rect!=null &&
                runCatching {LauncherIconGlassAssets.drawCached(drawable,canvas,rect)}.getOrDefault(false))return@intercept null
            if(scope==null || scope.drawable!==chain.getThisObject() || scope.canvas!==canvas ||
                canvas==null || rect==null || !main())return@intercept chain.proceed()
            val painted=try {draw(scope,canvas,rect)} catch(t:Throwable) {report(t);false}
            if(painted || (enabled && fallback(scope,canvas,rect)))null else chain.proceed()
        }
    }
    private fun draw(scope:Draw,canvas:Canvas,rect:Rect):Boolean {
        scope.state.liveMaterialPainted=false
        if(IconGlassRecordingSources.isRecording())return false
        val c=contract ?: return false
        val state=scope.state;val view=state.host.get() ?: return false
        if(!enabled || !canvas.isHardwareAccelerated ||
            !IconGlassPolicy.validSize(rect.width().toFloat(),rect.height().toFloat()))return false
        val nativePaint=if(scope.drawable is BitmapDrawable)scope.drawable.paint else if(c.fast.isInstance(scope.drawable))
            (field(scope.drawable.javaClass,"mMonoPaint") ?: c.paint)?.get(scope.drawable) as? Paint else null
        val alpha=nativePaint?.alpha ?: scope.drawable.alpha
        if(alpha<=0)return true // Skip masks, wallpaper mapping and GPU work, but keep native text/badges.
        val raw=scope.source ?: (scope.drawable as? BitmapDrawable)?.bitmap ?:
            (if(c.fast.isInstance(scope.drawable))c.bitmap.get(scope.drawable) as? Bitmap else IconGlassRecordingSources.get(scope.drawable)) ?: return false
        val original=LauncherIconGlassAssets.original(raw)
        // Low-res placeholders must remain eligible for the launcher's real icon load.
        if(original.isRecycled || original.width<16 || original.height<16)return false
        if(!liveMode && !mayProtect(view,rect))LauncherIconGlassAssets.prefabFor(scope.item ?: view.tag)?.let {prefab ->
            LauncherIconGlassAssets.apply(view,scope.drawable,scope.item ?: view.tag,original,prefab)
            if(LauncherIconGlassAssets.drawCached(scope.drawable,canvas,rect))return true
            canvas.drawBitmap(prefab,null,rect,Paint(Paint.FILTER_BITMAP_FLAG).apply {this.alpha=scope.drawable.alpha});return true
        }
        observeScreen(view.context)
        val cachedArtwork=state.artwork[scope.drawable]
        val artworkVariant=IconGlassDynamicIcons.variant(scope.component?.packageName)
        val artwork=cachedArtwork?.takeIf {it.source===original && it.generation==original.generationId &&
            it.component==scope.component && it.userId==scope.userId && it.variant==artworkVariant} ?:
            IconGlassMasks.resolve(view.context,scope.component,scope.userId,original,artworkReady)?.let {
                Artwork(original,original.generationId,it,artworkVariant,scope.component,scope.userId)
                    .also {ready -> state.artwork[scope.drawable]=ready}
            } ?: return fallback(scope,canvas,rect)
        val mask=artwork.mask
        // Compare matrices within this same Canvas to remove an arbitrary recording origin.
        // Native compound-drawable placement and FastBitmapDrawable transforms stay included.
        val background=if(mask.passthrough)null else {
            if(!scope.base.invert(state.inverse))return false
            canvas.getMatrix(state.current)
            state.combined.setConcat(state.inverse,state.current)
            state.screenRect.set(rect)
            state.combined.mapRect(state.screenRect)
            state.global.reset();view.transformMatrixToGlobal(state.global)
            state.global.mapRect(state.screenRect)
            IconGlassWallpaper.resolve(view.context,state.screenRect,artworkReady)
        }
        val safeMapping=if(mask.passthrough)null else background?.safeMapping ?: IconGlassWallpaper.safeMapping(state.screenRect)
        val backdrop=background?.bitmap ?: IconGlassFallback.backdrop
        val crop=background?.sourceRect ?: RectF(0f,0f,8f,8f)
        val disabled=if(c.fast.isInstance(scope.drawable))c.disabled?.invoke(scope.drawable) as? Boolean ?: false else !view.isEnabled
        state.bounds.set(rect)
        val item=scope.item ?: view.tag
        val generated=if(LauncherIconGlassAssets.currentFor(raw,item))raw else LauncherIconGlassAssets.generated(original,item) ?:
            LauncherIconGlassAssets.request(original,mask.foreground,backdrop,crop,
                artwork.style(params),artworkReady,LauncherIconGlassAssets.identity(scope.item ?: view.tag),background?.sharpBitmap ?: backdrop)
        if(generated!=null) {
            if(raw!==generated)LauncherIconGlassAssets.apply(view,scope.drawable,scope.item ?: view.tag,original,generated)
            if(safeMapping==null && (!liveMode || background==null || mask.passthrough)) {
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
            artwork.style(params,alpha,disabled),background?.sharpBitmap ?: backdrop,safeMapping)
        state.liveMaterialPainted=liveMode && background!=null && result
        if(result && !rendered) {
            rendered=true
            RuntimeDiagnostics.mark("icon_glass_render","3.0.1：图标曲边使用清晰壁纸折射；图案采用浅色分层玻璃")
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
        val prefab=LauncherIconGlassAssets.prefabFor(scope.item ?: scope.state.host.get()?.tag) ?: LauncherIconGlassAssets.generated(raw,scope.item ?: scope.state.host.get()?.tag)
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
            suggestionCardPredicate=runCatching {
                val util=Class.forName("com.android.launcher3.card.LauncherCardUtil",false,loader)
                util.getMethod("isSuggestCard",Any::class.java).takeIf {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType==Boolean::class.javaPrimitiveType
                }
            }.getOrNull()
            fun register(card:View) {
                if(!card.isAttachedToWindow || card in suggestionRoots || !isSuggestionCard(card))return
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
            // ColorOS 16 delivers card views through TitleCardView without this wrapper.
            val wrapper=runCatching {
                Class.forName("com.android.launcher3.card.appsuggestnopadding.SuggestNoPaddingViewWrapper",false,loader)
            }.getOrNull()
            val cardField=wrapper?.let {field(it,"titleCardView")}
            val add=wrapper?.let {runCatching {it.getDeclaredMethod("onAddChildView",View::class.java)}.getOrNull()}
            if(add==null)runCatching {
                val attach=cardType.getDeclaredMethod("onAttachedToWindow")
                module.hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    val card=chain.getThisObject() as? View
                    if(main() && card!=null)runCatching {register(card)}.onFailure(::report)
                    result
                }
            }.onFailure(::report)
            if(cardField!=null && add!=null)module.hook(add).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
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
        if(liveMode || safeAreaMapper.desktop(view,bounds)!=null) {
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
                val prefab=LauncherIconGlassAssets.generated(source,view.tag)
                if(!liveMode && prefab!=null && !mayProtect(view,Rect(0,0,view.width,view.height))) {canvas.drawBitmap(prefab,null,Rect(0,0,view.width,view.height),Paint(Paint.FILTER_BITMAP_FLAG));return null}
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

    private fun prepareFolderItem(view:View,item:Any,ready:()->Unit) {
        val source=LauncherIconGlassAssets.sourceForItem(item) ?: return
        val meta=metadata(item)
        val position=IntArray(2);view.getLocationOnScreen(position)
        val size=minOf(view.width,view.height).coerceAtLeast(48).toFloat()
        val screen=RectF(position[0].toFloat(),position[1].toFloat(),position[0]+size,position[1]+size)
        val completed:()->Unit={ready();artworkReady()}
        val generated=LauncherIconGlassBinding.prepareBitmap(view.context,item,source,meta.component,meta.user,
            screen,params,completed,wallpaperReady=artworkReady) ?: return
        LauncherIconGlassAssets.primeItem(item,generated)
        RuntimeDiagnostics.mark("icon_folder_warm","已在文件夹关闭时准备首屏图标模型缓存；空闲分片，不抓屏")
    }

    /** Closed folder preview icons are manually painted by the manager, not child TextViews.
     * Bypass only its pre-rasterized preview cache while our visible folder owns the Canvas. */
    private fun mountFolderPreviews(module:XposedInterface,loader:ClassLoader) {
        runCatching {
            val manager=Class.forName("com.android.launcher3.folder.PreviewItemManager",false,loader)
            val big=runCatching {Class.forName("com.android.launcher3.folder.big.BigFolderPreviewItemManager",false,loader)}.getOrNull()
            val paramsType=Class.forName("com.android.launcher3.folder.PreviewItemDrawingParams",false,loader)
            val stacked=runCatching {Class.forName("com.android.launcher3.folder.big.stacked.BFPreviewItemDrawingParams",false,loader)}.getOrNull()
            val managerHost=field(manager,"mIcon") ?: error("Missing preview host")
            val intrinsic=field(manager,"mIntrinsicIconSize") ?: error("Missing preview size")
            val previewCaches=field(manager,"mPreviewCacheDrawables")
            val slots=IconGlassPreviewSlots(field(paramsType,"drawable") ?: error("Missing preview drawable"),
                field(paramsType,"item") ?: error("Missing preview item"),
                field(paramsType,"transX") ?: error("Missing preview x"),
                field(paramsType,"transY") ?: error("Missing preview y"),
                field(paramsType,"scale") ?: error("Missing preview scale"),stacked,stacked?.getMethod("getCurrentStackedParams"))
            val pageEntry = IconGlassFolderPreviewContract.pageEntry(manager)
            val cacheEntry = manager.getDeclaredMethod("drawCache",Canvas::class.java,Integer.TYPE,java.lang.Boolean::class.java)
            val paramsEntries=mutableListOf(pageEntry to 2)
            big?.declaredMethods?.firstOrNull {it.name=="drawParamsWithAlpha" && it.returnType==Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Canvas::class.java,java.util.ArrayList::class.java,
                    Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
            }?.let {paramsEntries.add(it to 1)}
            // Own the manager's real draw entry. This also covers closed folders whose parent
            // dispatch was compiled/inlined or whose preview RenderNode is already cached.
            val managers=listOfNotNull(manager,big)
            // ColorOS 16 can call BigFolderPreviewItemManager's primitive overload
            // directly; the boxed bridge is not guaranteed to see that draw.
            val drawEntries=managers.flatMap {type -> type.declaredMethods.filter {method ->
                method.name=="draw" && method.returnType==Void.TYPE && method.parameterCount==2 &&
                    method.parameterTypes[0]==Canvas::class.java &&
                    method.parameterTypes[1] in setOf(Boolean::class.javaPrimitiveType,java.lang.Boolean::class.java)
            }}
            check(drawEntries.isNotEmpty())
            for(draw in drawEntries) {
                module.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val canvas=chain.getArg(0) as? Canvas
                    val host=managerHost.get(chain.getThisObject()) as? View
                    val inherited=folderOwner.current()
                    if(inherited!=null && inherited.host===host && inherited.canvas===canvas)return@intercept chain.proceed()
                    var scope:FolderDraw?=null
                    if(main() && canvas?.isHardwareAccelerated==true && host!=null && !IconGlassRecordingSources.isRecording())runCatching {
                        preferences()
                        if(enabled && inspect(host,state(host),host.tag,true)) {
                            IconGlassFolderWarmup.observe(host,prefsRevision,IconGlassWallpaper.revision(),
                                {enabled && interactive && !locked && !powerSave},::prepareFolderItem)
                        }
                        if(enabled && inspect(host,state(host),host.tag,true)) {
                            val base=Matrix();canvas.getMatrix(base);scope=FolderDraw(host,canvas,base)
                        }
                    }.onFailure(::report)
                    folderOwner.run(scope) {chain.proceed()}
                }
            }
            // drawParams can inline its tiny drawCache predicate.
            managers.flatMap {it.declaredMethods.asList()}
                .filter {it.name in setOf("draw","drawParams","drawParamsWithAlpha","drawPreviewItem")}
                .forEach {runCatching {module.deoptimize(it)}.onFailure(::report)}
            module.hook(cacheEntry)
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val scope=folderOwner.current()
                    if(scope!=null && enabled && managerHost.get(chain.getThisObject())===scope.host &&
                        chain.getArg(0)===scope.canvas && (liveMode || !scope.prefabsReady))false else chain.proceed()
                }
            for((entry,itemsArg) in paramsEntries)module.hook(entry).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val scope=folderOwner.current()
                if(scope!=null && enabled && managerHost.get(chain.getThisObject())===scope.host && chain.getArg(0)===scope.canvas)runCatching {
                    scope.prefabsReady=false
                    val roots=chain.getArg(itemsArg) as? Iterable<*> ?: return@runCatching
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
                    RuntimeDiagnostics.mark("icon_glass_preview_slots","闭合文件夹和堆叠图标已逐槽接入")
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
        IconGlassLibraryRuntime.observe(app, libraryReady)
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    IconGlassLibraryRuntime.resume()
                }
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED).apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
            },
                Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) { report(t) }
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
    private val fields=WeakHashMap<Class<*>,MutableMap<String,Field?>>()
    @Synchronized private fun field(type:Class<*>,name:String):Field? {
        val cached=fields.getOrPut(type) {HashMap()}
        if(cached.containsKey(name))return cached[name]
        var current:Class<*>?=type
        while(current!=null) {
            val found=runCatching {current!!.getDeclaredField(name).apply {isAccessible=true}}.getOrNull()
            if(found!=null) {cached[name]=found;return found}
            current=current.superclass
        }
        // Missing monochrome fields are a normal case, not an exception per icon/frame.
        cached[name]=null
        return null
    }
}
