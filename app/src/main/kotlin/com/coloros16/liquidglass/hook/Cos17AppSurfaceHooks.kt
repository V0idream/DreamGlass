/* DreamGlass contributors, 2026. GPL-3.0-or-later.
 * Native-fill suppression approach informed by ColorOS16-LiquidGlass's
 * AppPageGlassMounter, adapted to the supplied COS17 APK contracts.
 */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.entry.XposedEntry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** Recognize real controls even when their background is an ordinary opaque
 * drawable. Suppress only verified background paint AFTER the common GPU backdrop
 * was successfully assigned. Foreground text, images, child drawing and input stay
 * native. No global foreground blur, replacement borders or screenshot pipeline.
 */
internal object Cos17AppSurfaceHooks {
    private var targets:List<Class<*>> = emptyList()
    private var backgroundTargets:List<Class<*>> = emptyList()
    private var calculatorChild:Class<*>?=null
    private var calculatorFrame:Class<*>?=null
    private val drawing=ScopedDrawOwner<View>()
    fun isTarget(view:View)=targets.any {it.isInstance(view)} ||
        isNativeCard(view)
    fun isNativeCard(view:View)=view.background?.let {bg -> backgroundTargets.any {it.isInstance(bg)}}==true
    private fun applied(view:View):Boolean {
        if(isTarget(view))return AppGlassHook.materialApplied(view)
        if(calculatorChild?.isInstance(view)!=true)return false
        // Native COUIButton applies its material to its FrameButton parent.
        // Mount there once, rather than filtering the parent and child twice.
        var parent=view.parent
        repeat(3) {
            if(calculatorFrame?.isInstance(parent)==true)
                return AppGlassHook.materialApplied(parent as View)
            parent=(parent as? View)?.parent
        }
        return false
    }

