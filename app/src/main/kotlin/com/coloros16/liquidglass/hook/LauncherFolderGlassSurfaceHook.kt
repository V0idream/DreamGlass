/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.PixelFormat
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
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** New COS17 folder previews use BlurTransitionDrawable/OplusBlurProperties,
 * whereas the old mLayerBlurDrawable-only mapping misses them. The APK proves
 * FolderRoundImageView draws ONLY the folder background and its transition cover;
 * app icons/input are other FolderIcon children and are never intercepted here.
 * GPU backdrop input uses the OEM background bridge, with the shared preset.
 */
internal object LauncherFolderGlassSurfaceHook {
    private data class Contract(val view:Class<*>,val folder:Class<*>,val preview:Field,
        val backgroundView:Field,val info:Field,val spanX:Method,val spanY:Method,val radius:Method)
    private var contract:Contract?=null
    private var api:XposedInterface?=null
    private var bridge:Method?=null
    private val changing=ThreadLocal<Boolean>()
    private val states=WeakHashMap<View,State>()
    private var readAt=-1000L
    private var enabled=false
    private var snapshot:SharedGlassParams.Snapshot?=null
    private fun ui()=Looper.myLooper()==Looper.getMainLooper()

    /** Does not add a paid-card key: closed folders historically follow the
     * master/material switch, including their large desktop variants.
     */
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-readAt<500L)return
        readAt=now
        val prefs=api?.let(Prefs::read) ?: return
        enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,Prefs.DEFAULT_GLASS_ENABLE)
        if(enabled)snapshot=SharedGlassParams.read(prefs)
    }
    private fun eligible(view:View):Boolean {
        val c=contract ?: return false
        if(!c.view.isInstance(view))return false
        // Known RenderEffectDrawable and LayerBlurDrawable both report -3 in
        // this APK. An opaque theme bitmap would leave incorrect HWUI opacity
        // metadata when its draw is replaced, so that unknown path stays native.
        val native=(view as? ImageView)?.drawable ?: return false
        if(native.opacity==PixelFormat.OPAQUE)return false
        var parent=view.parent
        repeat(4) {
            val node=parent as? View ?: return false
            if(c.folder.isInstance(node))return runCatching {
                val preview=c.preview.get(node) ?: return@runCatching false
                val info=c.info.get(node) ?: return@runCatching false
                LauncherFolderSurfacePolicy.eligible(true,c.backgroundView.get(preview)===view,
                    c.spanX.invoke(info) as Int,c.spanY.invoke(info) as Int)
            }.getOrDefault(false)
            parent=node.parent
        }
        return false
    }
    private fun assign(view:View,effect:RenderEffect?) {
        val old=changing.get();changing.set(true)
        try {bridge!!.invoke(null,effect,view)} finally {
            if(old==null)changing.remove() else changing.set(old)
        }
    }
    private fun observe(view:View):State=states[view] ?: State(view).also {
        states[view]=it;view.viewTreeObserver.addOnPreDrawListener(it);view.addOnAttachStateChangeListener(it)
    }

    fun install(api:XposedInterface,loader:ClassLoader) {
        this.api=api;contract=null
        runCatching {
            val view=Class.forName("com.android.launcher3.folder.FolderRoundImageView",false,loader)
            val folder=Class.forName("com.android.launcher3.folder.FolderIcon",false,loader)
            val previewType=Class.forName("com.android.launcher3.folder.OplusPreviewBackground",false,loader)
            check(ImageView::class.java.isAssignableFrom(view) && View::class.java.isAssignableFrom(folder))
            val draw=view.getDeclaredMethod("onDraw",Canvas::class.java)
            check(draw.returnType==Void.TYPE)
            // These fields distinguish the dedicated background from icon ImageViews.
            check(view.getDeclaredField("mCoverDrawable").type==android.graphics.drawable.Drawable::class.java)
            check(view.getDeclaredField("mDrawCoverDrawable").type==Boolean::class.javaPrimitiveType)
            val preview=folder.getDeclaredField("mBackground").apply {check(type==previewType);isAccessible=true}
            val backgroundView=previewType.getDeclaredField("mBgView").apply {check(type==view);isAccessible=true}
            val info=folder.getDeclaredField("mInfo").apply {isAccessible=true}
            val sx=info.type.getMethod("getSpanX");val sy=info.type.getMethod("getSpanY")
            val radius=view.getDeclaredMethod("getMRadius")
            check(sx.returnType==Int::class.javaPrimitiveType && sy.returnType==Int::class.javaPrimitiveType &&
                radius.returnType==Float::class.javaPrimitiveType)
            val bridgeType=Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect",false,loader)
            val setter=bridgeType.getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            check(Modifier.isStatic(setter.modifiers));bridge=setter
            api.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(!ui() || changing.get()==true)return@intercept chain.proceed()
                val target=chain.getArg(1) as? View ?: return@intercept chain.proceed()
                // Native setup can precede layout/span ownership. Record its
                // effect for this exact background class before deciding to mount.
                if(contract?.view?.isInstance(target)!=true)return@intercept chain.proceed()
                val state=observe(target)
                state.nativeEffect=chain.getArg(0) as? RenderEffect
                // A native material reset may invalidate the assigned bridge. Let
                // it run, then the next pre-draw remounts the current GPU material.
                state.dirty=true
                chain.proceed()
            }
            api.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(!ui() || !(chain.getArg(0) as Canvas).isHardwareAccelerated)return@intercept chain.proceed()
                val target=chain.getThisObject() as View
                val state=states[target] ?: if(eligible(target))observe(target) else null
                if(state==null)return@intercept chain.proceed()
                val alreadyApplied=state.active
                state.sync()
                // First mounting frame keeps the old native image. Thereafter
                // only this proven background-only ImageView's paint is replaced.
                if(alreadyApplied && state.active)null else chain.proceed()
            }
            api.deoptimize(draw)
            contract=Contract(view,folder,preview,backgroundView,info,sx,sy,radius)
            RuntimeDiagnostics.mark("launcher-large-folder-contract","已识别大文件夹专用背景View；等待GPU背景桥实际挂载，原生图标保持独立")
        }.onFailure {
            contract=null
            RuntimeDiagnostics.mark("launcher-large-folder-contract","当前大文件夹结构未匹配，保留原生：${it.javaClass.simpleName}")
        }
    }

    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        private val target=WeakReference(view)
        private val material by lazy {AppGlassEffect()}
        var active=false
        var dirty=false
        var nativeEffect:RenderEffect?=null
        private var effect:RenderEffect?=null
        private var hash=0
        private var blockedUntil=0L
        fun sync() {
            val view=target.get() ?: return
            try {
                preferences()
                if(!enabled || !eligible(view) || !view.isShown || !view.isHardwareAccelerated ||
                    view.width<=0 || view.height<=0 || SystemClock.uptimeMillis()<blockedUntil) {
                    restore(view);return
                }
                val c=contract ?: return
                val radius=(c.radius.invoke(view) as Float).takeIf {it.isFinite() && it>=0f} ?: return
                val config=snapshot ?: return
                val key=System.identityHashCode(view)
                GlassRegistry.bind(key,view,"com.android.launcher3.folder.FolderRoundImageView")
                val params=SharedGlassParams.resolve(config.values,config.preset,GlassRegistry.overridesFor(key),
                    view.width.toFloat(),view.height.toFloat(),GlassRegistry.cornerOverrideFor(key) ?: radius,
                    false,config.compactOptics,config.grayBaseLuma)
                if(effect==null || hash!=params.hashCode()) {
                    effect=material.create(params,RectF(0f,0f,view.width.toFloat(),view.height.toFloat()))
                    hash=params.hashCode();dirty=true
                }
                if(!active || dirty) {
                    assign(view,effect);active=true;dirty=false;view.invalidate()
                    RuntimeDiagnostics.mark("launcher-large-folder-material","大文件夹背景已提交统一GPU预设；应用图标与输入保持原生，待视觉验收")
                }
            } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+30_000L
                runCatching {restore(view)}
                RuntimeDiagnostics.mark("launcher-large-folder-error","GPU材质暂退原生：${error.javaClass.simpleName}")
            }
        }
        private fun restore(view:View) {
            try {if(active)assign(view,nativeEffect)} finally {
                val wasActive=active;active=false;dirty=false;effect=null;hash=0
                if(wasActive)view.invalidate()
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
