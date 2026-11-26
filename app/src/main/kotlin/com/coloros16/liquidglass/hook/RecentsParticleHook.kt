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
import java.lang.reflect.Modifier
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
    private var slideRelease:SlideRelease?=null
    private var explicitDismiss=0
    private var clearAll=0
    private val observer=NativeAnimationObserver({action->ui.post {action()};Unit}) {error ->
        retryAt=SystemClock.uptimeMillis()+30_000L
        mark("observer-error","${error.javaClass.simpleName}: ${error.message}")
    }
    private class Release(val task:View,val playback:Any?,val goingUp:Boolean)
    private class SlideRelease(val task:View,val animation:Any)
    private fun mark(stage:String,value:String)=RuntimeDiagnostics.mark("recents-particles-$stage",value)
    private fun safe(action:()->Unit) {try {action()} catch(error:Throwable) {
        retryAt=SystemClock.uptimeMillis()+30_000L
        mark("error","${error.javaClass.simpleName}: ${error.message}")
    }}
    private fun allowed(context:Context):Boolean {
        if(Looper.myLooper()!=Looper.getMainLooper() || SystemClock.uptimeMillis()<retryAt)return false
        if(!membershipGranted()) {enabled=false;readAt=-1000L;stopAll();return false}
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
    private fun membershipGranted():Boolean=Prefs.memberAllowed(interfaceApi)
    fun install(api:XposedInterface,loader:ClassLoader) {
        if(mounted || Build.VERSION.SDK_INT<33 || Application.getProcessName()!="com.android.launcher")return
        interfaceApi=api
        installGesture(api,loader)
        installModernBatch(api,loader)
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
                                if(relevant && view!=null && allowed(view.context))ticket=capture(view,clearAll>0)
                            }
                            val result=try {chain.proceed()} catch(error:Throwable) {ticket?.let(::cancel);throw error}
                            safe {ticket?.let {t->
                                val animation=if(route.name=="addDismissedTaskAnimations")chain.getArg(2) else result
                                if(animation!=null)bindPending(animation,t,addListener) else cancel(t)
                            } }
                            result
                        }
                    }
                }
                runCatching {api.deoptimize(route)}
            }
            mark("batch-hook","原生单卡关闭／清空动画已接入；保留任务服务回调")
        }.onFailure {mark("batch-unavailable","${it.javaClass.simpleName}: ${it.message}")}
        // COS17's stack delegate builds its batch in RecentsViewAnimUtil, never
        // calling RecentsView.addDismissedTaskAnimations. This per-card entry is
        // invoked AFTER the OEM locked/quick-start exclusions have been applied.
        runCatching {
            val task=Class.forName("com.android.quickstep.views.TaskView",false,loader)
            val recents=Class.forName("com.android.quickstep.views.OplusRecentsViewImpl",false,loader)
            val batch=Class.forName("com.android.launcher3.anim.PendingAnimation",false,loader)
            val addListener=batch.getDeclaredMethod("addListener",Animator.AnimatorListener::class.java)
            val util=Class.forName("com.oplus.quickstep.utils.k",false,loader)
            val route=RecentsParticleContract.batchCard(util,recents,task,batch) ?: error("Unknown batch-card signature")
            hookBatchCard(api,route,addListener)
            // The delegate method is a verified caller of k.c; deopt it so a
            // previously inlined helper cannot bypass this independent hook.
            val delegate=Class.forName("lo.d",false,loader)
            val factory=delegate.getDeclaredMethod("createAllTasksDismissAnimation",Long::class.javaPrimitiveType)
            check(factory.returnType==batch)
            api.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                clearAll++;try {chain.proceed()} finally {clearAll--}
            }
            api.deoptimize(factory)
            mark("stack-batch-hook","COS17 堆叠清空逐卡动画已接入，锁定／快速启动任务仍由原生过滤")
        }.onFailure {mark("stack-batch-unavailable","${it.javaClass.simpleName}: ${it.message}")}
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

    private fun installGesture(api:XposedInterface,loader:ClassLoader) {
        runCatching {
            val playback=Class.forName("com.android.launcher3.anim.AnimatorPlaybackController",false,loader)
            val velocity=RecentsParticleContract.velocity(playback) ?: error("Unknown playback signature")
            val player=playback.getDeclaredMethod("getAnimationPlayer")
            api.hook(velocity).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val context=release
                val receiver=chain.getThisObject()
                var ticket:Ticket?=null
                safe {
                    // Other playbacks can start inside drag end, including reverse/reset animations.
                    if(context!=null && receiver!=null && (context.playback==null || context.playback===receiver) &&
                        RecentsParticleContract.confirmed(context.goingUp,chain.getArg(1)==true))
                        ticket=capture(context.task)
                }
                val result=try {chain.proceed()} catch(error:Throwable) {ticket?.let(::cancel);throw error}
                safe {ticket?.let {t ->
                    val animator=player.invoke(receiver) as? Animator
                    animator?.addListener(object:AnimatorListenerAdapter() {
                        override fun onAnimationCancel(a:Animator) {observer.invoke {cancel(t)}}
                        override fun onAnimationEnd(a:Animator) {observer.finish({}, {a.removeListener(this)})}
                    })
                    accept(t)
                    mark("gesture","原生确认上划删除；当前卡片 GPU 接管")
                }}
                result
            }
            val base=Class.forName("com.android.launcher3.uioverrides.touchcontrollers.TaskViewTouchController",false,loader)
            val task=base.getDeclaredField("mTaskBeingDragged").apply {isAccessible=true}
            val animation=base.getDeclaredField("mCurrentAnimation").apply {isAccessible=true}
            val goingUp=base.getDeclaredField("mCurrentAnimationIsGoingUp").apply {isAccessible=true}
            check(View::class.java.isAssignableFrom(task.type) && animation.type==playback && goingUp.type==Boolean::class.javaPrimitiveType)
            var mountedRoutes=0
            for(name in listOf("uu7","ja.g"))runCatching {
                val concrete=Class.forName(name,false,loader)
                check(base.isAssignableFrom(concrete))
                val end=RecentsParticleContract.dragEnd(concrete) ?: error("Unknown drag-end signature")
                hookRelease(api,end) { owner ->
                    val view=task.get(owner) as? View
                    val controller=animation.get(owner)
                    if(view!=null && controller!=null) Release(view,controller,goingUp.getBoolean(owner)) else null
                }
                mountedRoutes++
                val delegates=if(name=="uu7")listOf("fua","ob4","k6b","yca") else listOf("no.g","no.h","no.d","no.e","no.i")
                for(delegate in delegates)runCatching {
                    RecentsParticleContract.dragEnd(Class.forName(delegate,false,loader))?.let {api.deoptimize(it)}
                }
                if(name=="uu7")installSlideGesture(api,loader)
            }
            check(mountedRoutes>0) {"Unknown task touch adapter"}
            mark("gesture-hook","已接入后台卡片上划删除确认与原生播放控制器")
        }.onFailure {mark("gesture-unavailable","${it.javaClass.simpleName}: ${it.message}")}
    }

    private fun hookRelease(api:XposedInterface,end:Method,resolve:(Any)->Release?) {
        api.hook(end).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            if(Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
            val previous=release
            release=null
            safe {
                val owner=chain.getThisObject()
                if(owner!=null) {
                    val current=resolve(owner)
                    if(current!=null && allowed(current.task.context))release=current
                }
            }
            try {chain.proceed()} finally {release=previous}
        }
        runCatching {api.deoptimize(end)}
    }

    /** The new launcher can run a separate slide controller for a task card. */
    private fun installSlideGesture(api:XposedInterface,loader:ClassLoader) {
        runCatching {
            val slide=Class.forName("com.android.quickstep.uioverrides.touchcontrollers.controller.a",false,loader)
            val end=RecentsParticleContract.dragEnd(slide) ?: error("Unknown slide gesture")
            val task=slide.getDeclaredField("c").apply {isAccessible=true}
            val state=slide.getDeclaredField("z").apply {isAccessible=true}
            val direction=state.type.getDeclaredField("l").apply {isAccessible=true}
            val animation=state.type.getDeclaredField("n").apply {isAccessible=true}
            check(View::class.java.isAssignableFrom(task.type) && direction.type==Boolean::class.javaPrimitiveType)
            val start=animation.type.declaredMethods.singleOrNull {
                it.name=="f" && it.returnType==Void.TYPE && it.parameterCount==4 &&
                    it.parameterTypes[0].name=="com.android.launcher3.BaseDraggingActivity" &&
                    it.parameterTypes.drop(1)==listOf(Boolean::class.javaPrimitiveType,Float::class.javaPrimitiveType,Long::class.javaPrimitiveType)
            } ?: error("Unknown slide commit signature")
            api.hook(end).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val previous=slideRelease
                slideRelease=null
                safe {
                    val owner=chain.getThisObject()
                    val view=task.get(owner) as? View
                    val drag=state.get(owner)
                    val pending=drag?.let {animation.get(it)}
                    if(view!=null && drag!=null && pending!=null && direction.getBoolean(drag) && allowed(view.context))
                        slideRelease=SlideRelease(view,pending)
                }
                try {chain.proceed()} finally {slideRelease=previous}
            }
            api.hook(start).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val slideContext=slideRelease
                if(slideContext==null || slideContext.animation!==chain.getThisObject() || chain.getArg(1)!=true)
                    return@intercept chain.proceed()
                // The checked OEM method starts only this card's player once its guards pass.
                // Observe the actual start without asking the vendor to create a player early.
                val previous=release
                release=Release(slideContext.task,null,true)
                try {chain.proceed()} finally {release=previous}
            }
            runCatching {api.deoptimize(end)}
            runCatching {api.deoptimize(start)}
            mark("slide-hook","已接入新版后台卡片滑动控制器")
        }.onFailure {mark("slide-unavailable","${it.javaClass.simpleName}: ${it.message}")}
    }

    /** Verified Launcher 17.5.5 entry points after the recents classes were obfuscated. */
    private fun installModernBatch(api:XposedInterface,loader:ClassLoader) {
        runCatching {
            val recents=Class.forName("io7",false,loader)
            val task=Class.forName("com.android.quickstep.views.c",false,loader)
            val batch=Class.forName("com.android.launcher3.anim.PendingAnimation",false,loader)
            check(View::class.java.isAssignableFrom(recents) && View::class.java.isAssignableFrom(task))
            val addListener=batch.getDeclaredMethod("addListener",Animator.AnimatorListener::class.java)
            installModernExplicit(api,recents,task,batch,addListener)
            val util=Class.forName("k49",false,loader)
            val card=RecentsParticleContract.batchCard(util,recents,task,batch,"a") ?: error("Unknown clear-all card signature")
            val factory=RecentsParticleContract.clearFactory(recents,batch) ?: error("Unknown clear-all factory")
            api.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                clearAll++
                try {chain.proceed()} finally {clearAll--}
            }
            hookBatchCard(api,card,addListener)
            runCatching {api.deoptimize(factory)}
            for(name in listOf("dn7","jb4","vca"))runCatching {
                val delegate=Class.forName(name,false,loader)
                RecentsParticleContract.clearFactory(delegate,batch)?.let {api.deoptimize(it)}
                val caller=delegate.declaredMethods.singleOrNull {
                    it.name=="u0" && it.returnType==Void.TYPE && it.parameterTypes.contentEquals(arrayOf(View::class.java))
                }
                caller?.let {api.deoptimize(it)}
            }
            mark("batch-1755-hook","已接入新版后台一键清空与逐卡动画")
        }.onFailure {mark("batch-1755-unavailable","${it.javaClass.simpleName}: ${it.message}")}
    }

    private fun hookBatchCard(api:XposedInterface,card:Method,addListener:Method) {
        api.hook(card).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            var ticket:Ticket?=null
            safe {
                val view=chain.getArg(1) as? View
                if(clearAll>0 && view!=null && allowed(view.context))ticket=capture(view,true)
            }
            val result=try {chain.proceed()} catch(error:Throwable) {ticket?.let(::cancel);throw error}
            safe {ticket?.let {bindPending(chain.getArg(3)!!,it,addListener)}}
            result
        }
        runCatching {api.deoptimize(card)}
    }

    private fun installModernExplicit(api:XposedInterface,recents:Class<*>,task:Class<*>,batch:Class<*>,addListener:Method) {
        runCatching {
            val entry=RecentsParticleContract.explicit(recents.superclass ?: error("Missing recents base"),task)
                ?: error("Unknown explicit dismissal")
            val factory=RecentsParticleContract.singleFactory(recents,task,batch)
                ?: error("Unknown single-card factory")
            api.hook(entry).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val removing=chain.getArg(2)==true
                if(removing)explicitDismiss++
                try {chain.proceed()} finally {if(removing)explicitDismiss--}
            }
            api.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                var ticket:Ticket?=null
                safe {
                    val view=chain.getArg(0) as? View
                    if(explicitDismiss>0 && chain.getArg(1)==true && chain.getArg(2)==true &&
                        chain.getArg(4)==false && view!=null && allowed(view.context))ticket=capture(view)
                }
                val result=try {chain.proceed()} catch(error:Throwable) {ticket?.let(::cancel);throw error}
                safe {ticket?.let {if(result!=null)bindPending(result,it,addListener) else cancel(it)}}
                result
            }
            runCatching {api.deoptimize(entry)}
            runCatching {api.deoptimize(factory)}
        }.onFailure {mark("explicit-1755-unavailable","${it.javaClass.simpleName}: ${it.message}")}
    }

    private fun bindPending(animation:Any,t:Ticket,addListener:Method) {
        val group=pending[animation] ?: ArrayList<Ticket>().also { list ->
            // Register before publishing: failed reflection must not leave a
            // list which silently prevents the next card from adding a listener.
            addListener.invoke(animation,object:AnimatorListenerAdapter() {
                override fun onAnimationStart(a:Animator) {list.toList().forEach {t->observer.invoke {accept(t)}}}
                override fun onAnimationCancel(a:Animator) {list.toList().forEach {t->observer.invoke {cancel(t)}}}
                override fun onAnimationEnd(a:Animator) {
                    observer.finish({pending.remove(animation);list.clear()}, {a.removeListener(this)})
                }
            })
            pending[animation]=list
        }
        if(t !in group)group.add(t)
    }

    internal class Ticket(target:View,val root:WeakReference<View>,val node:RenderNode,
        val x:Float,val y:Float,val width:Int,val height:Int,val rawX:Float,val rawY:Float,
        val capturedAt:Long=SystemClock.uptimeMillis(),val batch:Boolean=false) {
        val view=WeakReference(target)
        val taskIdentity=runCatching {target.javaClass.getMethod("getTask").invoke(target)}.getOrNull()
        @Volatile var cancelled=false
        var submitted=false;var hidden=false
        var effectSpeed=100
        var nativeAlpha=target.alpha
        var dust:ParticleDissolveHook.Api33.Dust?=null
        var deadline:Runnable?=null
    }
    internal fun capture(view:View,batch:Boolean=false):Ticket? {
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
        val t=Ticket(view,WeakReference(root),node,rect.left,rect.top,w,h,rawX,rawY,batch=batch)
        tickets[view]=t
        val deadline=Runnable {cancel(t)};t.deadline=deadline;ui.postDelayed(deadline,1800L)
        return t
    }
    internal fun accept(t:Ticket) {
        if(t.cancelled || t.submitted)return
        t.submitted=true
        val acceptedAt=SystemClock.uptimeMillis()
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
                if(image==null || t.cancelled || root==null || !membershipGranted() ||
                    !root.isShown || !interactive(root.context) || !RecentsParticleContract.frameReady(t.batch,
                        view?.isAttachedToWindow==true,SystemClock.uptimeMillis()-acceptedAt)) {cancel(t);return@safe}
                var x=t.x;var y=t.y
                val attached=view!=null && view.isAttachedToWindow && view.rootView===root && sameTask(t,view)
                if(attached) {
                    val matrix=Matrix();view!!.transformMatrixToGlobal(matrix)
                    val rootMatrix=Matrix();root.transformMatrixToGlobal(rootMatrix)
                    val inverse=Matrix();if(!rootMatrix.invert(inverse)) {cancel(t);return@safe}
                    matrix.postConcat(inverse)
                    val now=RectF(0f,0f,view.width.toFloat(),view.height.toFloat());matrix.mapRect(now)
                    x+=now.left-t.rawX;y+=now.top-t.rawY
                }
                // A batch may detach its card before the GPU fence returns.
                // Its already recorded node can still dissolve at the captured
                // root-local position. Never suppress a detached/reparented View.
                val dust=ParticleDissolveHook.Api33.Dust(root,image,x,y,t.width,t.height,
                    root.resources.displayMetrics.density,t.effectSpeed,-1f,.20f,true,remainsAllowed=::membershipGranted) {finish(t)}
                t.dust=dust;dust.start()
                if(attached) {
                    t.nativeAlpha=view!!.alpha;t.hidden=true
                    ownAlpha=true;try {view.alpha=0f} finally {ownAlpha=false}
                }
                mark("playing","任务卡片 GPU 向上飘散；${image.width}x${image.height}")
            }}
        }} catch(error:Throwable) {t.node.discardDisplayList();cancel(t);throw error}
    }
    internal fun nativeAlpha(view:View,requested:Float):Float {
        if(ownAlpha || Looper.myLooper()!=Looper.getMainLooper())return requested
        val ticket=tickets[view] ?: return requested
        if(!sameTask(ticket,view)) {cancel(ticket);return requested}
        if(!ticket.hidden)return requested
        ticket.nativeAlpha=requested
        return 0f
    }
    private fun finish(t:Ticket) {
        t.cancelled=true;t.deadline?.let(ui::removeCallbacks);t.deadline=null
        t.view.get()?.let {v->
            if(tickets[v]===t)tickets.remove(v)
            if(t.hidden && v.alpha==0f && sameTask(t,v)) {ownAlpha=true;try {v.alpha=t.nativeAlpha} finally {ownAlpha=false}}
        }
        t.hidden=false;t.dust=null
    }
    internal fun cancel(t:Ticket) {
        if(t.cancelled)return
        if(!t.submitted)t.node.discardDisplayList()
        t.dust?.stop();finish(t)
    }
    internal fun stopAll() {tickets.values.toList().forEach(::cancel);pending.clear()}
    private fun sameTask(t:Ticket,view:View)=t.taskIdentity==null ||
        runCatching {view.javaClass.getMethod("getTask").invoke(view)===t.taskIdentity}.getOrDefault(false)
}