    fun install(api:XposedInterface,loader:ClassLoader,pkg:String) {
        targets=emptyList()
        backgroundTargets=emptyList()
        calculatorChild=null;calculatorFrame=null
        try {
            // This private method records the View's background only; never skip
            // View.draw/onDraw wholesale (TextView glyphs live there as well).
            val background=View::class.java.getDeclaredMethod("drawBackground",Canvas::class.java)
            check(background.returnType==Void.TYPE)
            val candidates=when(pkg) {
                XposedEntry.PACKAGE_CALCULATOR -> calculator(api,loader)
                XposedEntry.PACKAGE_GALLERY -> gallery(api,loader)
                XposedEntry.PACKAGE_ASSISTANT_SCREEN -> assistant(loader)
                else -> emptyList()
            }
            if(candidates.isEmpty() && backgroundTargets.isEmpty())return
            api.hook(background).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getThisObject() as? View
                val canvas=chain.getArg(0) as? Canvas
                if(view!=null && canvas?.isHardwareAccelerated==true && applied(view)) {
                    RuntimeDiagnostics.mark("app-glass-native-fill-suppressed","已挂载材质的控件不再重复绘制原生不透明底；前景照常绘制")
                    null
                } else chain.proceed()
            }
            // Boot/JIT compiled View.draw may already contain its private helper.
            // Deoptimize that caller; otherwise only hooking drawBackground can
            // leave the native opaque fill in its compiled display-list path.
            api.deoptimize(View::class.java.getDeclaredMethod("draw",Canvas::class.java))
            targets=candidates
            RuntimeDiagnostics.mark("app-glass-target-contract","已核对 ${candidates.size} 类控件／${backgroundTargets.size} 类原生背景；等待实际材质挂载")
        } catch(error:Throwable) {
            targets=emptyList()
            backgroundTargets=emptyList()
            calculatorChild=null;calculatorFrame=null
            RuntimeDiagnostics.mark("app-glass-target-contract","原生控件适配未启用：${error.javaClass.simpleName}；保持原界面")
        }
    }

    private fun calculator(api:XposedInterface,loader:ClassLoader):List<Class<*>> {
        val type=Class.forName("com.android.calculator2.ui.widget.COUIButton",false,loader)
        val frame=Class.forName("com.android.calculator2.ui.widget.FrameButton",false,loader)
        check(View::class.java.isAssignableFrom(type))
        check(android.widget.FrameLayout::class.java.isAssignableFrom(frame))
        val onDraw=type.getDeclaredMethod("onDraw",Canvas::class.java)
        // COS17 17.2.16: l gates only the custom fill branch in onDraw; p draws
        // an offscreen bitmap containing five gradient/shadow paths, not glyphs.
        val fill=type.getDeclaredField("l").apply {isAccessible=true}
        val coat=type.getDeclaredMethod("p",Canvas::class.java)
        val bitmap=type.getDeclaredField("f0")
        val fillPaint=type.getDeclaredField("n")
        val gradientPass=type.getDeclaredMethod("q",Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
        check(fill.type==Boolean::class.javaPrimitiveType && bitmap.type==android.graphics.Bitmap::class.java &&
            fillPaint.type==android.graphics.Paint::class.java && gradientPass.returnType==Void.TYPE &&
            onDraw.returnType==Void.TYPE && coat.returnType==Void.TYPE)
        api.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val view=chain.getThisObject() as View
            val active=(chain.getArg(0) as Canvas).isHardwareAccelerated && applied(view)
            // Always enter the scope, including inactive nested targets, so an
            // ancestor's successful mount cannot suppress an unrelated child.
            drawing.run(if(active)view else null) {
                if(!active)return@run chain.proceed()
                val native=fill.getBoolean(view);fill.setBoolean(view,false)
                try {chain.proceed()} finally {fill.setBoolean(view,native)}
            }
        }
        api.hook(coat).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            if(drawing.current()===chain.getThisObject())null else chain.proceed()
        }
        api.deoptimize(onDraw)
        calculatorChild=type;calculatorFrame=frame
        return listOf(frame)
    }

    private fun gallery(api:XposedInterface,loader:ClassLoader):List<Class<*>> {
        val type=Class.forName("com.oplus.gallery.basebiz.widget.BlurButtonWrapperView",false,loader)
        check(View::class.java.isAssignableFrom(type))
        val dispatch=type.getDeclaredMethod("dispatchDraw",Canvas::class.java)
        val button=type.getDeclaredMethod("getButtonView")
        val delegate=type.getDeclaredMethod("getMaterialEffectDelegate").apply {isAccessible=true}
        val decoration=delegate.returnType.getDeclaredMethod("d",Canvas::class.java)
        check(button.returnType==View::class.java && dispatch.returnType==Void.TYPE && decoration.returnType==Void.TYPE)
        val delegateScope=ScopedDrawOwner<Any>()
        api.hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val view=chain.getThisObject() as View
            val owner=if((chain.getArg(0) as Canvas).isHardwareAccelerated && applied(view))
                runCatching {delegate.invoke(view)}.getOrNull() else null
            delegateScope.run(owner) {chain.proceed()}
        }
        api.hook(decoration).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            // Native dispatchDraw draws children first, then this material stroke.
            // Removing only that stroke keeps every icon and its native press/input.
            if(delegateScope.current()===chain.getThisObject())null else chain.proceed()
        }
        api.deoptimize(dispatch)
        return listOf(type)
    }

    private fun assistant(loader:ClassLoader):List<Class<*>> {
        // This APK's e0.b is a rounding mask, not the fill throat. Leave that
        // mask and its child callback intact. Only the verified native background
        // drawable is selected; common View.drawBackground suppression handles it.
        val drawable=Class.forName("com.coloros.common.ui.u",false,loader)
        val delegate=Class.forName("com.coloros.common.ui.v",false,loader)
        check(android.graphics.drawable.Drawable::class.java.isAssignableFrom(drawable) &&
            drawable.getDeclaredField("a").type==delegate &&
            drawable.getDeclaredMethod("draw",Canvas::class.java).returnType==Void.TYPE)
        backgroundTargets=listOf(drawable)
        return emptyList()
    }
}
