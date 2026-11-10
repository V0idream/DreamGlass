/* DreamGlass contributors, 2026-10-30. GPL-3.0. OEM GPU backdrop adapter. */
package com.coloros16.liquidglass.hook
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.entry.XposedEntry
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.glass.SharedGlassParams
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Filters OEM backdrop input, never foreground text/icons. B-family native SF backgrounds
 * request the same channel. No screen capture, CPU blur or fake white-ring overlay.
 * Missing OEM capability keeps the native material and reports that limitation.
 */
object AppGlassHook {
    private const val BRIDGE="com.oplus.view.OplusViewBackgroundRenderEffect"
    private val keys=mapOf(XposedEntry.PACKAGE_GALLERY to Prefs.KEY_GALLERY_GLASS,
        XposedEntry.PACKAGE_SMART_SIDEBAR to Prefs.KEY_SMART_SIDEBAR_GLASS,
        XposedEntry.PACKAGE_ASSISTANT_SCREEN to Prefs.KEY_ASSISTANT_SCREEN_GLASS)
    private val states=WeakHashMap<View,State>()
    private var api:XposedInterface?=null
    private var bridge:Method?=null
    private var switchKey=""
    private var packageName=""
    private var changing=false
    private var readAt=-1000L
    private var enabled=false
    private var memberAccepted=false
    private var snapshot:SharedGlassParams.Snapshot?=null
    fun install(api:XposedInterface,classLoader:ClassLoader,packageName:String) {
        val key=keys[packageName] ?: return
        this.api=api;this.packageName=packageName;switchKey=key
        if(packageName==XposedEntry.PACKAGE_GALLERY)GalleryLiveBackdrop.install()
        GlassRegistry.attach { Prefs.read(api) }
        NativeAppBackdropHook.install(api,classLoader)
        try {
            val type=Class.forName(BRIDGE,false,classLoader)
            val method=type.getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            check(Modifier.isStatic(method.modifiers));bridge=method
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(changing || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getArg(1) as? View ?: return@intercept chain.proceed()
                if(NativeAppBackdropHook.owns(view.background))return@intercept chain.proceed()
                val original=chain.getArg(0) as? RenderEffect
                var state:State?=null
                var replacement:RenderEffect?=null
                try { state=observe(view);state?.nativeEffect=original;replacement=state?.effect() }
                catch(error:Throwable) { failure(error) }
                if(replacement!=null) {
                    val args=chain.getArgs().toTypedArray();args[0]=if(state?.liveContent==true)null else replacement
                    if(!nativeMutation {Cos17AppSurfaceHooks.beforeAssign(view,true)})return@intercept chain.proceed()
                    try {
                        state?.clip(view)
                        val result=chain.proceed(args)
                        state?.applied=true
                        if(state?.liveContent==true)GalleryLiveBackdrop.activate(view)
                        nativeMutation {Cos17AppSurfaceHooks.onMaterialStateChanged(view,true)}
                        RuntimeDiagnostics.mark("app-glass-material","原生背景输入已接入统一 GPU 材质；前景保持原生")
                        return@intercept result
                    } catch(error:Throwable) {
                        // Do not retry the native callback after an exception.
                        runCatching {state?.restore(view)}.onFailure(::failure)
                        throw error
                    }
                }
                chain.proceed()
            }
            RuntimeDiagnostics.mark("app-glass-capability","已安装 OEM 背景通道，等待组件实际命中")
        } catch(error:Throwable) {
            bridge=null
            RuntimeDiagnostics.mark("app-glass-capability",if(packageName==XposedEntry.PACKAGE_GALLERY)
                "OEM背景通道不可用；继续安装相册独立实时内容入口，其他材质保留原生"
                else "缺少 OEM 背景通道，保留原生；不叠加假玻璃描边")
            failure(error)
            // Gallery's independent Activity RenderNode -> panel content route
            // does not call the OEM background filter. Do not make its install
            // contingent on an unrelated optional bridge or its hookability.
            if(packageName!=XposedEntry.PACKAGE_GALLERY)return
        }
        Cos17AppSurfaceHooks.install(api,classLoader,packageName)
        for(method in listOf(View::class.java.getDeclaredMethod("setBackgroundDrawable",Drawable::class.java),
            View::class.java.getDeclaredMethod("onAttachedToWindow"))) {
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                if(!changing && Looper.myLooper()==Looper.getMainLooper())try {
                    val view=chain.getThisObject() as? View
                    if(view!=null && !NativeAppBackdropHook.owns(view.background) &&
                        (isBlur(view.background) || Cos17AppSurfaceHooks.isTarget(view)))observe(view)
                } catch(error:Throwable) { failure(error) }
                result
            }
        }
    }
    private fun preferences() {
        memberAccepted=Prefs.memberAllowed(api)
        if(!memberAccepted) {enabled=false;readAt=-1000L;return}
        val now=SystemClock.uptimeMillis();if(now-readAt<500)return
        readAt=now
        val p=api?.let(Prefs::read) ?: return
        enabled=p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_GLASS_ENABLE,true) && p.getBoolean(switchKey,false)
        RuntimeDiagnostics.update("app-glass-enabled",if(enabled)"开启" else "关闭")
        if(enabled)snapshot=SharedGlassParams.read(p)
    }
    internal fun materialApplied(view:View):Boolean = states[view]?.applied==true && Prefs.memberAllowed(api)
    /** Called only with the actual panel returned by the Gallery dialog builder,
     * not a class-name scan over unrelated activities or windows. */
    internal fun observeGalleryDialog(view:View) {
        if(packageName!=XposedEntry.PACKAGE_GALLERY || Looper.myLooper()!=Looper.getMainLooper() ||
            !GalleryNativeSurfaceContract.isDialogPanel(view))return
        val observed=observe(view)!=null
        RuntimeDiagnostics.update("gallery-save-observer",if(observed)
            "已直接观察实际保存对话框parentPanel；等待本窗口preDraw配置/源检查"
            else "实际parentPanel未能登记：观察表容量或宿主契约限制")
        if(observed)view.invalidate()
    }
    internal fun nativeParams(view:View,width:Float,height:Float):com.coloros16.liquidglass.liquidglass.LiquidGlassShader.Params? {
        preferences();if(!enabled || role(view)==AppGlassSurfacePolicy.NONE)return null
        val s=snapshot ?: return null
        val key=System.identityHashCode(view)
        GlassRegistry.bind(key,view,view.javaClass.name)
        val outline=Outline();view.background?.getOutline(outline)
        val radius=GlassRegistry.cornerOverrideFor(key) ?: if(outline.radius>0)outline.radius else minOf(width,height)*.22f
        return SharedGlassParams.resolve(s.values,s.preset,GlassRegistry.overridesFor(key),width,height,radius,
            role(view)==AppGlassSurfacePolicy.CONTROL,s.compactOptics,s.grayBaseLuma)
    }
    private fun id(view:View)=runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    private fun isBlur(drawable:Drawable?,depth:Int=0):Boolean {
        if(drawable==null || depth>4)return false
        var type:Class<*>?=drawable.javaClass
        while(type!=null && type!=Drawable::class.java) {
            val name=type.name
            if(name.contains("BlurDrawable") || name.startsWith("com.oplus.posteffect.drawable."))return true
            type=type.superclass
        }
        if(drawable is LayerDrawable)return (0 until drawable.numberOfLayers).any { isBlur(drawable.getDrawable(it),depth+1) }
        val current=drawable.current
        return current!==drawable && isBlur(current,depth+1)
    }
    private fun hasControl(view:View,depth:Int=0):Boolean {
        if(view !is ViewGroup || depth>4)return false
        for(i in 0 until view.childCount) {
            val child=view.getChildAt(i)
            if(child.isClickable || hasControl(child,depth+1))return true
        }
        return false
    }
    private fun canObserve(view:View)=when(packageName) {
        XposedEntry.PACKAGE_GALLERY,XposedEntry.PACKAGE_ASSISTANT_SCREEN ->
            Cos17AppSurfaceHooks.isTarget(view)
        else -> true
    }
    private fun role(view:View):Int {
        val dm=view.resources.displayMetrics
        // Vendor bridges may be called for both parent and child. Limit these
        // these apps to proven material owners; never filter an icon twice.
        if(!canObserve(view))return AppGlassSurfacePolicy.NONE
        // A verified dialog host may include native transparent outer margins.
        // Its exact inner bounds, not the whole host size, define the material.
        Cos17AppSurfaceHooks.nativeRole(view)?.let {return it}
        if(view.width>=dm.widthPixels*.9f && view.height>=dm.heightPixels*.65f)return AppGlassSurfacePolicy.NONE
        val hint=if(packageName==XposedEntry.PACKAGE_SMART_SIDEBAR)"/sidebar" else if(packageName==XposedEntry.PACKAGE_ASSISTANT_SCREEN)"/assistant" else ""
        return AppGlassSurfacePolicy.role(view.javaClass.name+hint,id(view),view.width.toFloat(),view.height.toFloat(),
            dm.widthPixels.toFloat(),dm.heightPixels.toFloat(),view is ViewGroup,view.isClickable,hasControl(view),
            nativeCard=Cos17AppSurfaceHooks.isNativeCard(view))
    }
    private fun observe(view:View):State? {
        // Unrelated native blur calls must not exhaust the bounded owner table.
        if(!canObserve(view))return null
        if(states[view]!=null)return states[view]
        preferences();if(states.size>=96)return null
        val state=State(view);states[view]=state
        GlassRegistry.bind(System.identityHashCode(view),view,view.javaClass.name)
        view.viewTreeObserver.addOnPreDrawListener(state);view.addOnAttachStateChangeListener(state)
        return state
    }
    private inline fun <T> nativeMutation(block:()->T):T {
        val previous=changing;changing=true
        try {return block()} finally {changing=previous}
    }
    private fun assign(view:View,effect:RenderEffect?)=nativeMutation { bridge?.invoke(null,effect,view) }
    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        val target=WeakReference(view)
        var nativeEffect:RenderEffect?=null
        var applied=false
        private var paramsHash=0
        private var cached:RenderEffect?=null
        private val material by lazy { AppGlassEffect() }
        private var blockedUntil=0L
        private var lastApplied:RenderEffect?=null
        private var materialBounds:RectF?=null
        private var materialRadius=0f
        var liveContent=false;private set
        private fun gate(view:View,detail:String) {
            if(packageName==XposedEntry.PACKAGE_GALLERY && GalleryNativeSurfaceContract.isDialogPanel(view))
                RuntimeDiagnostics.update("gallery-save-gate",detail)
        }
        fun effect():RenderEffect? {
          try {
            preferences()
            val view=target.get() ?: return null
            liveContent=packageName==XposedEntry.PACKAGE_GALLERY && GalleryLiveBackdrop.needs(view)
            if(!enabled) {gate(view,"关闭：会员确认=$memberAccepted；总开关/玻璃/相册联合开启=$enabled");return null}
            if(SystemClock.uptimeMillis()<blockedUntil) {gate(view,"此前渲染异常，正在原生回退退避期");return null}
            if(!view.isHardwareAccelerated) {gate(view,"原生回退：宿主不是硬件加速窗口");return null}
            if(!liveContent && bridge==null)return null
            if(!GallerySurfaceAdapter.canAssign(view)) {gate(view,"原生回退：独立绘制入口或安全背景租用尚未就绪");return null}
            val kind=role(view);if(kind==AppGlassSurfacePolicy.NONE) { cached=null;gate(view,"保持原生：宿主角色不在已核对材质范围");return null }
            val s=snapshot ?: return null
            val outline=Outline();view.background?.getOutline(outline)
            if(outline.isEmpty)view.outlineProvider?.getOutline(view,outline)
            val outlineRect=Rect()
            val nativeBounds=nativeMutation {Cos17AppSurfaceHooks.nativeBounds(view)}
            if(Cos17AppSurfaceHooks.isTarget(view) && nativeBounds==null) {gate(view,"保持原生：原生面板几何未就绪");return null}
            val area=nativeBounds ?: if(outline.getRect(outlineRect) && !outlineRect.isEmpty)RectF(outlineRect)
                else RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
            if(area.isEmpty || !listOf(area.left,area.top,area.right,area.bottom).all {it.isFinite()}) {gate(view,"保持原生：当前面板范围为空或非有限值");return null}
            val opacity=Cos17AppSurfaceHooks.nativeFillAlpha(view)
            if(opacity<=0f) {gate(view,"保持原生：原生面板当前透明度为零");return null}
            val key=System.identityHashCode(view)
            val radius=GlassRegistry.cornerOverrideFor(key) ?: Cos17AppSurfaceHooks.nativeRadius(view) ?: if(outline.radius>0)outline.radius else
                if(kind==AppGlassSurfacePolicy.CONTROL)minOf(area.width(),area.height())*.5f else minOf(area.width(),area.height())*.22f
            val p=SharedGlassParams.resolve(s.values,s.preset,GlassRegistry.overridesFor(key),
                area.width(),area.height(),radius.coerceIn(0f,minOf(area.width(),area.height())*.5f),kind==AppGlassSurfacePolicy.CONTROL,s.compactOptics,s.grayBaseLuma)
            materialBounds=RectF(area);materialRadius=p.cornerRadius
            val hash=31*(31*p.hashCode()+area.hashCode())+opacity.hashCode()
            if(liveContent) {
                cached=GalleryLiveBackdrop.prepare(view,area,p,opacity) ?: run {
                    gate(view,"保持原生：独立内容源未就绪，详见gallery-save-owner/source");return null
                }
                paramsHash=hash
                gate(view,"允许：会员确认、开关、硬件、宿主与独立实时内容源均通过")
                return cached
            }
            if(cached==null || paramsHash!=hash) {
                cached=material.create(p,area,opacity);paramsHash=hash
                if(com.coloros16.liquidglass.BuildConfig.DEBUG && GalleryNativeSurfaceContract.matches(view))
                    RuntimeDiagnostics.mark("gallery-geometry-${view.javaClass.simpleName}",
                        "host=${view.width}x${view.height}; local=$area; radius=${p.cornerRadius}; scroll=${view.scrollX},${view.scrollY}; fixed-mask; backdrop=OEM")
            }
            return cached
          } catch(error:Throwable) {
            blockedUntil=SystemClock.uptimeMillis()+30_000L;failure(error);return null
          }
        }
        fun clip(view:View) {
            if(!liveContent && GalleryNativeSurfaceContract.matches(view))materialBounds?.let {
                NativeSurfaceClipLease.sync(view,it,materialRadius)
            }
        }
        fun restore(view:View) {
            try {if(applied && bridge!=null)assign(view,nativeEffect)} finally {
                // Always release native channels even when the OEM bridge fails.
                applied=false;cached=null;paramsHash=0;lastApplied=null
                runCatching {GalleryLiveBackdrop.release(view)}.onFailure(::failure)
                runCatching {NativeSurfaceClipLease.restore(view)}.onFailure(::failure)
                runCatching {nativeMutation {Cos17AppSurfaceHooks.onMaterialStateChanged(view,false)}}.onFailure(::failure)
            }
        }
        override fun onPreDraw():Boolean {
            val view=target.get() ?: return true
            try {
                val effect=if(view.isShown && view.windowVisibility==View.VISIBLE)effect() else null
                if(effect==null)restore(view)
                else if(!applied || lastApplied!==effect) {
                    if(nativeMutation {Cos17AppSurfaceHooks.beforeAssign(view,true)}) {
                        clip(view)
                        // A live panel owns its independent content node. Clear
                        // an existing OEM filter only if that optional bridge
                        // exists; missing it must not prevent the actual paint.
                        if(!liveContent || bridge!=null)assign(view,if(liveContent)null else effect)
                        lastApplied=effect;applied=true
                        if(liveContent)GalleryLiveBackdrop.activate(view)
                        RuntimeDiagnostics.mark("app-glass-material","组件已使用统一预设；未添加工具栏外框")
                    } else restore(view)
                }
                if(applied)nativeMutation {clip(view);Cos17AppSurfaceHooks.onMaterialStateChanged(view,true)}
            } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+30_000L
                runCatching { restore(view) };failure(error)
            }
            return true
        }
        override fun onViewAttachedToWindow(v:View) {}
        override fun onViewDetachedFromWindow(v:View) {
            runCatching { restore(v) };v.viewTreeObserver.removeOnPreDrawListener(this)
            v.removeOnAttachStateChangeListener(this);states.remove(v)
        }
    }
    private fun failure(error:Throwable) {
        RuntimeDiagnostics.mark("app-glass-error","${error.javaClass.simpleName}: ${error.message}")
        Lg.w("AppGlass","native backdrop retained after adapter failure",error)
    }
}
