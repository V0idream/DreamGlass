/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.RelativeLayout
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.glass.SharedGlassParams
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** COS17's verified clear-all material owner. Keeps the native RenderNode's live
 * background input via its existing LayerBlurDrawable; no CPU blur, wallpaper/screenshot, task deletion or callback
 * interception. Original click, text, src icon, native alpha/scale and touch
 * remain native. Independent of the incompatible COS16 PressFeedbackHandler. */
internal object RecentsClearButtonGlassHook {
    private data class Contract(val button:Class<*>,val panel:Class<*>,val slot:Field,
        val getButton:Method,val bounds:Method)
    private var contract:Contract?=null
    private var api:XposedInterface?=null
    private val states=WeakHashMap<View,State>()
    private val nativeDraw=ScopedDrawOwner<View>()
    private val readCadence=ClearButtonReadCadence()
    private var enabled=false
    private var snapshot:SharedGlassParams.Snapshot?=null
    private fun ui()=Looper.myLooper()==Looper.getMainLooper()
    private fun mark(stage:String,value:String)=RuntimeDiagnostics.mark("recents-clear-glass-$stage",value)
    private fun preferences(entering:Boolean=false) {
        val now=SystemClock.uptimeMillis();if(!readCadence.shouldRead(now,entering))return
        val p=api?.let(Prefs::read) ?: return
        // A missing framework response is NOT a successful cache refresh. The
        // first onDraw may retry in this same frame instead of waiting 500ms.
        // A basic control, like QS buttons: free and follows the shared switches.
        enabled=p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_GLASS_ENABLE,Prefs.DEFAULT_GLASS_ENABLE)
        if(enabled)snapshot=SharedGlassParams.read(p)
        readCadence.succeeded(now)
    }
    private fun exact(view:View)=contract?.button==view.javaClass
    private fun eligible(view:View):Boolean {
        val c=contract ?: return false
        val immediate=view.parent as? View ?: return false
        val grandparent=immediate.parent as? View
        // Audited oplus_clear_all_panel.xml places the slot in a plain
        // RelativeLayout. Also accept the direct slot used by another layout;
        // never search a whole hierarchy merely for an unrelated clear button.
        val level=RecentsClearButtonPolicy.panelLevel(immediate.javaClass==c.panel,
            immediate.javaClass==RelativeLayout::class.java,grandparent?.javaClass==c.panel) ?: return false
        val panel=if(level==0)immediate else grandparent ?: return false
        return runCatching {RecentsClearButtonPolicy.eligible(exact(view),panel.javaClass==c.panel,
            c.slot.get(panel)===view,c.getButton.invoke(panel)===view,
            view.resources.getResourceEntryName(view.id))}.getOrDefault(false)
    }
    private fun observe(view:View):State=states[view] ?: State(view).also {
        states[view]=it;view.viewTreeObserver.addOnPreDrawListener(it);view.addOnAttachStateChangeListener(it)
    }
    fun install(api:XposedInterface,loader:ClassLoader) {
        if(Build.VERSION.SDK_INT<33 || contract!=null)return
        this.api=api
        runCatching {
            val version=Lg.processContext()?.packageManager?.getPackageInfo("com.android.launcher",0)?.versionName
            if(CosCompatibility.supportsLegacyPressFeedback(version)) {
                mark("contract","旧桌面继续使用原兼容路径；不安装COS17清理按钮适配")
                return
            }
            val button=Class.forName("com.android.launcher.views.PressFeedbackButton",false,loader)
            val panel=Class.forName("com.oplus.quickstep.views.OplusClearAllPanelView",false,loader)
            check(button.superclass.name=="androidx.appcompat.widget.AppCompatButton")
            val slot=panel.getDeclaredField("a").apply {check(type==button);isAccessible=true}
            val getter=panel.getDeclaredMethod("getClearPressButton").apply {check(returnType==View::class.java)}
            val bounds=button.getDeclaredMethod("getItemBackgroundRect").apply {check(returnType==RectF::class.java)}
            button.getDeclaredMethod("getForceBlockDraw").apply {check(returnType==Boolean::class.javaPrimitiveType)}
            val isolation=button.getDeclaredMethod("setForceBlockDraw",Boolean::class.javaPrimitiveType)
            val inflate=panel.getDeclaredMethod("onFinishInflate")
            val attached=button.getDeclaredMethod("onAttachedToWindow")
            val onDraw=button.getDeclaredMethod("onDraw",Canvas::class.java)
            val useBlur=button.getDeclaredMethod("f",Boolean::class.javaPrimitiveType).apply {check(returnType==Boolean::class.javaPrimitiveType)}
            api.hook(inflate).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(ui())(getter.invoke(chain.getThisObject()) as? View)?.takeIf(::eligible)?.let(::observe)
                result
            }
            api.hook(attached).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(ui())(chain.getThisObject() as? View)?.takeIf(::eligible)?.let(::observe)
                result
            }
            api.hook(isolation).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(ui())(chain.getThisObject() as? View)?.let {view ->
                    // The verified COS17 setter only assigns its field. It
                    // does NOT invalidate the button / cached parent layer.
                    // Keep our material in the enter display list and request
                    // one new record when native isolation changes or ends.
                    // No native flag, layer, alpha or cleanup callback changes.
                    val state=states[view] ?: if(eligible(view))observe(view) else null
                    state?.nativeIsolationChanged(chain.getArg(0) as Boolean)
                }
                result
            }
            api.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getThisObject() as View
                val canvas=chain.getArg(0) as Canvas
                val drawn=ui() && runCatching {
                    // Also attach lazily when a ROM reuses a button whose
                    // attach/inflate callbacks preceded the panel slot setup.
                    val state=states[view] ?: if(eligible(view))observe(view) else null
                    state?.draw(canvas)==true
                }.getOrDefault(false)
                nativeDraw.run(if(drawn)view else null) {chain.proceed()}
            }
            api.hook(useBlur).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                // Skip fallback fill ONLY after a visible layer was drawn on
                // this very Canvas. Native node recording/registration is not
                // sufficient. Controller setup and unrelated buttons stay native.
                if(nativeDraw.current()===chain.getThisObject() && nativeDraw.current()!=null)true else chain.proceed()
            }
            api.deoptimize(onDraw);api.deoptimize(useBlur);api.deoptimize(inflate);api.deoptimize(isolation)
            contract=Contract(button,panel,slot,getter,bounds)
            mark("contract","已核对COS17 btn_clear实例、panel slot/getter及原生绘制分支；保留点击和清理回调")
        }.onFailure {mark("unavailable","缺少精确控件或OEM背景接口，保留原生：${it.javaClass.simpleName}")}
    }

    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        private val target=WeakReference(view)
        var active=false;private set
        private var blockedUntil=0L
        private val surface=ClearButtonGlassSurface()
        private var area=RectF()
        private var params:LiquidGlassShader.Params?=null
        private var wasShown=false
        fun draw(canvas:Canvas):Boolean {
            val view=target.get() ?: return false
            // Prepare on this Canvas too; never wait for another invalidation.
            sync(requestFrame=false)
            // OEM forceBlockDraw suppresses its fallback paint/src icon during
            // the *entire* overview enter animation. It does not hide the native
            // text, and must not hide this separate material either. The native
            // parent RenderNode still controls enter/exit alpha and translation.
            if(!active || !view.isShown || view.windowVisibility!=View.VISIBLE)return false
            val p=params ?: return false
            return LauncherHook.withNativeClearSource(view) {source,info ->
                val drawn=surface.draw(canvas,area,p,source,view.resources.displayMetrics.density)
                RuntimeDiagnostics.update("recents-clear-glass-current",
                    "source=${info.kind}; reason=${info.reason}; baseDrawn=$drawn; opticalSubmitted=${surface.opticalSubmitted}; error=${surface.opticalError ?: "none"}")
                drawn
            }
        }
        fun nativeIsolationChanged(isolated:Boolean) {
            val view=target.get() ?: return
            sync(requestFrame=false)
            // Event-driven: exactly one invalidation per native setter call,
            // never a frame loop while overview is closed or the device sleeps.
            if(active && view.isAttachedToWindow && view.isShown && view.windowVisibility==View.VISIBLE)view.invalidate()
            mark("isolation","native=$isolated; materialReady=$active; nativeAlphaAndCleanupPreserved=true")
        }
        private fun sync(requestFrame:Boolean=true) {
            val view=target.get() ?: return
            try {
                val shown=view.isShown
                preferences(entering=shown && !wasShown)
                wasShown=shown
                val c=contract ?: return
                if(!enabled || !eligible(view) || !shown || !view.isHardwareAccelerated ||
                    view.width<=0 || view.height<=0 || SystemClock.uptimeMillis()<blockedUntil) {restore(view);return}
                // Geometry is independent of the OEM fallback-paint isolation;
                // native entering alpha/scale remain on the parent RenderNode.
                val b=RectF(c.bounds.invoke(view) as RectF)
                val radius=RecentsClearButtonPolicy.radius(b.left,b.top,b.right,b.bottom) ?: run {restore(view);return}
                val config=snapshot ?: return
                val key=System.identityHashCode(view);GlassRegistry.bind(key,view,c.button.name)
                val p=SharedGlassParams.resolve(config.values,config.preset,GlassRegistry.overridesFor(key),
                    b.width(),b.height(),GlassRegistry.cornerOverrideFor(key) ?: radius,true,config.compactOptics,config.grayBaseLuma)
                LauncherHook.registerNativeClearMaterial(view,p)
                val changed=!active || area!=b || params!=p
                area=b;params=p;active=true
                if(changed)surface.prepare()
                if(changed && requestFrame)view.invalidate()
                mark("material","直接绘制可见胶囊与共用玻璃；缺少LayerBlurDrawable也保留底色")

            } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+30_000L
                runCatching {restore(view)}
                mark("error","当前按钮还原原生：${error.javaClass.simpleName}")
            }
        }
        private fun restore(view:View) {
            if(active)LauncherHook.removeNativeClearMaterial(view)
            active=false;params=null
        }

        override fun onPreDraw():Boolean {sync();return true}
        override fun onViewAttachedToWindow(v:View) {}
        override fun onViewDetachedFromWindow(v:View) {
            runCatching {restore(v)};v.viewTreeObserver.removeOnPreDrawListener(this)
            v.removeOnAttachStateChangeListener(this);states.remove(v)
        }
    }
}