/** Verified ROM shapes, testable without instantiating vendor classes. */
object RecentsParticleContract {
    fun frameReady(batch:Boolean,attached:Boolean,age:Long)=age in 0L..180L && (batch || attached)
    fun dragEnd(type:Class<*>):Method?=type.declaredMethods.singleOrNull {
        it.name=="onDragEnd" && it.returnType==Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
    }
    @JvmOverloads
    fun batchCard(type:Class<*>,recents:Class<*>,task:Class<*>,pending:Class<*>,name:String="c"):Method?=
        type.declaredMethods.singleOrNull {it.name==name && Modifier.isStatic(it.modifiers) &&
            it.returnType==Void.TYPE && it.parameterTypes.contentEquals(arrayOf(recents,task,Long::class.javaPrimitiveType,pending))}
    fun clearFactory(type:Class<*>,pending:Class<*>):Method?=type.declaredMethods.singleOrNull {
        it.name=="f0" && it.returnType==pending &&
            it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
    }
    fun explicit(type:Class<*>,task:Class<*>):Method?=type.declaredMethods.singleOrNull {
        it.name=="a2" && it.returnType==Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(task,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
    }
    fun singleFactory(type:Class<*>,task:Class<*>,pending:Class<*>):Method?=type.declaredMethods.singleOrNull {
        it.name=="N" && it.returnType==pending &&
            it.parameterTypes.contentEquals(arrayOf(task,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
    }
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
