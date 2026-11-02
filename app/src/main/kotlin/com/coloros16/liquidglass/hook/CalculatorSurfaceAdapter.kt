/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** COS17 calculator 17.2.16. Operator SVGs are View backgrounds, while digits use
 * TextView's foreground. Both stay completely native. Only the separately verified
 * fill and five-path shadow bitmap are suppressed after the parent has a material.
 */
internal object CalculatorSurfaceAdapter {
    private var buttonType:Class<*>?=null
    private var frameType:Class<*>?=null
    private var mounted:(View)->Boolean={false}
    private val drawing=CalculatorForegroundDraw()

    fun install(api:XposedInterface,loader:ClassLoader,materialApplied:(View)->Boolean):List<Class<*>> {
        val button=Class.forName("com.android.calculator2.ui.widget.COUIButton",false,loader)
        val frame=Class.forName("com.android.calculator2.ui.widget.FrameButton",false,loader)
        check(View::class.java.isAssignableFrom(button) && FrameLayout::class.java.isAssignableFrom(frame))
        val onDraw=button.getDeclaredMethod("onDraw",Canvas::class.java)
        val fill=button.getDeclaredField("l").apply {isAccessible=true}
        val coat=button.getDeclaredMethod("p",Canvas::class.java)
        val gradient=button.getDeclaredMethod("q",Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
        check(fill.type==Boolean::class.javaPrimitiveType && button.getDeclaredField("f0").type==Bitmap::class.java &&
            button.getDeclaredField("n").type==Paint::class.java && onDraw.returnType==Void.TYPE &&
            coat.returnType==Void.TYPE && gradient.returnType==Void.TYPE)
        api.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val view=chain.getThisObject() as View
            val canvas=chain.getArg(0) as Canvas
            val parent=materialHost(view)
            val active=canvas.isHardwareAccelerated && parent!=null && mounted(parent)
            drawing.draw(view,active,{fill.getBoolean(view)},{fill.setBoolean(view,it)}) {chain.proceed()}
        }
        api.hook(coat).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            if(drawing.suppressCoat(chain.getThisObject()!!))null else chain.proceed()
        }
        api.deoptimize(onDraw)
        buttonType=button;frameType=frame;mounted=materialApplied
        RuntimeDiagnostics.mark("calculator-foreground-contract","已核对按钮填充／阴影独立通道；SVG 背景与原生文字均保留")
        return listOf(frame)
    }

    /** Resolves only the verified native container, never an arbitrary ancestor. */
    fun materialHost(button:View):View? {
        if(buttonType?.isInstance(button)!=true)return null
        var parent=button.parent
        repeat(3) {
            if(frameType?.isInstance(parent)==true)return parent as View
            parent=(parent as? View)?.parent
        }
        return null
    }

    /** Native COUIButton fill is laid out over [0,width] x [0,height]. Its
     * FrameButton can be a larger grid cell. Return the *button's* visible box in
     * host-local coordinates, including native child/ancestor matrices and scroll.
     * Do not center a guessed circle in the whole cell or map the host matrix a
     * second time. Ambiguous/missing native children leave geometry unmodified.
     */
    fun nativeBounds(host:View):RectF? {
        if(frameType?.isInstance(host)!=true || host.width<=0 || host.height<=0)return null
        val candidates=ArrayList<View>(1)
        fun find(parent:ViewGroup,depth:Int) {
            if(depth>3)return
            for(index in 0 until parent.childCount) {
                val child=parent.getChildAt(index)
                if(child.visibility!=View.VISIBLE || child.alpha<=0f)continue
                if(buttonType?.isInstance(child)==true && materialHost(child)===host) {
                    if(child.width>0 && child.height>0)candidates+=child
                } else if(child is ViewGroup)find(child,depth+1)
            }
        }
        find(host as ViewGroup,0)
        if(candidates.size!=1)return null
        var node=candidates.single()
        val bounds=RectF(0f,0f,node.width.toFloat(),node.height.toFloat())
        node.clipBounds?.let { if(!bounds.intersect(RectF(it)))return null }
        repeat(4) {
            val parent=node.parent as? ViewGroup ?: return null
            if(!node.matrix.isIdentity)node.matrix.mapRect(bounds)
            bounds.offset((node.left-parent.scrollX).toFloat(),(node.top-parent.scrollY).toFloat())
            if(parent.clipChildren && !bounds.intersect(0f,0f,parent.width.toFloat(),parent.height.toFloat()))return null
            if(parent.clipToPadding && (parent.paddingLeft!=0 || parent.paddingTop!=0 || parent.paddingRight!=0 || parent.paddingBottom!=0) &&
                !bounds.intersect(parent.paddingLeft.toFloat(),parent.paddingTop.toFloat(),
                    (parent.width-parent.paddingRight).toFloat(),(parent.height-parent.paddingBottom).toFloat()))return null
            if(parent===host) {
                if(!bounds.left.isFinite() || !bounds.top.isFinite() || !bounds.right.isFinite() || !bounds.bottom.isFinite() || bounds.isEmpty)return null
                return bounds
            }
            node=parent
        }
        return null
    }

    /** Root calls this after mount, on active pre-draw/background replacement and
     * during restore/detach. A child COUIButton must NEVER be passed through it.
     */
    fun onMaterialStateChanged(host:View,active:Boolean) {
        if(frameType?.isInstance(host)!=true || Looper.myLooper()!=Looper.getMainLooper())return
        if(CalculatorBackgroundLease.sync(host,active)) {
            RuntimeDiagnostics.mark(if(active)"calculator-parent-translucent" else "calculator-parent-restored",
                if(active)"父容器背景已通过正常 setter 转为透明，清除不透明缓存；子按钮 SVG 不变" else "已还原父容器原生背景")
        }
    }
}
