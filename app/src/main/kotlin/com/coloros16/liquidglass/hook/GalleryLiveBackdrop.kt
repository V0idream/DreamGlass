/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.os.Looper
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Exact Gallery content sources: main_tab_layout's ViewPager2 sibling, or the
 * Activity's separate decor behind its dialog window. Never mirror an ancestor
 * of the glass host: that would put a RenderNode inside its own source graph. */
internal object GalleryLiveBackdrop {
    private class Frame {
        val glass=LiveContentGlassNode();var active=false;var failed=false
        private var watched:WeakReference<View>?=null
        private var listener:ViewTreeObserver.OnPreDrawListener?=null
        fun watch(source:View,host:View) {
            // Same-window content already shares Gallery's preDraw. Invalidating
            // it from its own callback would create a permanent frame loop.
            if(source.rootView===host.rootView) {unwatch();return}
            if(watched?.get()===source)return
            unwatch()
            val target=WeakReference(host)
            val observer=ViewTreeObserver.OnPreDrawListener {
                target.get()?.let {runCatching {it.invalidate()}};true
            }
            source.viewTreeObserver.addOnPreDrawListener(observer)
            watched=WeakReference(source);listener=observer
        }
        fun unwatch() {
            val source=watched?.get();val current=listener
            if(source!=null && current!=null && source.viewTreeObserver.isAlive)
                source.viewTreeObserver.removeOnPreDrawListener(current)
            watched=null;listener=null
        }
    }
    private val frames=WeakHashMap<View,Frame>()
    private val firstFrameRetries=WeakHashMap<View,Int>()
    private val dialogOwners=WeakHashMap<View,WeakReference<Activity>>()
    private var nativeAccessor:GalleryNativeNodeAccessor?=null
    private var hasDialogDispatch=false
    private fun id(view:View)=runCatching {view.resources.getResourceEntryName(view.id)}.getOrDefault("")
    fun install():Boolean=runCatching {
        nativeAccessor=GalleryNativeNodeAccessor(View::class.java,RenderNode::class.java)
        check(nativeAccessor!!.available)
        RuntimeDiagnostics.mark("gallery-live-node-contract","已注册运行时wrapper取值及精确View.mRenderNode通道")
        true
    }.getOrElse {
        nativeAccessor=null
        RuntimeDiagnostics.mark("gallery-live-node-contract-error","没有原生节点通道：${it.javaClass.simpleName}")
        false
    }
    fun dialogDispatchInstalled() {hasDialogDispatch=true}
    fun canDrawDialog()=hasDialogDispatch
    private data class Source(val view:View,val parent:View?,val dim:Float)
    private fun activity(context:Context):Activity? {
        var next=context
        repeat(12) {
            if(next is Activity)return next as Activity
            val current=next;next=(current as? ContextWrapper)?.baseContext ?: return null
            if(next===current)return null
        };return null
    }
    /** A themed panel Context is not required to retain the Activity. Associate
     * only the Dialog's own decor with its explicit owner/context at show-time;
     * never pick an arbitrary recent Activity or another gallery window. */
    fun installDialogSources(api:XposedInterface) {
        runCatching {
            api.hook(Dialog::class.java.getDeclaredMethod("show"))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    if(Looper.myLooper()==Looper.getMainLooper())runCatching {
                        val dialog=chain.getThisObject() as Dialog
                        val owner=dialog.ownerActivity ?: activity(dialog.context)
                        val decor=dialog.window?.peekDecorView()
                        if(owner!=null && decor!=null && decor!==owner.window.peekDecorView())
                            dialogOwners[decor]=WeakReference(owner)
                    }
                    result
                }
        }.onFailure {
            RuntimeDiagnostics.mark("gallery-dialog-source-contract","窗口关联未注册：${it.javaClass.simpleName}；使用显式Activity上下文")
        }
    }
    /** transformMatrixToGlobal alone can remain relative to the ViewRoot's
     * window. Dialog and Activity have DIFFERENT roots/window offsets. Anchor
     * each matrix to its actual screen origin without losing scale/rotation.
     * When a ROM already includes that origin the correction is exactly zero. */
    private fun screenMatrix(view:View):Matrix {
        val matrix=Matrix();view.transformMatrixToGlobal(matrix)
        val origin=floatArrayOf(0f,0f);matrix.mapPoints(origin)
        val screen=IntArray(2);view.getLocationOnScreen(screen)
        matrix.postTranslate(screen[0]-origin[0],screen[1]-origin[1])
        return matrix
    }
    private fun source(host:View):Source? {
        if(GalleryNativeSurfaceContract.isDialogPanel(host)) {
            val a=dialogOwners[host.rootView]?.get() ?: activity(host.context) ?: run {
                RuntimeDiagnostics.mark("gallery-live-dialog-missing-owner","面板没有可核对的所属Activity；保持原生")
                return null
            }
            if(a.isFinishing || a.isDestroyed || a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)return null
            val content=a.window.peekDecorView() ?: return null
            if(content===host.rootView || content.left!=0 || content.top!=0 || !content.matrix.isIdentity)return null
            val attrs=host.rootView.layoutParams as? WindowManager.LayoutParams
            val dim=if(attrs!=null && attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND!=0)attrs.dimAmount.coerceIn(0f,1f) else 0f
            return Source(content,null,dim)
        }
        var owner:View?=host
        repeat(12) {
            val current=owner ?: return null
            if(id(current)=="main_segment_wrapper_view" && current.javaClass.name==
                "com.oplus.gallery.standard_lib.ui.bottomnavigation.BottomFloatingNavigationBar") {
                val container=current.parent as? ViewGroup ?: return null
                if(id(container)!="container")return null
                val index=container.indexOfChild(current)
                val content=(0 until index).map(container::getChildAt).singleOrNull {
                    id(it)=="view_pager" && it.javaClass.name=="androidx.viewpager2.widget.ViewPager2"
                } ?: return null
                return Source(content,container,0f)
            }
            owner=current.parent as? View
        };return null
    }
    fun needs(host:View)=GalleryNativeSurfaceContract.isDialogPanel(host) || GalleryNativeSurfaceContract.isHomeNavigation(host)
    fun prepare(host:View,area:RectF,p:LiquidGlassShader.Params,opacity:Float):RenderEffect?=runCatching {
        val from=source(host) ?: return null
        val v=from.view
        if(!LiveContentSourceGraph.independent(v,host) {it.parent as? View} ||
            !v.isAttachedToWindow || !v.isHardwareAccelerated || !v.isShown)return null
        val nodeResult=nativeAccessor?.read(v) ?: run {
            RuntimeDiagnostics.update("gallery-live-node-state","未取得实际RenderNode；source=${v.javaClass.name}")
            return null
        }
        val native=nodeResult.node as RenderNode
        RuntimeDiagnostics.update("gallery-live-node-state","已取得实际RenderNode：${nodeResult.route}")
        if(!native.hasDisplayList()) {
            val count=firstFrameRetries[host] ?: 0
            if(count<2) {firstFrameRetries[host]=count+1;host.postOnAnimation {host.invalidate()}}
            RuntimeDiagnostics.mark("gallery-live-source-waiting","等待原生源首个display list；不使用透明输入替代")
            return null
        }
        firstFrameRetries.remove(host)
        val global=screenMatrix(from.parent ?: v)
        val hostGlobal=screenMatrix(host)
        val inverse=Matrix();if(!hostGlobal.invert(inverse))return null
        val map=Matrix().apply {setConcat(inverse,global)}
        val frame=frames[host] ?: Frame().also {frames[host]=it}
        if(frame.failed)return null
        frame.watch(v,host)
        if(frame.glass.update(native,map,area,p,opacity,from.dim,refreshContent=true))host.invalidate()
        RuntimeDiagnostics.mark("gallery-live-source-${if(from.parent==null)"dialog" else "home"}",
            "原生${if(from.parent==null)"独立活动窗口" else "ViewPager2兄弟内容"} RenderNode实时引用；无截图/位图/父子递归")
        frame.glass.effect
    }.getOrElse {
        RuntimeDiagnostics.mark("gallery-live-source-error","保持原生：${it.javaClass.simpleName}");null
    }
    fun activate(view:View) {frames[view]?.active=true}
    fun owns(view:View)=frames[view]?.active==true
    fun draw(view:View,canvas:Canvas) {
        if(hasDialogDispatch && GalleryNativeSurfaceContract.isDialogPanel(view))return
        drawContent(view,canvas)
    }
    fun drawDialogPanel(view:View,canvas:Canvas) {
        if(!hasDialogDispatch)return
        // dispatchDraw is in scrolled content coordinates; this is a background
        // surface fixed to the panel, matching View.drawBackground's compensation.
        val save=canvas.save()
        try {canvas.translate(view.scrollX.toFloat(),view.scrollY.toFloat());drawContent(view,canvas)}
        finally {canvas.restoreToCount(save)}
    }
    private fun drawContent(view:View,canvas:Canvas) {
        val frame=frames[view]?.takeIf {it.active && !it.failed} ?: return
        if(!canvas.isHardwareAccelerated)return
        try {
            frame.glass.draw(canvas)
            if(GalleryNativeSurfaceContract.isDialogPanel(view))
                RuntimeDiagnostics.mark("gallery-live-dialog-painted","parentPanel.dispatchDraw实际提交GPU内容；位于原生文字/按钮之前")
        } catch(error:Throwable) {
            frame.failed=true
            RuntimeDiagnostics.mark("gallery-live-draw-error","背景恢复原生：${error.javaClass.simpleName}")
            view.postInvalidateOnAnimation() // Let the normal preDraw restore native ownership.
        }
    }
    fun release(view:View) {frames.remove(view)?.let {it.unwatch();it.glass.close()}}
}
