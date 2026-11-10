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
                val current=target.get()
                if(active && current!=null && current.isAttachedToWindow && current.isShown &&
                    current.windowVisibility==View.VISIBLE)runCatching {current.invalidate()}
                else unwatch() // A hidden/dismissed sheet is not a glass consumer.
                true
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
    private val pendingPanels=WeakHashMap<View,View.OnAttachStateChangeListener>()
    private var nativeAccessor:GalleryNativeNodeAccessor?=null
    private var hasDialogBackground=false
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
    fun dialogBackgroundInstalled() {hasDialogBackground=true}
    fun canDrawDialog()=hasDialogBackground
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
    fun installDialogSources(api:XposedInterface,loader:ClassLoader,onPanel:(View)->Unit) {
        fun panel(decor:View):View? {
            val resource=decor.resources.getIdentifier("parentPanel","id",decor.context.packageName)
            if(resource!=0)decor.findViewById<View>(resource)?.takeIf(GalleryNativeSurfaceContract::isDialogPanel)?.let {return it}
            val candidates=java.util.ArrayDeque<Pair<View,Int>>()
            candidates.add(decor to 0)
            var visited=0
            while(candidates.isNotEmpty() && visited++<256) {
                val (view,depth)=candidates.removeFirst()
                if(GalleryNativeSurfaceContract.isDialogPanel(view))return view
                if(depth<16 && view is ViewGroup)
                    for(i in 0 until minOf(view.childCount,(256-candidates.size).coerceAtLeast(0)))
                        candidates.add(view.getChildAt(i) to depth+1)
            }
            return null
        }
        fun bind(dialog:Dialog,builderContext:Context?,entry:String,retry:Boolean) {
            if(Looper.myLooper()!=Looper.getMainLooper())return
            val decor=dialog.window?.peekDecorView() ?: run {
                RuntimeDiagnostics.update("gallery-save-entry","${entry}：实际Dialog尚无decor，保持原生")
                return
            }
            val owner=dialog.ownerActivity ?: activity(dialog.context) ?: builderContext?.let(::activity)
            if(owner!=null && decor!==owner.window.peekDecorView())dialogOwners[decor]=WeakReference(owner)
            RuntimeDiagnostics.update("gallery-save-owner",if(owner!=null)
                "已绑定实际Dialog窗口到${owner.javaClass.name}；独立decor=${decor!==owner.window.peekDecorView()}"
                else "${entry}没有显式Activity owner/context，保持原生")
            val host=panel(decor)
            if(host!=null) {
                if(host.isAttachedToWindow) {
                    pendingPanels.remove(host)?.let(host::removeOnAttachStateChangeListener)
                    onPanel(host)
                    RuntimeDiagnostics.update("gallery-save-entry","${entry}：实际${dialog.javaClass.name}返回窗口中找到并登记parentPanel")
                } else {
                    // A Dialog decor can already exist before WindowManager has
                    // attached its hierarchy. A single posted runnable is not an
                    // attachment guarantee (particularly during COUI transitions).
                    // Bind the actual host's lifecycle, with no timer or polling.
                    if(!pendingPanels.containsKey(host)) {
                        val listener=object:View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(view:View) {
                                view.removeOnAttachStateChangeListener(this)
                                pendingPanels.remove(view)
                                runCatching {onPanel(view)}.onFailure {
                                    RuntimeDiagnostics.update("gallery-save-entry","parentPanel挂载登记失败：${it.javaClass.simpleName}")
                                }
                            }
                            override fun onViewDetachedFromWindow(view:View) {}
                        }
                        pendingPanels[host]=listener
                        host.addOnAttachStateChangeListener(listener)
                    }
                    RuntimeDiagnostics.update("gallery-save-entry","${entry}：已找到parentPanel，等待实际窗口挂载")
                }
            } else {
                RuntimeDiagnostics.update("gallery-save-entry","${entry}：parentPanel尚未出现，等待一次窗口队列回调")
                if(retry)decor.post {
                    if(dialog.isShowing && dialog.window?.peekDecorView()===decor)
                        runCatching {bind(dialog,builderContext,entry,false)}.onFailure {
                            RuntimeDiagnostics.update("gallery-save-entry","实际窗口登记失败：${it.javaClass.simpleName}")
                        }
                }
            }
        }
        runCatching {
            api.hook(Dialog::class.java.getDeclaredMethod("show"))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val result=chain.proceed()
                    runCatching {bind(chain.getThisObject() as Dialog,null,"Dialog.show",true)}.onFailure {
                        RuntimeDiagnostics.update("gallery-save-entry","Dialog.show补充入口登记失败：${it.javaClass.simpleName}")
                    }
                    result
                }
        }.onFailure {
            RuntimeDiagnostics.mark("gallery-dialog-source-contract","窗口关联未注册：${it.javaClass.simpleName}；使用显式Activity上下文")
        }
        // APK save flow: nae.B0 -> kbb/ap5 -> COUIAlertDialogBuilder.show(View).
        // Observe THAT returned Dialog/window, even if a native background
        // filter was never set and no generic View lifecycle hook saw it.
        runCatching {
            val builder=Class.forName("com.coui.appcompat.dialog.COUIAlertDialogBuilder",false,loader)
            val alertDialog=Class.forName("androidx.appcompat.app.AlertDialog",false,loader)
            val contract=GallerySaveDialogEntry.resolve(builder,View::class.java,Dialog::class.java,alertDialog,Context::class.java)
            val show=contract.show
            val context=contract.context
            api.hook(show).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val result=chain.proceed()
                if(result is Dialog && alertDialog.isInstance(result))runCatching {
                    val sourceContext=context.invoke(chain.getThisObject()) as? Context
                    bind(result,sourceContext,"COUIAlertDialogBuilder.show(View)",true)
                }.onFailure {
                    RuntimeDiagnostics.update("gallery-save-entry","保存构建器已返回；登记失败：${it.javaClass.simpleName}")
                }
                result
            }
            api.deoptimize(show)
            RuntimeDiagnostics.mark("gallery-save-entry-contract","已独立注册实际COUIAlertDialogBuilder.show(View)返回窗口；不依赖wrapper或OEM背景bridge")
        }.onFailure {
            RuntimeDiagnostics.mark("gallery-save-entry-contract-error","实际保存构建器入口未匹配：${it.javaClass.simpleName}")
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
                RuntimeDiagnostics.update("gallery-save-owner","面板没有可核对的Activity owner或Context；保持原生")
                return null
            }
            if(a.isFinishing || a.isDestroyed || a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0) {
                RuntimeDiagnostics.update("gallery-save-owner","所属Activity正在结束/销毁或为安全窗口；保持原生")
                return null
            }
            val content=a.window.peekDecorView() ?: run {
                RuntimeDiagnostics.update("gallery-save-owner","所属Activity尚无decor；保持原生");return null
            }
            if(content===host.rootView || content.left!=0 || content.top!=0 || !content.matrix.isIdentity) {
                RuntimeDiagnostics.update("gallery-save-owner","源与宿主窗口重合或源根存在未匹配变换；保持原生");return null
            }
            RuntimeDiagnostics.update("gallery-save-owner","已确认${a.javaClass.name}独立Activity窗口，不是Dialog自身内容")
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
    private fun sourceState(host:View,detail:String) {
        if(GalleryNativeSurfaceContract.isDialogPanel(host))RuntimeDiagnostics.update("gallery-save-source",detail)
    }
    fun prepare(host:View,area:RectF,p:LiquidGlassShader.Params,opacity:Float):RenderEffect?=runCatching {
        val from=source(host) ?: run {sourceState(host,"没有独立原生内容源，保持原生背景");return null}
        val v=from.view
        if(!LiveContentSourceGraph.independent(v,host) {it.parent as? View} ||
            !v.isAttachedToWindow || !v.isHardwareAccelerated || !v.isShown) {
            sourceState(host,"源图不独立或源未挂载/硬件加速/可见，保持原生背景");return null
        }
        val nodeResult=nativeAccessor?.read(v) ?: run {
            RuntimeDiagnostics.update("gallery-live-node-state","未取得实际RenderNode；source=${v.javaClass.name}")
            sourceState(host,"未取得实际RenderNode，保持原生背景")
            return null
        }
        val native=nodeResult.node as RenderNode
        RuntimeDiagnostics.update("gallery-live-node-state","已取得实际RenderNode：${nodeResult.route}")
        if(!native.hasDisplayList()) {
            val count=firstFrameRetries[host] ?: 0
            if(count<2) {firstFrameRetries[host]=count+1;host.postOnAnimation {host.invalidate()}}
            RuntimeDiagnostics.mark("gallery-live-source-waiting","等待原生源首个display list；不使用透明输入替代")
            sourceState(host,"等待源首个display list，保持原生背景")
            return null
        }
        firstFrameRetries.remove(host)
        val global=screenMatrix(from.parent ?: v)
        val hostGlobal=screenMatrix(host)
        val inverse=Matrix();if(!hostGlobal.invert(inverse)) {
            sourceState(host,"宿主矩阵不可逆，保持原生背景");return null
        }
        val map=Matrix().apply {setConcat(inverse,global)}
        val frame=frames[host] ?: Frame().also {frames[host]=it}
        if(frame.failed) {sourceState(host,"当前内容节点此前绘制失败，保持原生背景");return null}
        frame.watch(v,host)
        if(frame.glass.update(native,map,area,p,opacity,from.dim,refreshContent=true))host.invalidate()
        RuntimeDiagnostics.mark("gallery-live-source-${if(from.parent==null)"dialog" else "home"}",
            "原生${if(from.parent==null)"独立活动窗口" else "ViewPager2兄弟内容"} RenderNode实时引用；无截图/位图/父子递归")
        sourceState(host,"就绪：${nodeResult.route}原生实时内容；独立窗口；opacity=$opacity；dim=${from.dim}")
        frame.glass.effect
    }.getOrElse {
        RuntimeDiagnostics.mark("gallery-live-source-error","保持原生：${it.javaClass.simpleName}")
        sourceState(host,"原生源处理异常：${it.javaClass.simpleName}；保持原生背景")
        null
    }
    fun activate(view:View) {frames[view]?.active=true}
    fun owns(view:View)=frames[view]?.active==true
    fun draw(view:View,canvas:Canvas) {
        if(hasDialogBackground && GalleryNativeSurfaceContract.isDialogPanel(view))return
        drawContent(view,canvas)
    }
    fun drawDialogBackground(panel:View,paintHost:View,canvas:Canvas) {
        if(!canvas.isHardwareAccelerated)return
        val save=canvas.save()
        try {
            // dialogOverlay is inside rootView; its origin excludes native
            // parentPanel margins. Preserve that genuine layout transform.
            if(panel!==paintHost) {
                val inverse=Matrix()
                if(!screenMatrix(paintHost).invert(inverse))return
                canvas.concat(Matrix().apply {setConcat(inverse,screenMatrix(panel))})
            }
            drawContent(panel,canvas)
        } catch(error:Throwable) {
            frames[panel]?.failed=true
            RuntimeDiagnostics.mark("gallery-dialog-background-error","背景坐标处理失败，恢复原生：${error.javaClass.simpleName}")
            panel.postInvalidateOnAnimation()
        } finally {canvas.restoreToCount(save)}
    }
    private fun drawContent(view:View,canvas:Canvas) {
        val frame=frames[view]?.takeIf {it.active && !it.failed} ?: return
        if(!canvas.isHardwareAccelerated)return
        try {
            frame.glass.draw(canvas)
            if(GalleryNativeSurfaceContract.isDialogPanel(view))
                RuntimeDiagnostics.mark("gallery-live-dialog-painted","实际parentPanel/dialogOverlay背景Drawable已提交完整GPU玻璃；原生文字与按钮保持独立")
        } catch(error:Throwable) {
            frame.failed=true
            RuntimeDiagnostics.mark("gallery-live-draw-error","背景恢复原生：${error.javaClass.simpleName}")
            view.postInvalidateOnAnimation() // Let the normal preDraw restore native ownership.
        }
    }
    fun release(view:View) {frames.remove(view)?.let {it.unwatch();it.glass.close()}}
}
