/* DreamGlass contributors, 2026-10-30. GPL-3.0. Uses the shared GPU-only dissolve backend. */
package com.coloros16.liquidglass.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Application
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.*

/** Only native confirmed recents dismissal. Never remove/kill tasks or replace native callbacks. */
object RecentsParticleHook {
    const val KEY_ENABLED="recents_dissolve_enabled"
    const val KEY_SPEED="recents_dissolve_speed_percent"
    const val MAX_ACTIVE=2
    private val ui=Handler(Looper.getMainLooper())
    private val tickets=WeakHashMap<View,Ticket>()
    private val pending=WeakHashMap<Any,MutableList<Ticket>>()
    private val worker=ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(MAX_ACTIVE),
        { r->Thread(r,"DreamGlass-recents-snapshot").apply {isDaemon=true} },ThreadPoolExecutor.AbortPolicy())
    private var mounted=false
    private var readAt=-1000L;private var retryAt=0L
    private var enabled=false;private var speed=100
    private var ownAlpha=false
    private var release:Release?=null
    private var explicitDismiss=0
    private var clearAll=0
    private class Release(val task:View,val playback:Any,val goingUp:Boolean)
    private fun mark(stage:String,value:String)=RuntimeDiagnostics.mark("recents-particles-$stage",value)
    private fun safe(action:()->Unit) {try {action()} catch(error:Throwable) {
        retryAt=SystemClock.uptimeMillis()+30_000L
        mark("error","${error.javaClass.simpleName}: ${error.message}")
    }}
    private fun allowed(context:Context):Boolean {
        if(Looper.myLooper()!=Looper.getMainLooper() || SystemClock.uptimeMillis()<retryAt)return false
        if(SystemClock.uptimeMillis()-readAt>=500L) {
            readAt=SystemClock.uptimeMillis()
            val prefs=interfaceApi?.let(Prefs::read) ?: return false
            enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(KEY_ENABLED,false)
            speed=Prefs.readIntCompat(prefs,KEY_SPEED,100).coerceIn(35,300)
            if(!enabled)stopAll()
        }
        return enabled && interactive(context)
    }
    private fun interactive(context:Context):Boolean {
        val power=context.getSystemService(PowerManager::class.java)
        return power?.isInteractive==true && !power.isPowerSaveMode && android.animation.ValueAnimator.areAnimatorsEnabled()
    }
    private var interfaceApi:XposedInterface?=null
    fun install(api:XposedInterface,loader:ClassLoader) {
        if(mounted || Build.VERSION.SDK_INT<33 || Application.getProcessName()!="com.android.launcher")return
        interfaceApi=api
        // Install independently: a changed gesture adapter does not disable the clear-all path.
        runCatching {
            val base=Class.forName("com.android.launcher3.uioverrides.touchcontrollers.TaskViewTouchController",false,loader)
            val concrete=Class.forName("ja.g",false,loader)
            check(base.isAssignableFrom(concrete))
            val task=base.getDeclaredField("mTaskBeingDragged").apply {isAccessible=true}
            val animation=base.getDeclaredField("mCurrentAnimation").apply {isAccessible=true}
            val goingUp=base.getDeclaredField("mCurrentAnimationIsGoingUp").apply {isAccessible=true}
            val playback=Class.forName("com.android.launcher3.anim.AnimatorPlaybackController",false,loader)
            val velocity=RecentsParticleContract.velocity(playback) ?: error("Unknown playback signature")
            val player=playback.getDeclaredMethod("getAnimationPlayer")
            val end=concrete.getDeclaredMethod("onDragEnd",Float::class.javaPrimitiveType)
            api.hook(end).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val previous=release
                release=null
                safe {
                    val owner=chain.getThisObject()
                    val view=task.get(owner) as? View
                    val controller=animation.get(owner)
                    if(view!=null && controller!=null && allowed(view.context))
                        release=Release(view,controller,goingUp.getBoolean(owner))
                }
                try {chain.proceed()} finally {release=previous}
            }
            api.hook(velocity).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val context=release
                var ticket:Ticket?=null
                safe {
                    // The delegate also starts a pull-down reset animation with true. Identity
                    // must match mCurrentAnimation, and only the upward delete may go to end.
                    if(context!=null && context.playback===chain.getThisObject() &&
                        RecentsParticleContract.confirmed(context.goingUp,chain.getArg(1)==true))
                        ticket=capture(context.task)
                }
                val result=try {chain.proceed()} catch(error:Throwable) {ticket?.let(::cancel);throw error}
                safe { ticket?.let { t->
                    val animator=player.invoke(context!!.playback) as? Animator
                    animator?.addListener(object:AnimatorListenerAdapter() {
                        override fun onAnimationCancel(a:Animator) {cancel(t)}
                        override fun onAnimationEnd(a:Animator) {a.removeListener(this)}
                    })
                    accept(t)
                    mark("gesture","原生确认上划删除；当前卡片 GPU 接管")
                } }
                result
            }
            api.deoptimize(end)
            runCatching {Class.forName("no.g",false,loader).declaredMethods.filter {it.name=="onDragEnd"}.forEach {api.deoptimize(it)}}
            mark("gesture-hook","17.3.12 删除决策与 playback 身份校验已安装")
        }.onFailure {mark("gesture-unavailable","${it.javaClass.simpleName}: ${it.message}")}
        runCatching {
            val task=Class.forName("com.android.quickstep.views.TaskView",false,loader)
            val batch=Class.forName("com.android.launcher3.anim.PendingAnimation",false,loader)
            val addListener=batch.getDeclaredMethod("addListener",Animator.AnimatorListener::class.java)
            val types=listOf("com.android.quickstep.views.RecentsView","com.android.quickstep.views.OplusRecentsViewImpl")
                .map {Class.forName(it,false,loader)}
            for(type in types)for(route in RecentsParticleContract.routes(type,task,batch)) {
                api.hook(route).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    when(route.name) {
                        "dismissTask"->{
                            val scoped=chain.getArg(2)==true
                            if(scoped)explicitDismiss++
                            try {chain.proceed()} finally {if(scoped)explicitDismiss--}
                        }
                        "dismissAllTasks"->{clearAll++;try {chain.proceed()} finally {clearAll--}}
                        else->{
                            var ticket:Ticket?=null
                            safe {
                                val view=chain.getArg(0) as? View
                                val relevant=if(route.name=="addDismissedTaskAnimations")clearAll>0 else
                                    explicitDismiss>0 && chain.getArg(2)==true && chain.getArg(4)==false
                                if(relevant && view!=null && allowed(view.context))ticket=capture(view)
                            }
                            val result=try {chain.proceed()} catch(error:Throwable) {ticket?.let(::cancel);throw error}
                            safe {ticket?.let {t->
                                val animation=if(route.name=="addDismissedTaskAnimations")chain.getArg(2) else result
                                if(animation!=null) {
                                    val group=pending[animation] ?: ArrayList<Ticket>().also { list->
                                        pending[animation]=list
                                        addListener.invoke(animation,object:AnimatorListenerAdapter() {
                                            override fun onAnimationStart(a:Animator) {list.toList().forEach(::accept)}
                                            override fun onAnimationCancel(a:Animator) {list.toList().forEach(::cancel)}
                                            override fun onAnimationEnd(a:Animator) {a.removeListener(this);list.clear()}
                                        })
                                    }
                                    if(t !in group)group.add(t)
                                } else cancel(t)
                            } }
                            result
                        }
                    }
                }
                runCatching {api.deoptimize(route)}
            }
            mark("batch-hook","原生单卡关闭／清空动画已接入；保留任务服务回调")
        }.onFailure {mark("batch-unavailable","${it.javaClass.simpleName}: ${it.message}")}
        runCatching {
            api.hook(View::class.java.getDeclaredMethod("setAlpha",Float::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain->
                    val requested=chain.getArg(0) as Float
                    val actual=(chain.getThisObject() as? View)?.let {nativeAlpha(it,requested)} ?: requested
                    if(actual!=requested)chain.proceed(arrayOf<Any>(actual)) else chain.proceed()
                }
        }.onFailure {mark("alpha-unavailable",it.javaClass.simpleName)}
        mounted=true
    }

    internal class Ticket(target:View,val root:WeakReference<View>,val node:RenderNode,
        val x:Float,val y:Float,val width:Int,val height:Int,val rawX:Float,val rawY:Float,
        val capturedAt:Long=SystemClock.uptimeMillis()) {
        val view=WeakReference(target)
        @Volatile var cancelled=false
        var submitted=false;var hidden=false
        var effectSpeed=100
        var nativeAlpha=target.alpha
        var dust:ParticleDissolveHook.Api33.Dust?=null
        var deadline:Runnable?=null
    }
    internal fun capture(view:View):Ticket? {
        tickets[view]?.let {return it}
        if(tickets.size>=MAX_ACTIVE || !view.isAttachedToWindow || !view.isShown || !view.isHardwareAccelerated)return null
        val root=view.rootView
        if(root===view || view.width<=0 || view.height<=0)return null
        val matrix=Matrix();view.transformMatrixToGlobal(matrix)
        val rootMatrix=Matrix();root.transformMatrixToGlobal(rootMatrix)
        val inverse=Matrix();if(!rootMatrix.invert(inverse))return null
        matrix.postConcat(inverse)
        val rect=RectF(0f,0f,view.width.toFloat(),view.height.toFloat());matrix.mapRect(rect)
        val rawX=rect.left;val rawY=rect.top
        if(!rect.intersect(0f,0f,root.width.toFloat(),root.height.toFloat()) || rect.width()<8f || rect.height()<8f)return null
        val w=kotlin.math.ceil(rect.width()).toInt();val h=kotlin.math.ceil(rect.height()).toInt()
        val node=RenderNode("DreamGlass dismissed recent task");node.setPosition(0,0,w,h)
        val canvas=node.beginRecording(w,h)
        try {canvas.translate(-rect.left,-rect.top);canvas.concat(matrix);view.draw(canvas)} finally {node.endRecording()}
        val t=Ticket(view,WeakReference(root),node,rect.left,rect.top,w,h,rawX,rawY)
        tickets[view]=t
        val deadline=Runnable {cancel(t)};t.deadline=deadline;ui.postDelayed(deadline,1800L)
        return t
    }
    internal fun accept(t:Ticket) {
        if(t.cancelled || t.submitted)return
        t.submitted=true
        t.effectSpeed=speed
        t.deadline?.let(ui::removeCallbacks)
        t.deadline=Runnable {cancel(t)}.also {ui.postDelayed(it,ParticleDissolveHook.durationMs(t.effectSpeed)+500L)}
        try {worker.execute {
            var bitmap:Bitmap?=null
            try {if(!t.cancelled)bitmap=ParticleDissolveHook.Api33.snapshotNode(t.node,t.width,t.height,640,960)}
            catch(error:Throwable) {ui.post {safe {cancel(t);throw error}}}
            finally {t.node.discardDisplayList()}
            val image=bitmap
            ui.post {safe {
                val view=t.view.get();val root=t.root.get()
                if(image==null || t.cancelled || view==null || root==null || !view.isAttachedToWindow ||
                    !root.isShown || !interactive(root.context) || SystemClock.uptimeMillis()-t.capturedAt>180L) {cancel(t);return@safe}
                val matrix=Matrix();view.transformMatrixToGlobal(matrix)
                val rootMatrix=Matrix();root.transformMatrixToGlobal(rootMatrix)
                val inverse=Matrix();if(!rootMatrix.invert(inverse)) {cancel(t);return@safe}
                matrix.postConcat(inverse)
                val now=RectF(0f,0f,view.width.toFloat(),view.height.toFloat());matrix.mapRect(now)
                // Continue from the native card's current position, including a clipped top.
                val x=t.x+now.left-t.rawX;val y=t.y+now.top-t.rawY
                val dust=ParticleDissolveHook.Api33.Dust(root,image,x,y,t.width,t.height,
                    view.resources.displayMetrics.density,t.effectSpeed,-1f,.20f,true) {finish(t)}
                t.dust=dust;dust.start()
                t.nativeAlpha=view.alpha;t.hidden=true
                ownAlpha=true;try {view.alpha=0f} finally {ownAlpha=false}
                mark("playing","任务卡片 GPU 向上飘散；${image.width}x${image.height}")
            }}
        }} catch(error:Throwable) {t.node.discardDisplayList();cancel(t);throw error}
    }
    internal fun nativeAlpha(view:View,requested:Float):Float {
        if(ownAlpha || Looper.myLooper()!=Looper.getMainLooper())return requested
        val ticket=tickets[view] ?: return requested
        if(!ticket.hidden)return requested
        ticket.nativeAlpha=requested
        return 0f
    }
    private fun finish(t:Ticket) {
        t.cancelled=true;t.deadline?.let(ui::removeCallbacks);t.deadline=null
        t.view.get()?.let {v->
            if(tickets[v]===t)tickets.remove(v)
            if(t.hidden && v.alpha==0f) {ownAlpha=true;try {v.alpha=t.nativeAlpha} finally {ownAlpha=false}}
        }
        t.hidden=false;t.dust=null
    }
    internal fun cancel(t:Ticket) {
        if(t.cancelled)return
        if(!t.submitted)t.node.discardDisplayList()
        t.dust?.stop();finish(t)
    }
    internal fun stopAll() {tickets.values.toList().forEach(::cancel);pending.clear()}
}

/** Verified ROM shapes, testable without instantiating vendor classes. */
object RecentsParticleContract {
    fun confirmed(goingUp:Boolean,goingToEnd:Boolean)=goingUp && goingToEnd
    fun velocity(type:Class<*>):Method?=type.declaredMethods.singleOrNull {
        it.name=="startWithVelocity" && it.returnType==Void.TYPE && it.parameterTypes.contentEquals(
            arrayOf(Context::class.java,Boolean::class.javaPrimitiveType,Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,Long::class.javaPrimitiveType))
    }
    fun routes(type:Class<*>,task:Class<*>,pending:Class<*>):List<Method> = type.declaredMethods.filter {
        val args=it.parameterTypes
        when(it.name) {
            "dismissTask"->it.returnType==Void.TYPE && args.contentEquals(arrayOf(task,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
            "dismissAllTasks"->it.returnType==Void.TYPE && args.contentEquals(arrayOf(View::class.java))
            "createTaskDismissAnimation"->it.returnType==pending && args.contentEquals(arrayOf(task,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
            "addDismissedTaskAnimations"->it.returnType==Void.TYPE && args.contentEquals(arrayOf(task,Long::class.javaPrimitiveType,pending))
            else->false
        }
    }
}
