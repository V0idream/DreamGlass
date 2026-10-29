/* DreamGlass contributors, 2026-10-30. GPL-3.0. OEM GPU backdrop adapter. */
package com.coloros16.liquidglass.hook
import android.graphics.Outline
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
        XposedEntry.PACKAGE_CALCULATOR to Prefs.KEY_CALCULATOR_GLASS,
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
    private var snapshot:SharedGlassParams.Snapshot?=null
    fun install(api:XposedInterface,classLoader:ClassLoader,packageName:String) {
        this.api=api;this.packageName=packageName;switchKey=keys[packageName] ?: return
        GlassRegistry.attach { Prefs.read(api) }
        try {
            val type=Class.forName(BRIDGE,false,classLoader)
            val method=type.getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            check(Modifier.isStatic(method.modifiers));bridge=method
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(changing || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getArg(1) as? View ?: return@intercept chain.proceed()
                val original=chain.getArg(0) as? RenderEffect
                var state:State?=null
                var replacement:RenderEffect?=null
                try { state=observe(view);state?.nativeEffect=original;replacement=state?.effect() }
                catch(error:Throwable) { failure(error) }
                if(replacement!=null) {
                    val args=chain.getArgs().toTypedArray();args[0]=replacement
                    val result=chain.proceed(args)
                    state?.applied=true
                    RuntimeDiagnostics.mark("app-glass-material","原生背景输入已接入统一 GPU 材质；前景保持原生")
                    return@intercept result
                }
                chain.proceed()
            }
            RuntimeDiagnostics.mark("app-glass-capability","已安装 OEM 背景通道，等待组件实际命中")
        } catch(error:Throwable) {
            RuntimeDiagnostics.mark("app-glass-capability","缺少 OEM 背景通道，保留原生；不叠加假玻璃描边")
            failure(error);return
        }
        for(method in listOf(View::class.java.getDeclaredMethod("setBackgroundDrawable",Drawable::class.java),
            View::class.java.getDeclaredMethod("onAttachedToWindow"))) {
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                if(!changing && Looper.myLooper()==Looper.getMainLooper())try {
                    val view=chain.getThisObject() as? View
                    if(view!=null && isBlur(view.background))observe(view)
                } catch(error:Throwable) { failure(error) }
                result
            }
        }
    }
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-readAt<500)return
        readAt=now
        val p=api?.let(Prefs::read) ?: return
        enabled=p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_GLASS_ENABLE,true) && p.getBoolean(switchKey,false)
        if(enabled)snapshot=SharedGlassParams.read(p)
    }
    private fun id(view:View)=runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    private fun isBlur(drawable:Drawable?,depth:Int=0):Boolean {
        if(drawable==null || depth>4)return false
        val name=drawable.javaClass.name
        if(name.contains("BlurDrawable") || name.startsWith("com.oplus.posteffect.drawable."))return true
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
    private fun role(view:View):Int {
        val dm=view.resources.displayMetrics
        if(view.width>=dm.widthPixels*.9f && view.height>=dm.heightPixels*.65f)return AppGlassSurfacePolicy.NONE
        val hint=if(packageName==XposedEntry.PACKAGE_SMART_SIDEBAR)"/sidebar" else if(packageName==XposedEntry.PACKAGE_ASSISTANT_SCREEN)"/assistant" else ""
        return AppGlassSurfacePolicy.role(view.javaClass.name+hint,id(view),view.width.toFloat(),view.height.toFloat(),
            dm.widthPixels.toFloat(),dm.heightPixels.toFloat(),view is ViewGroup,view.isClickable,hasControl(view))
    }
    private fun observe(view:View):State? {
        if(states[view]!=null)return states[view]
        preferences();if(states.size>=96)return null
        val state=State(view);states[view]=state
        GlassRegistry.bind(System.identityHashCode(view),view,view.javaClass.name)
        view.viewTreeObserver.addOnPreDrawListener(state);view.addOnAttachStateChangeListener(state)
        return state
    }
    private fun assign(view:View,effect:RenderEffect?) {
        changing=true
        try { bridge?.invoke(null,effect,view) } finally { changing=false }
    }
    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        val target=WeakReference(view)
        var nativeEffect:RenderEffect?=null
        var applied=false
        private var paramsHash=0
        private var cached:RenderEffect?=null
        private val material by lazy { AppGlassEffect() }
        private var blockedUntil=0L
        private var lastApplied:RenderEffect?=null
        private var checkedAt=-1000L
        private var checkedWidth=0;private var checkedHeight=0;private var checkedBackground=0
        fun effect():RenderEffect? {
          try {
            preferences()
            val view=target.get() ?: return null
            if(!enabled || SystemClock.uptimeMillis()<blockedUntil || !view.isHardwareAccelerated)return null
            val now=SystemClock.uptimeMillis();val backgroundId=System.identityHashCode(view.background)
            if(now-checkedAt<250L && checkedWidth==view.width && checkedHeight==view.height && checkedBackground==backgroundId)return cached
            checkedAt=now;checkedWidth=view.width;checkedHeight=view.height;checkedBackground=backgroundId
            val kind=role(view);if(kind==AppGlassSurfacePolicy.NONE) { cached=null;return null }
            val s=snapshot ?: return null
            val outline=Outline();view.background?.getOutline(outline)
            if(outline.isEmpty)view.outlineProvider?.getOutline(view,outline)
            val key=System.identityHashCode(view)
            val radius=GlassRegistry.cornerOverrideFor(key) ?: if(outline.radius>0)outline.radius else
                if(kind==AppGlassSurfacePolicy.CONTROL)minOf(view.width,view.height)*.5f else minOf(view.width,view.height)*.22f
            val p=SharedGlassParams.resolve(s.values,s.preset,GlassRegistry.overridesFor(key),
                view.width.toFloat(),view.height.toFloat(),radius,kind==AppGlassSurfacePolicy.CONTROL)
            val hash=p.hashCode()
            if(cached==null || paramsHash!=hash) { cached=material.create(p);paramsHash=hash }
            return cached
          } catch(error:Throwable) {
            blockedUntil=SystemClock.uptimeMillis()+30_000L;failure(error);return null
          }
        }
        private fun restore(view:View) {
            if(!applied)return
            assign(view,nativeEffect);applied=false;cached=null;paramsHash=0
        }
        override fun onPreDraw():Boolean {
            val view=target.get() ?: return true
            try {
                val effect=if(view.isShown && view.windowVisibility==View.VISIBLE)effect() else null
                if(effect==null)restore(view)
                else if(!applied || lastApplied!==effect) {
                    assign(view,effect);lastApplied=effect;applied=true
                    RuntimeDiagnostics.mark("app-glass-material","组件已使用统一预设；未添加工具栏外框")
                }
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
    private fun failure(error:Throwable) { Lg.w("AppGlass","native backdrop retained after adapter failure",error) }
}
