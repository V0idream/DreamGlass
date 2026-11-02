/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Gallery 17's material belongs to BlurButtonWrapperView itself, not buttonView.
 * Its delegate separately installs a COUI blur/stroke surface, paints a fallback
 * background and draws a final highlight. Replace those channels only on the
 * verified wrapper; children, icon backgrounds, touch and native alpha stay native.
 */
internal object GallerySurfaceAdapter {
    private data class Contract(val wrapper:Class<*>,val delegate:Method,val host:Field,
        val radius:Field,val corner:Field,val cornerRadius:Method?)
    private data class Request(val method:Method,val tail:Array<Any?>) {
        fun replay(view:View) {method.invoke(null,*arrayOf(view,*tail))}
    }
    private class State {
        val requests=GalleryMaterialRequests<Request>()
        var nativeCleared=false
    }
    private val states=WeakHashMap<View,State>()
    private val decorationScope=ScopedDrawOwner<Any>()
    private val mutating=ThreadLocal<Boolean>()
    private var contract:Contract?=null
    private var clearBlur:Method?=null
    private var clearStroke:Method?=null
    private var noBlur:Any?=null
    private var noStroke:Any?=null
    private fun ui()=Looper.myLooper()==Looper.getMainLooper()
    fun matches(view:View)=contract?.wrapper?.isInstance(view)==true

    private inline fun <T> nativeMutation(block:()->T):T {
        val old=mutating.get();mutating.set(true)
        try {return block()} finally {if(old==null)mutating.remove() else mutating.set(old)}
    }
    private fun delegate(view:View):Any? {
        val c=contract ?: return null
        if(!c.wrapper.isInstance(view))return null
        return c.delegate.invoke(view)?.takeIf {c.host.get(it)===view}
    }

    /** Native surface coordinates are already wrapper-local. Do not map child
     * button offsets/press matrices onto this host a second time. A native round
     * Outline can narrow the rect; absent Outline uses the proven full host box.
     */
    fun nativeBounds(view:View):RectF? {
        if(!ui() || !matches(view) || view.width<=0 || view.height<=0)return null
        return runCatching {
            if(delegate(view)==null)return@runCatching null
            val outline=Outline();view.outlineProvider?.getOutline(view,outline)
            val rect=Rect()
            val result=if(!outline.isEmpty && outline.getRect(rect))RectF(rect)
                else if(outline.isEmpty)RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
                else return@runCatching null // Unknown native path geometry: do not guess.
            result.takeIf { !it.isEmpty && listOf(it.left,it.top,it.right,it.bottom).all {v -> v.isFinite()} }
        }.getOrNull()
    }
    /** a16.l(float) stores the same radius in boxed Float e and corner params f.
     * Read that semantic value before the Outline fallback; do not invent .5h or
     * apply the generic card preset's .22 multiplier to this capsule.
     */
    fun nativeRadius(view:View):Float? {
        if(!ui() || !matches(view))return null
        return runCatching {
            val c=contract ?: return@runCatching null
            val owner=delegate(view) ?: return@runCatching null
            val radius=c.radius.get(owner) as? Float
            if(radius!=null)return@runCatching radius.takeIf {it.isFinite() && it>=0f}
            val corner=c.corner.get(owner)
            val fromParams=if(corner!=null)(c.cornerRadius?.invoke(corner) as? Number)?.toFloat() else null
            if(fromParams!=null)return@runCatching fromParams.takeIf {it.isFinite() && it>=0f}
            val outline=Outline();view.outlineProvider?.getOutline(view,outline)
            outline.radius.takeIf {!outline.isEmpty && it.isFinite() && it>=0f}
        }.getOrNull()
    }

    /** Call under AppGlassHook's `changing` guard BEFORE assigning our OEM effect.
     * false means native state could not safely be leased: leave this host native.
     */
    fun beforeAssign(view:View,active:Boolean):Boolean {
        if(!matches(view))return true
        if(!ui())return false
        if(!active) {onMaterialStateChanged(view,false);return true}
        val state=states[view] ?: return false
        return try {
            if(delegate(view)==null || !state.requests.begin())return false
            if(!state.nativeCleared) {
                nativeMutation {
                    clearBlur!!.invoke(null,view,noBlur)
                    clearStroke!!.invoke(null,view,noStroke)
                }
                state.nativeCleared=true
            }
            CalculatorBackgroundLease.sync(view,true)
            true
        } catch(error:Throwable) {
            runCatching {onMaterialStateChanged(view,false)}
            RuntimeDiagnostics.mark("gallery-glass-native-error","原生材质租用失败，保留原生：${error.javaClass.simpleName}")
            false
        }
    }

