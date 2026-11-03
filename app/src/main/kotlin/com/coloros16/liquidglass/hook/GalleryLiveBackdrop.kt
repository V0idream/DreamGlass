/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.lang.reflect.Method
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
    private var wrapper:Method?=null
    private var renderNode:Method?=null
    private fun id(view:View)=runCatching {view.resources.getResourceEntryName(view.id)}.getOrDefault("")
    fun install():Boolean=runCatching {
        val getter=View::class.java.getMethod("getViewWrapper")
        val node=getter.returnType.getMethod("getRenderNode")
        check(node.returnType==RenderNode::class.java)
        wrapper=getter;renderNode=node;true
    }.getOrElse {wrapper=null;renderNode=null;false}
    private data class Source(val view:View,val parent:View?,val dim:Float)
    private fun activity(context:Context):Activity? {
        var next=context
        repeat(12) {
            if(next is Activity)return next as Activity
            val current=next;next=(current as? ContextWrapper)?.baseContext ?: return null
            if(next===current)return null
        };return null
    }
    private fun source(host:View):Source? {
        if(GalleryNativeSurfaceContract.isDialogPanel(host)) {
            val a=activity(host.context) ?: return null
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
        val native=renderNode?.invoke(wrapper?.invoke(v)) as? RenderNode ?: return null
        if(!native.hasDisplayList()) {
            val count=firstFrameRetries[host] ?: 0
            if(count<2) {firstFrameRetries[host]=count+1;host.postOnAnimation {host.invalidate()}}
            return null
        }
        firstFrameRetries.remove(host)
        val global=Matrix()
        if(from.parent!=null)from.parent.transformMatrixToGlobal(global) else v.transformMatrixToGlobal(global)
        val hostGlobal=Matrix();host.transformMatrixToGlobal(hostGlobal)
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
        val frame=frames[view]?.takeIf {it.active && !it.failed} ?: return
        try {frame.glass.draw(canvas)} catch(error:Throwable) {
            frame.failed=true
            RuntimeDiagnostics.mark("gallery-live-draw-error","背景恢复原生：${error.javaClass.simpleName}")
            view.postInvalidateOnAnimation() // Let the normal preDraw restore native ownership.
        }
    }
    fun release(view:View) {frames.remove(view)?.let {it.unwatch();it.glass.close()}}
}
