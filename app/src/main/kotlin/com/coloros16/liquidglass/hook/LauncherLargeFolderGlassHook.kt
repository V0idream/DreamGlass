/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.glass.SharedGlassParams
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** COS17 big folders use BlurTransitionDrawable, bypassing the legacy LayerBlurDrawable map.
 * Only their dedicated ImageView backdrop is replaced. Icon previews and touch handling remain
 * native. The OEM background node supplies live pixels without a screenshot or bitmap cache. */
internal object LauncherLargeFolderGlassHook {
    private var api:XposedInterface?=null
    private var background:Class<*>?=null
    private var folder:Class<*>?=null
    private var isBig:Method?=null
    private var radius:Method?=null
    private var setter:Method?=null
    private var readAt=-1000L
    private var enabled=false
    private var config:SharedGlassParams.Snapshot?=null
    private val states=WeakHashMap<View,State>()
    private val changing=ThreadLocal<Boolean>()
    private fun ui()=Looper.myLooper()==Looper.getMainLooper()
    private fun eligible(view:View):Boolean {
        if(view.javaClass!=background)return false
        var parent=view.parent
        repeat(5) {
            val node=parent as? View ?: return false
            if(folder?.isInstance(node)==true)return isBig?.invoke(node)==true
            parent=node.parent
        }
        return false
    }
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-readAt<500L)return
        readAt=now
        val prefs=api?.let(Prefs::read) ?: return
        enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,Prefs.DEFAULT_GLASS_ENABLE)
        if(enabled)config=SharedGlassParams.read(prefs)
    }
    private fun assign(view:View,effect:RenderEffect?) {
        val previous=changing.get();changing.set(true)
        try {setter!!.invoke(null,effect,view)} finally {
            if(previous==null)changing.remove() else changing.set(previous)
        }
    }
    private fun state(view:View)=states[view] ?: State(view).also {
        states[view]=it;view.viewTreeObserver.addOnPreDrawListener(it);view.addOnAttachStateChangeListener(it)
    }
    fun install(module:XposedInterface,loader:ClassLoader) {
        api=module
        runCatching {
            val bg=Class.forName("com.android.launcher3.folder.FolderRoundImageView",false,loader)
            val big=Class.forName("com.android.launcher3.folder.FlexibleFolderIcon",false,loader)
            check(ImageView::class.java.isAssignableFrom(bg) && View::class.java.isAssignableFrom(big))
            val checkBig=big.getMethod("isBigFolderIcon");check(checkBig.returnType==Boolean::class.javaPrimitiveType)
            val getRadius=bg.getMethod("getMRadius");check(getRadius.returnType==Float::class.javaPrimitiveType)
            val bridge=Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect",false,loader)
                .getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            check(Modifier.isStatic(bridge.modifiers))
            background=bg;folder=big;isBig=checkBig;radius=getRadius;setter=bridge
            module.hook(bridge).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(ui() && changing.get()!=true) {
                    val view=chain.getArg(1) as? View
                    if(view!=null && view.javaClass==background)state(view).let {
                        it.nativeEffect=chain.getArg(0) as? RenderEffect;it.dirty=true
                    }
                }
                chain.proceed()
            }
            module.hook(bg.getDeclaredMethod("onDraw",Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val view=chain.getThisObject() as? ImageView
                    val canvas=chain.getArg(0) as? Canvas
                    // Keep native image/bounds/state intact. Skip just the backdrop paint
                    // after a valid OEM live-background effect has been submitted.
                    val painted=if(ui() && view!=null && canvas?.isHardwareAccelerated==true)
                        runCatching {state(view).sync()}.getOrDefault(false) else false
                    if(painted)null else chain.proceed()
                }
            RuntimeDiagnostics.mark("large_folder_glass_contract","大文件夹独立背景入口已安装；使用实时原生背景节点")
        }.onFailure {
            background=null
            RuntimeDiagnostics.update("large_folder_glass_fallback","结构未匹配，保留原生：${it.javaClass.simpleName}")
        }
    }
    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        private val host=WeakReference(view)
        private val material by lazy {AppGlassEffect()}
        var nativeEffect:RenderEffect?=null
        var dirty=true
        private var active=false
        private var effect:RenderEffect?=null
        private var effectHash=0
        private var blockedUntil=0L
        fun sync():Boolean {
            val view=host.get() ?: return false
            return try {
                preferences()
                if(!enabled || !eligible(view) || !view.isShown || !view.isAttachedToWindow ||
                    !view.isHardwareAccelerated || view.width<=0 || view.height<=0 ||
                    SystemClock.uptimeMillis()<blockedUntil || !NativeSurfaceBackgroundLease.canMount(view)) {
                    restore(view);return false
                }
                val snapshot=config ?: return false
                val corner=(radius!!.invoke(view) as Float).takeIf {it.isFinite() && it>=0f} ?: return false
                val bounds=RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
                val key=System.identityHashCode(view)
                GlassRegistry.bind(key,view,"com.android.launcher3.folder.FolderRoundImageView")
                val params=SharedGlassParams.resolve(snapshot.values,snapshot.preset,GlassRegistry.overridesFor(key),
                    bounds.width(),bounds.height(),GlassRegistry.cornerOverrideFor(key) ?: corner,
                    false,snapshot.compactOptics,snapshot.grayBaseLuma)
                val hash=params.hashCode()
                if(effect==null || effectHash!=hash) {effect=material.create(params,bounds);effectHash=hash;dirty=true}
                NativeSurfaceBackgroundLease.sync(view,true)
                check(NativeSurfaceBackgroundLease.owns(view))
                if(!active || dirty) {
                    assign(view,effect);active=true;dirty=false
                    RuntimeDiagnostics.mark("large_folder_glass_material","大文件夹背景已提交统一玻璃；实时输入，无抓屏，待真机视觉验收")
                }
                true
            } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+30_000L
                runCatching {restore(view)}
                RuntimeDiagnostics.update("large_folder_glass_fallback","保留原生：${error.javaClass.simpleName}")
                false
            }
        }
        private fun restore(view:View) {
            try {if(active || NativeSurfaceBackgroundLease.owns(view))assign(view,nativeEffect)} finally {
                active=false;dirty=true;effect=null;effectHash=0;NativeSurfaceBackgroundLease.sync(view,false)
            }
        }
        override fun onPreDraw():Boolean {sync();return true}
        override fun onViewAttachedToWindow(view:View) {}
        override fun onViewDetachedFromWindow(view:View) {
            runCatching {restore(view)}
            view.viewTreeObserver.removeOnPreDrawListener(this);view.removeOnAttachStateChangeListener(this);states.remove(view)
        }
    }
}