    /** true synchronizes only the wrapper background; false is called AFTER the
     * root restores its OEM effect. Replaying native COUI last avoids that restore
     * overwriting the original material. Root holds `changing` for both branches.
     */
    fun onMaterialStateChanged(view:View,active:Boolean) {
        if(!matches(view) || !ui())return
        val state=states[view]
        if(active) {
            if(state?.requests?.active==true)CalculatorBackgroundLease.sync(view,true)
            return
        }
        CalculatorBackgroundLease.sync(view,false)
        if(state==null)return
        val restore=state.requests.end()
        state.nativeCleared=false
        nativeMutation {
            for(request in restore)runCatching {request.replay(view)}.onFailure {
                RuntimeDiagnostics.mark("gallery-glass-restore-error",it.javaClass.simpleName)
            }
        }
    }

    fun install(api:XposedInterface,loader:ClassLoader,materialApplied:(View)->Boolean):List<Class<*>> {
        contract=null;states.clear()
        return try {
            val wrapper=Class.forName("com.oplus.gallery.basebiz.widget.BlurButtonWrapperView",false,loader)
            val getter=wrapper.getDeclaredMethod("getMaterialEffectDelegate").apply {isAccessible=true}
            val owner=getter.returnType
            check(View::class.java.isAssignableFrom(wrapper) && owner.name=="com.oplus.aiunit.vision.a16")
            val host=owner.getDeclaredField("a").apply {check(type==View::class.java);isAccessible=true}
            val radius=owner.getDeclaredField("e").apply {check(type==java.lang.Float::class.java);isAccessible=true}
            val corner=owner.getDeclaredField("f").apply {
                check(type.name=="com.oplus.view.material.OplusMaterialCornerParams");isAccessible=true
            }
            val cornerRadius=runCatching {corner.type.getMethod("getRadius")}.getOrNull()
            val decoration=owner.getDeclaredMethod("d",Canvas::class.java)
            val dispatch=wrapper.getDeclaredMethod("dispatchDraw",Canvas::class.java)
            check(decoration.returnType==Void.TYPE && dispatch.returnType==Void.TYPE &&
                wrapper.getDeclaredMethod("getButtonView").returnType==View::class.java)
            val material=Class.forName("com.coui.appcompat.COUIMaterialEffect",false,loader)
            val blurType=Class.forName("com.coui.appcompat.COUIMaterialBlurEffect\$BlurEffectType",false,loader)
            val strokeType=Class.forName("com.coui.appcompat.COUIMaterialStrokeEffect\$StrokeEffectType",false,loader)
            clearBlur=material.getDeclaredMethod("setViewBlurEffect",View::class.java,blurType)
            clearStroke=material.getDeclaredMethod("setViewStrokeEffect",View::class.java,strokeType)
            noBlur=blurType.getField("TYPE_NO_EFFECT").get(null)
            noStroke=strokeType.getField("TYPE_NO_EFFECT").get(null)
            val setters=material.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.returnType==Void.TYPE &&
                    it.name in setOf("setViewBlurEffect","setViewStrokeEffect") &&
                    it.parameterTypes.firstOrNull()==View::class.java
            }
            check(setters.size==6 && clearBlur in setters && clearStroke in setters)
            for(method in setters)api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(mutating.get()==true || !ui())return@intercept chain.proceed()
                val view=chain.getArg(0) as? View ?: return@intercept chain.proceed()
                if(!matches(view))return@intercept chain.proceed()
                val state=states[view] ?: State().also {states[view]=it}
                val channel=if(method.name=="setViewBlurEffect")GalleryMaterialRequests.Channel.BLUR
                    else GalleryMaterialRequests.Channel.STROKE
                val suppress=state.requests.remember(channel,Request(method,chain.getArgs().drop(1).toTypedArray()))
                if(suppress)null else chain.proceed()
            }
            api.hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getThisObject() as View
                val active=ui() && (chain.getArg(0) as Canvas).isHardwareAccelerated &&
                    states[view]?.requests?.active==true && materialApplied(view)
                val native=if(active)runCatching {delegate(view)}.getOrNull() else null
                decorationScope.run(native) {chain.proceed()}
            }
            api.hook(decoration).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(decorationScope.current()===chain.getThisObject() && decorationScope.current()!=null)null
                else chain.proceed()
            }
            // Constructor's e() and transition methods call tiny COUI setters;
            // remove stale inlined copies so native requests can be restored.
            for(method in owner.declaredMethods.filter {it.name in setOf("e","f","i","k","n")})api.deoptimize(method)
            api.deoptimize(dispatch)
            contract=Contract(wrapper,getter,host,radius,corner,cornerRadius)
            RuntimeDiagnostics.mark("gallery-glass-contract","已识别真实 wrapper 材质、原生圆角与独立模糊／描边通道；保留按钮前景")
            listOf(wrapper)
        } catch(error:Throwable) {
            contract=null
            RuntimeDiagnostics.mark("gallery-glass-contract","相册接口未匹配，保留原生：${error.javaClass.simpleName}")
            emptyList()
        }
    }
}
