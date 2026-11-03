/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderEffect
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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** COS17's verified clear-all material owner. Uses the native RenderNode's live
 * background input; no CPU blur, wallpaper/screenshot, task deletion or callback
 * interception. Original click, text, src icon, native alpha/scale and touch
 * remain native. Independent of the incompatible COS16 PressFeedbackHandler. */
internal object RecentsClearButtonGlassHook {
    private data class Contract(val button:Class<*>,val panel:Class<*>,val slot:Field,
        val getButton:Method,val bounds:Method,val forceBlocked:Method)
    private var contract:Contract?=null
    private var api:XposedInterface?=null
    private var bridge:Method?=null
    private val states=WeakHashMap<View,State>()
    private val changing=ThreadLocal<Boolean>()
    private val nativeDraw=ScopedDrawOwner<View>()
    private var readAt=-1000L
    private var enabled=false
    private var snapshot:SharedGlassParams.Snapshot?=null
    private fun ui()=Looper.myLooper()==Looper.getMainLooper()
    private fun mark(stage:String,value:String)=RuntimeDiagnostics.mark("recents-clear-glass-$stage",value)
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-readAt<500L)return
        readAt=now
        val p=api?.let(Prefs::read) ?: return
        // A basic control, like QS buttons: free and follows the shared switches.
        enabled=p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_GLASS_ENABLE,Prefs.DEFAULT_GLASS_ENABLE)
        if(enabled)snapshot=SharedGlassParams.read(p)
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
    private fun assign(view:View,effect:RenderEffect?) {
        val old=changing.get();changing.set(true)
        try {bridge!!.invoke(null,effect,view)} finally {if(old==null)changing.remove() else changing.set(old)}
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
            val blocked=button.getDeclaredMethod("getForceBlockDraw").apply {check(returnType==Boolean::class.javaPrimitiveType)}
            val inflate=panel.getDeclaredMethod("onFinishInflate")
            val attached=button.getDeclaredMethod("onAttachedToWindow")
            val onDraw=button.getDeclaredMethod("onDraw",Canvas::class.java)
            val useBlur=button.getDeclaredMethod("f",Boolean::class.javaPrimitiveType).apply {check(returnType==Boolean::class.javaPrimitiveType)}
            val type=Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect",false,loader)
            val setter=type.getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            check(Modifier.isStatic(setter.modifiers));bridge=setter
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
            api.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(ui() && changing.get()!=true) {
                    val view=chain.getArg(1) as? View
                    if(view!=null && eligible(view))observe(view).also {
                        it.nativeEffect=chain.getArg(0) as? RenderEffect;it.dirty=true
                    }
                }
                chain.proceed()
            }
            api.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getThisObject() as View
                val active=ui() && states[view]?.active==true && (chain.getArg(0) as Canvas).isHardwareAccelerated
                nativeDraw.run(if(active)view else null) {chain.proceed()}
            }
            api.hook(useBlur).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                // This condition ONLY skips the two fallback solid-color Paths
                // inside this owner's onDraw. Calls from native g()/controller
                // setup stay original; no native blur capability flag is forced.
                if(nativeDraw.current()===chain.getThisObject() && nativeDraw.current()!=null)true else chain.proceed()
            }
            api.deoptimize(onDraw);api.deoptimize(useBlur);api.deoptimize(inflate)
            contract=Contract(button,panel,slot,getter,bounds,blocked)
            mark("contract","已核对COS17 btn_clear实例、panel slot/getter及原生绘制分支；保留点击和清理回调")
        }.onFailure {mark("unavailable","缺少精确控件或OEM背景接口，保留原生：${it.javaClass.simpleName}")}
    }

    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        private val target=WeakReference(view)
        private val material by lazy {AppGlassEffect()}
        var nativeEffect:RenderEffect?=null
        var dirty=false
        var active=false;private set
        private var effect:RenderEffect?=null
        private var effectHash=0
        private var blockedUntil=0L
        private fun sync() {
            val view=target.get() ?: return
            try {
                preferences()
                val c=contract ?: return
                if(!enabled || !eligible(view) || !view.isShown || !view.isHardwareAccelerated ||
                    !view.clipToOutline || view.width<=0 || view.height<=0 ||
                    c.forceBlocked.invoke(view)==true || SystemClock.uptimeMillis()<blockedUntil) {restore(view);return}
                val b=RectF(c.bounds.invoke(view) as RectF)
                val radius=RecentsClearButtonPolicy.radius(b.left,b.top,b.right,b.bottom) ?: run {restore(view);return}
                val config=snapshot ?: return
                val key=System.identityHashCode(view);GlassRegistry.bind(key,view,c.button.name)
                val p=SharedGlassParams.resolve(config.values,config.preset,GlassRegistry.overridesFor(key),
                    b.width(),b.height(),GlassRegistry.cornerOverrideFor(key) ?: radius,true,config.compactOptics,config.grayBaseLuma)
                val hash=31*p.hashCode()+b.hashCode()
                if(effect==null || effectHash!=hash) {effect=material.create(p,b);effectHash=hash;dirty=true}
                if(!RecentsClearButtonBackgroundLease.sync(view,true)) {restore(view);return}
                if(!active || dirty) {
                    assign(view,effect);active=true;dirty=false
                    mark("material","清后台按钮已提交统一GPU玻璃，使用原生实时背景输入；实际显示待验收")
                }
            } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+30_000L
                runCatching {restore(view)}
                mark("error","当前按钮还原原生：${error.javaClass.simpleName}")
            }
        }
        private fun restore(view:View) {
            try {if(active || RecentsClearButtonBackgroundLease.owns(view))assign(view,nativeEffect)} finally {
                active=false;dirty=false;effect=null;effectHash=0
                RecentsClearButtonBackgroundLease.sync(view,false)
            }
        }
        override fun onPreDraw():Boolean {sync();return true}
        override fun onViewAttachedToWindow(v:View) {}
        override fun onViewDetachedFromWindow(v:View) {
            runCatching {restore(v)};v.viewTreeObserver.removeOnPreDrawListener(this)
            v.removeOnAttachStateChangeListener(this);states.remove(v)
        }
    }
}
