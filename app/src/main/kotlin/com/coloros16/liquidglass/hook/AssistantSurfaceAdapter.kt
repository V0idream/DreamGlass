/* DreamGlass contributors, 2026. GPL-3.0-or-later.
 * Native-fill ownership follows ColorOS16-LiquidGlass's mounting-before-
 * suppression principle, with independently verified COS17 Shelf contracts.
 */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** Shelf 17.14.60 draws card fill through t.c inside four actual View owners.
 * u.draw only draws the optional native highlight overlay and is NOT a card
 * background. The original onDraw/draw always runs exactly once; only the proven
 * fill/stroke throat is skipped after that SAME owner's GPU material is mounted.
 */
internal object AssistantSurfaceAdapter {
    private data class Owner(val contract:AssistantOwnerContract.Resolved,@Volatile var ready:Boolean=false)
    private data class Frame(val owner:View,val painter:Any?,val path:Path?,val active:Boolean)
    private val scope=ScopedDrawOwner<Frame>()
    @Volatile private var owners:List<Owner> = emptyList()

    fun isNativeCard(view:View):Boolean=owners.any {it.ready && it.contract.spec.card && it.contract.type.isInstance(view)}
    fun matches(view:View):Boolean=owners.any {it.ready && it.contract.type.isInstance(view)}
    private fun owner(view:View)=owners.firstOrNull {it.ready && it.contract.type.isInstance(view)}
    private fun isUiThread()=Looper.myLooper()==Looper.getMainLooper()

    /** Actual local fill geometry. Root can bind its optical mask to this instead
     * of guessing that View.width/height equals the painted card. No View retained.
     */
    fun nativeBounds(view:View):RectF? {
        if(!isUiThread())return null
        val contract=owner(view)?.contract ?: return null
        return runCatching {
            val native=contract.path.get(view) as Path
            if(native.isEmpty)return@runCatching null
            RectF().also {native.computeBounds(it,true)}.takeIf {
                !it.isEmpty && listOf(it.left,it.top,it.right,it.bottom).all {v -> v.isFinite()}
            }
        }.getOrNull()
    }

    /** Preserve native background-only fade/visibility without changing content
     * alpha. Constraint cards use a separate float; image controls gate their fill.
     */
    fun nativeFillAlpha(view:View):Float? {
        if(!isUiThread())return null
        val contract=owner(view)?.contract ?: return null
        return runCatching {
            if(!contract.enabled.getBoolean(view) || contract.blocked?.getBoolean(view)==true)0f
            else (contract.alpha?.getFloat(view) ?: 1f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 0f
        }.getOrNull()
    }

    fun install(api:XposedInterface,loader:ClassLoader,materialApplied:(View)->Boolean):List<Class<*>> {
        owners=emptyList()
        return try {
            val painter=Class.forName("com.coloros.common.ui.t",false,loader)
            val painterInterface=Class.forName("com.coloros.common.ui.y",false,loader)
            val stroke=Class.forName("com.coloros.common.ui.b",false,loader)
            check(painterInterface.isAssignableFrom(painter))
            check(painter.getDeclaredField("f").type==Paint::class.java)
            val fill=painter.getDeclaredMethod("c",Canvas::class.java,Path::class.java,Float::class.javaPrimitiveType)
            val outline=painter.getDeclaredMethod("d",Canvas::class.java,Path::class.java,stroke)
            check(fill.returnType==Void.TYPE && outline.returnType==Void.TYPE)
            val selected=AssistantOwnerContract.specs.mapNotNull {spec ->
                runCatching {Class.forName(spec.name,false,loader)}.getOrNull()?.let {type ->
                    AssistantOwnerContract.resolve(type,spec,View::class.java,Canvas::class.java,Path::class.java,painterInterface)
                }?.let(::Owner)
            }
            if(selected.isEmpty())return emptyList()

            api.hook(fill).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val frame=scope.current()
                if(frame?.active==true && frame.painter===chain.getThisObject() && frame.path===chain.getArg(1)) {
                    RuntimeDiagnostics.mark("assistant-glass-fill","已替换真实卡片底色；原生前景与圆角遮罩继续绘制")
                    null
                } else chain.proceed()
            }
            api.hook(outline).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val frame=scope.current()
                if(frame?.active==true && frame.painter===chain.getThisObject())null else chain.proceed()
            }

            fun frame(view:View,canvas:Canvas,item:Owner):Frame? = runCatching {
                // Always enter a frame for inactive nested owners as well, so a
                // mounted parent's scope cannot suppress its non-mounted child.
                val active=item.ready && isUiThread() && canvas.isHardwareAccelerated && materialApplied(view)
                if(!active)return@runCatching Frame(view,null,null,false)
                val contract=item.contract
                val path=contract.path.get(view) as Path
                val delegate=contract.painter.invoke(view) ?: return@runCatching null
                Frame(view,delegate,path,active)
            }.getOrNull()
            val mounted=ArrayList<Owner>()
            for(item in selected)runCatching {
                val contract=item.contract
                for(method in listOfNotNull(contract.onDraw,contract.draw)) {
                    api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                        val view=chain.getThisObject() as View
                        val canvas=chain.getArg(0) as Canvas
                        scope.run(frame(view,canvas,item)) {chain.proceed()}
                    }
                    api.deoptimize(method)
                }
                item.ready=true;mounted+=item
            }.onFailure {
                item.ready=false
                RuntimeDiagnostics.mark("assistant-glass-owner-missing-${item.contract.type.simpleName}",it.javaClass.simpleName)
            }
            owners=mounted
            RuntimeDiagnostics.mark("assistant-glass-contract","已识别 ${mounted.size} 类真实底色宿主；不再将高光装饰当作背景")
            // Image owners still enter an inactive scope to shield their own
            // native fill from a parent card. Do not discover avatars/content
            // images (including HeadHighlightShadowImageView) as glass panels.
            mounted.filter {it.contract.spec.card}.map {it.contract.type}
        } catch(error:Throwable) {
            owners=emptyList()
            RuntimeDiagnostics.mark("assistant-glass-contract","当前负一屏底色接口未匹配，保留原生：${error.javaClass.simpleName}")
            emptyList()
        }
    }
}
