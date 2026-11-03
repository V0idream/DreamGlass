/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

/** 小布建议 compact cards use createDefaultBackground(), which paints an
 * ordinary solid GradientDrawable and never reaches the legacy blur mapping.
 * SuggestCompactBgView is an empty View inserted at TitleCardView child 0.
 * Only that exact background is leased. Engine icon siblings/input stay native.
 */
internal object LauncherSuggestionGlassSurfaceHook {
    private data class Contract(val background:Class<*>,val card:Class<*>,val wrapper:Field,
        val wrapperCard:Field,val wrapperBackground:Field,val info:Method,val itemType:Field,
        val cardType:Field,val outline:Class<*>,val radius:Field)
    private var contract:Contract?=null
    private var api:XposedInterface?=null
    private var bridge:Method?=null
    private val states=WeakHashMap<View,State>()
    private val changing=ThreadLocal<Boolean>()
    private var preferencesAt=-1000L
    private var enabled=false
    private var config:SharedGlassParams.Snapshot?=null
    private fun ui()=Looper.myLooper()==Looper.getMainLooper()
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-preferencesAt<500L)return
        preferencesAt=now
        val prefs=api?.let(Prefs::read) ?: return
        enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,Prefs.DEFAULT_GLASS_ENABLE) &&
            prefs.getBoolean(Prefs.KEY_CARD_GLASS_ENABLE,Prefs.DEFAULT_CARD_GLASS_ENABLE)
        if(enabled)config=SharedGlassParams.read(prefs)
    }
    private fun exact(view:View)=contract?.background==view.javaClass
    private fun eligible(view:View):Boolean {
        val c=contract ?: return false
        if(!exact(view))return false
        val parent=view.parent as? ViewGroup ?: return false
        if(!c.card.isInstance(parent) || parent.childCount==0)return false
        return runCatching {
            val wrapper=c.wrapper.get(parent) ?: return@runCatching false
            val info=c.info.invoke(parent) ?: return@runCatching false
            LauncherSuggestionSurfacePolicy.eligible(c.wrapperCard.get(wrapper)===parent,
                c.wrapperBackground.get(wrapper)===view,parent.getChildAt(0)===view,
                c.itemType.getInt(info),c.cardType.getInt(info))
        }.getOrDefault(false)
    }
    private fun observe(view:View):State=states[view] ?: State(view).also {
        states[view]=it;view.viewTreeObserver.addOnPreDrawListener(it);view.addOnAttachStateChangeListener(it)
    }
    private fun assign(view:View,effect:RenderEffect?) {
        val previous=changing.get();changing.set(true)
        try {bridge!!.invoke(null,effect,view)} finally {
            if(previous==null)changing.remove() else changing.set(previous)
        }
    }

    fun install(api:XposedInterface,loader:ClassLoader) {
        this.api=api;contract=null
        runCatching {
            val background=Class.forName("com.android.launcher3.card.appsuggestnopadding.SuggestCompactBgView",false,loader)
            val card=Class.forName("com.android.launcher3.card.TitleCardView",false,loader)
            val wrapperType=Class.forName("com.android.launcher3.card.appsuggestnopadding.SuggestNoPaddingViewWrapper",false,loader)
            check(background.superclass==View::class.java && ViewGroup::class.java.isAssignableFrom(card))
            val wrapper=card.getDeclaredField("mSuggestNoPaddingViewWrapper").apply {check(type==wrapperType);isAccessible=true}
            val wrapperCard=wrapperType.getDeclaredField("titleCardView").apply {check(type==card);isAccessible=true}
            val wrapperBackground=wrapperType.getDeclaredField("mCompactBgView").apply {check(type==background);isAccessible=true}
            val info=card.getMethod("getItemInfo")
            check(info.returnType.name=="com.android.launcher3.card.LauncherCardInfo")
            val itemType=info.returnType.getField("itemType").apply {check(type==Int::class.javaPrimitiveType)}
            val cardType=info.returnType.getDeclaredField("mCardType").apply {check(type==Int::class.javaPrimitiveType);isAccessible=true}
            val outline=Class.forName("pantanal.app.groupcard.defaultImpl.ClipSmoothOutlineProvider",false,loader)
            val radius=outline.getDeclaredField("cardRadius").apply {check(type==Float::class.javaPrimitiveType);isAccessible=true}
            val bridgeType=Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect",false,loader)
            val setter=bridgeType.getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            check(Modifier.isStatic(setter.modifiers));bridge=setter
            val attached=background.getDeclaredMethod("onAttachedToWindow")
            val manager=Class.forName("com.android.launcher3.card.CardBackgroundBlurManager",false,loader)
            val create=manager.getDeclaredMethod("createDefaultBackground",View::class.java,Boolean::class.javaPrimitiveType)
            check(create.returnType==Boolean::class.javaPrimitiveType)
            api.hook(attached).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(ui())(chain.getThisObject() as? View)?.takeIf(::exact)?.let(::observe)
                result
            }
            api.hook(create).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(ui())(chain.getArg(0) as? View)?.takeIf(::exact)?.let {observe(it).dirty=true}
                result
            }
            api.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(!ui() || changing.get()==true)return@intercept chain.proceed()
                val view=chain.getArg(1) as? View ?: return@intercept chain.proceed()
                if(exact(view))observe(view).also {
                    it.nativeEffect=chain.getArg(0) as? RenderEffect;it.dirty=true
                }
                chain.proceed()
            }
            // Native initCompactBgView calls the tiny default-background helper.
            api.deoptimize(wrapperType.getDeclaredMethod("initCompactBgView"))
            contract=Contract(background,card,wrapper,wrapperCard,wrapperBackground,info,itemType,cardType,outline,radius)
            RuntimeDiagnostics.mark("launcher-suggestion-contract","已核对小布建议专用背景及82/83卡片身份；图标兄弟不作为渲染目标")
        }.onFailure {
            contract=null
            RuntimeDiagnostics.mark("launcher-suggestion-contract","小布建议结构未匹配，保留原生：${it.javaClass.simpleName}")
        }
    }

    private class State(view:View):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        private val target=WeakReference(view)
        private val material by lazy {AppGlassEffect()}
        var nativeEffect:RenderEffect?=null
        var dirty=false
        private var active=false
        private var effect:RenderEffect?=null
        private var effectHash=0
        private var blockedUntil=0L
        private var reportedGeometry=false
        private fun geometry(view:View):Pair<RectF,Float>? {
            val c=contract ?: return null
            val provider=view.outlineProvider ?: return null
            // This is the exact native clipping provider assigned immediately
            // after creation, not a guessed min(width,height)/2 capsule.
            if(!c.outline.isInstance(provider) || !view.clipToOutline)return null
            val radius=c.radius.getFloat(provider).takeIf {it.isFinite() && it>=0f} ?: return null
            val outline=Outline();provider.getOutline(view,outline)
            if(outline.isEmpty)return null
            val rect=Rect()
            val area=if(outline.getRect(rect))RectF(rect) else RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
            if(area.isEmpty || !listOf(area.left,area.top,area.right,area.bottom).all {it.isFinite()})return null
            return area to radius
        }
        private fun sync() {
            val view=target.get() ?: return
            try {
                preferences()
                if(!enabled || !eligible(view) || !view.isShown || !view.isHardwareAccelerated ||
                    view.width<=0 || view.height<=0 || SystemClock.uptimeMillis()<blockedUntil) {restore(view);return}
                val geometry=geometry(view) ?: run {
                    restore(view)
                    if(!reportedGeometry) {
                        reportedGeometry=true
                        RuntimeDiagnostics.mark("launcher-suggestion-geometry","等待小布建议原生圆角路径；尚未替换背景")
                    }
                    return
                }
                reportedGeometry=false
                if(!NativeSurfaceBackgroundLease.canMount(view)) {
                    restore(view)
                    RuntimeDiagnostics.mark("launcher-suggestion-background","原生背景不能安全租用，保持原生")
                    return
                }
                val snapshot=config ?: return
                val key=System.identityHashCode(view)
                GlassRegistry.bind(key,view,"com.android.launcher3.card.appsuggestnopadding.SuggestCompactBgView")
                val p=SharedGlassParams.resolve(snapshot.values,snapshot.preset,GlassRegistry.overridesFor(key),
                    geometry.first.width(),geometry.first.height(),GlassRegistry.cornerOverrideFor(key) ?: geometry.second,
                    false,snapshot.compactOptics,snapshot.grayBaseLuma)
                val hash=31*p.hashCode()+geometry.first.hashCode()
                if(effect==null || hash!=effectHash) {effect=material.create(p,geometry.first);effectHash=hash;dirty=true}
                // A successful default-setter lease removes the solid backing
                // and updates HWUI opacity metadata before enabling our surface.
                NativeSurfaceBackgroundLease.sync(view,true)
                check(NativeSurfaceBackgroundLease.owns(view)) {"Suggestion background lease was not installed"}
                if(!active || dirty) {
                    assign(view,effect);active=true;dirty=false
                    RuntimeDiagnostics.mark("launcher-suggestion-material","小布建议背景已提交统一GPU玻璃；四个图标、点击及原生圆角不变，待视觉验收")
                }
            } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+30_000L
                runCatching {restore(view)}
                RuntimeDiagnostics.mark("launcher-suggestion-error","小布建议暂退原生：${error.javaClass.simpleName}")
            }
        }
        private fun restore(view:View) {
            try {if(active || NativeSurfaceBackgroundLease.owns(view))assign(view,nativeEffect)} finally {
                active=false;dirty=false;effect=null;effectHash=0
                NativeSurfaceBackgroundLease.sync(view,false)
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
