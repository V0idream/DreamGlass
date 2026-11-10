/* DreamGlass contributors, 2026-10-29. GPL-3.0, as the surrounding project.
 * GPU snapshot lifecycle follows the Android Developers HardwareRenderer example:
 * https://developer.android.com/guide/topics/renderscript/migrate (Apache-2.0 sample).
 * Particle motion/dissolution below is an independent implementation; no video assets are bundled.
 */
package com.coloros16.liquidglass.hook

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One-line integration: ParticleDissolveHook.install(api, classLoader).
 * Only explicit SwipeHelper dismissals of notification rows; never unlock, heads-up timeout or QS.
 * No CPU blur, pixel readback, per-particle View, repeating timer or replacement deletion callback.
 */
object ParticleDissolveHook {
    const val KEY_ENABLED = "notification_dissolve_enabled"
    const val KEY_SPEED = "notification_dissolve_speed_percent"
    const val DEFAULT_SPEED = 100
    const val MIN_SPEED = 35
    const val MAX_SPEED = 300
    const val MAX_ACTIVE = 3
    fun handoffX(capturedX:Float,recordRowTranslation:Float,currentTranslation:Float):Float =
        if(currentTranslation.isFinite() && recordRowTranslation.isFinite())capturedX+currentTranslation-recordRowTranslation else capturedX
    fun handoffProgress(translation:Float,width:Int):Float =
        if(width>0 && translation.isFinite())(.14f+.24f*(kotlin.math.abs(translation)/width).coerceIn(0f,1f)).coerceAtMost(.32f) else .14f
    fun durationMs(speed: Int): Long = 110_000L / speed.coerceIn(MIN_SPEED, MAX_SPEED)
    // Let most of the dissolve be seen, without making slow presets hold the shade for seconds.
    fun clearAllHoldMs(speed:Int,elapsed:Long):Long =
        ((durationMs(speed)*.68).toLong()-elapsed.coerceAtLeast(0)).coerceIn(0L,900L)

    private var reporter: ((String,String)->Unit)? = null
    private fun report(stage:String,detail:String) { runCatching { reporter?.invoke("particles_$stage",detail) } }
    private fun current(stage:String,detail:String) {
        // mark() preserves first arrival, which hid pending -> ready/blocked
        // transitions. Keep current bounded values for device diagnosis.
        runCatching {RuntimeDiagnostics.update("particles_$stage",detail)}
        report(stage,detail)
    }
    fun install(api: XposedInterface, classLoader: ClassLoader, diagnostics: ((String,String)->Unit)? = null) {
        reporter=diagnostics
        if (Build.VERSION.SDK_INT < 33 || Application.getProcessName() != "com.android.systemui") return
        try { Api33.install(api, classLoader) }
        catch (error: Throwable) { current("error","install: ${error.javaClass.simpleName}: ${error.message}");Lg.w("LiquidGlass", "notification particles unavailable", error) }
    }

    @RequiresApi(33)
    internal object Api33 {
        private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        private val ui = Handler(Looper.getMainLooper())
        private val captures = WeakHashMap<View, Capture>() // UI thread only; at most MAX_ACTIVE.
        private val effects = WeakHashMap<View, Dust>()
        private val shadeCloses = WeakHashMap<View, ShadeClose>()
        private val shadeEpochs = WeakHashMap<View,Int>()
        // Explicit clear-all preparation only. Weak identities do not keep removed
        // entries/rows alive; GPU captures remain bounded by the existing limit.
        private class ClearPrepared(item:Any,view:View,val capture:Capture,val close:Boolean) {
            val item=WeakReference(item);val row=WeakReference(view)
        }
        private val clearPrepared=ArrayList<ClearPrepared>(MAX_ACTIVE)
        private var preparationGeneration=0
        private fun releaseClearPreparation(cancelUncommitted:Boolean) {
            preparationGeneration++
            val old=clearPrepared.toList();clearPrepared.clear()
            if(cancelUncommitted)for(p in old) {
                val view=p.row.get() ?: continue
                if(!p.capture.committed && captures[view]===p.capture) {
                    captures.remove(view);p.capture.cancelled=true
                }
            }
        }
        private var replayingClose=false
        private var collectionAcceptSequence=0L
        private val clearCompletions=WeakHashMap<Any,NotificationLocalDismissContract.OnceCompletion>()
        private var clearPreparedBinding:((ClearPrepared)->Boolean)?=null
        private fun hasVisibleClearPreparation(root:View):Boolean {
            val now=SystemClock.uptimeMillis()
            return clearPrepared.any { p ->
                val view=p.row.get()
                val visible=Rect()
                view!=null && p.close && !p.capture.cancelled && !p.capture.committed &&
                    now-p.capture.createdAt<4950L && captures[view]===p.capture &&
                    p.capture.root.get()===root && view.isAttachedToWindow &&
                    view.isShown && view.alpha>.01f && view.getGlobalVisibleRect(visible) &&
                    !visible.isEmpty && clearPreparedBinding?.invoke(p)==true
            }
        }
        private var installed = false
        private var enabled = false
        private var masterEnabled=false
        private var featureEnabled=false
        private var speed = DEFAULT_SPEED
        private var readAt = -1L
        private var retryAt = 0L
        private var interfaceApi: XposedInterface? = null
        private fun membershipGranted(): Boolean = Prefs.memberAllowed(interfaceApi)
        private val worker = ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(MAX_ACTIVE), { task ->
                Thread(task, "LiquidGlass-particle-snapshot").apply { isDaemon = true }
            }, ThreadPoolExecutor.AbortPolicy())

        fun install(api: XposedInterface, loader: ClassLoader) {
            interfaceApi = api
            if (installed) return
            val row = Class.forName(ROW, false, loader)
            val helper = Class.forName("com.android.systemui.SwipeHelper", false, loader)
            // COS17 overrides both the gesture and menu paths in NotificationSwipeHelper.
            // Hook every concrete declaration; Capture.requested/consumed deduplicates wrappers.
            val helpers = listOfNotNull(helper, runCatching {
                Class.forName("com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper", false, loader)
            }.getOrNull(), runCatching {
                // Confirmed stock COS17 callback. Its start/commit events cover clear-all too;
                // clearNotifications() does not call row.performDismiss() for every row.
                Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController\$10", false, loader)
            }.getOrNull()).distinct()
            var mounted = 0
            for (type in helpers) for (route in NotificationMotionContract.routes(type)) {
              try {
                api.hook(route.method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val view = chain.getArg(route.index) as? View
                    if (view != null && row.isInstance(view)) safe {
                        if (route.event == NotificationMotionContract.Event.CANCEL ||
                            route.event == NotificationMotionContract.Event.ABORT || allowed(api, view.context))
                            onGesture(view, route.event)
                    }
                    chain.proceed()
                }
                mounted++
              } catch(error:Throwable) { report("route_error","${route.method.name}: ${error.javaClass.simpleName}") }
            }
            // Callers compiled before installation can inline the small callback wrappers.
            for(type in helpers) for(method in type.declaredMethods) {
                if(method.name in setOf("onTouchEvent","onInterceptTouchEvent","dismissChild","superDismissChild","dismiss","onDownUpdate"))
                    runCatching { api.deoptimize(method) }
            }
            // Native row deletion is the commit point. An attempted swipe/menu open is not deletion.
            val commit = row.getDeclaredMethod("performDismiss", Boolean::class.javaPrimitiveType)
            api.hook(commit).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.getThisObject() as? View
                if (view != null) safe { if (allowed(api, view.context)) request(view) }
                val result = chain.proceed()
                if (view != null) safe { if (allowed(api, view.context)) dismiss(view) }
                result
            }
            Lg.i("LiquidGlass", "particle gesture routes=$mounted; native row commit installed")
            report("hook","gesture routes=$mounted; native commit installed")
            // This optional route must not prevent the gesture routes from installing.
            runCatching { installClearAllClose(api,loader) }.onFailure {
                report("clear_all_route","原生收起保留：${it.javaClass.simpleName}")
            }
            runCatching {installMixedGroupCommit(api,loader,row)}.onFailure {
                report("mixed_group_route","保留原生集合删除：${it.javaClass.simpleName}")
            }
            installed = true
        }

        /** Collection acceptance covers animation-less removal as well as
         * ordinary child rows inside a retained group. Do not infer acceptance
         * from package/group UI relationships or alter native deletion arguments. */
        private fun installMixedGroupCommit(api:XposedInterface,loader:ClassLoader,row:Class<*>) {
            val collection=Class.forName("com.android.systemui.statusbar.notification.collection.NotifCollection",false,loader)
            val entry=Class.forName("com.android.systemui.statusbar.notification.collection.NotificationEntry",false,loader)
            val route=NotificationLocalDismissContract.resolve(collection,entry,row) ?: error("Unknown local dismissal contract")
            val stack=Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",false,loader)
            val adapterGetter=row.getMethod("getEntryAdapter")
            val adapterEntry=adapterGetter.returnType.getMethod("getEntry")
            // Preparation is optional: a changed vendor signature must not disable
            // the more important collection commit route.
            clearPreparedBinding={ p ->
                val item=p.item.get();val view=p.row.get()
                item!=null && view!=null && entry.isInstance(item) &&
                    route.clearable.invoke(item)==true && route.state.invoke(item).toString()=="NOT_DISMISSED" &&
                    adapterGetter.invoke(view)?.let {adapterEntry.invoke(it)}===item
            }
            val prep=NotificationLocalDismissContract.prepareRoute(stack,row)
            if(prep!=null) {
                api.hook(prep.clear).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    if(Looper.myLooper()==Looper.getMainLooper())safe {
                        val owner=chain.getThisObject() as? ViewGroup
                        if(owner!=null && allowed(api,owner.context)) {
                            releaseClearPreparation(true)
                            val selection=chain.getArg(0) as Int
                            val close=chain.getArg(1)==true
                            var scanned=0;var hidden=0;var retained=0;var capacity=0
                            val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View,Boolean>())
                            fun visit(view:View) {
                                if(scanned>=128 || !row.isInstance(view) || !seen.add(view))return
                                scanned++
                                val adapter=prep.adapter.invoke(view)
                                val item=adapter?.let {prep.entry.invoke(it)}
                                val clearable=item!=null && entry.isInstance(item) && route.clearable.invoke(item)==true
                                val eligible=clearable && route.state.invoke(item).toString()=="NOT_DISMISSED" &&
                                    prep.includes.invoke(null,view,selection)==true
                                if(eligible) {
                                    if(effects.containsKey(view) || captures[view]?.committed==true)return
                                    val visible=Rect()
                                    if(!view.isShown || view.alpha<=.01f || !view.getGlobalVisibleRect(visible) || visible.isEmpty)hidden++
                                    else {
                                        val existing=captures[view]?.takeIf {!it.cancelled && SystemClock.uptimeMillis()-it.createdAt<4950L}
                                        if(existing==null && (captures.size>=MAX_ACTIVE || effects.size>=MAX_ACTIVE))capacity++
                                        else {
                                            val c=existing ?: prepare(view)
                                            if(c!=null)clearPrepared.add(ClearPrepared(item!!,view,c,close))
                                        }
                                    }
                                    // A clearable summary already contains the child pixels.
                                    // Avoid overlapping parent/child captures for normal groups.
                                    return
                                }
                                retained++
                                val children=prep.children.invoke(view) as? List<*> ?: return
                                for(child in children)if(child is View)visit(child)
                            }
                            for(i in 0 until owner.childCount)visit(owner.getChildAt(i))
                            val generation=preparationGeneration
                            ui.postDelayed({if(preparationGeneration==generation)releaseClearPreparation(true)},5000L)
                            current("clear_prepare","scanned=$scanned; prepared=${clearPrepared.size}; retained=$retained; hidden=$hidden; capacity=$capacity; close=$close")
                        }
                    }
                    chain.proceed()
                }
                runCatching {api.deoptimize(prep.clear)}
            } else current("clear_prepare","厂商clearNotifications准备接口未匹配；集合提交仍注册")
            api.hook(route.commit).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val candidates=ArrayList<Pair<Any,View>>(MAX_ACTIVE)
                if(Looper.myLooper()==Looper.getMainLooper())safe {
                    val entries=chain.getArg(0) as? List<*> ?: emptyList<Any>()
                    var retained=0;var missingRow=0;var duplicate=0;var missedCapture=0;var warm=0
                    val acceptedRows=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View,Boolean>())
                    for(item in entries)if(item!=null && entry.isInstance(item) && route.clearable.invoke(item)==true &&
                        route.state.invoke(item).toString()=="NOT_DISMISSED")
                        (route.row.invoke(item) as? View)?.let {acceptedRows.add(it)}
                    for(item in entries) {
                        if(candidates.size>=MAX_ACTIVE)break
                        if(item==null || !entry.isInstance(item) || route.state.invoke(item).toString()!="NOT_DISMISSED" ||
                            route.clearable.invoke(item)!=true) {retained++;continue}
                        val view=route.row.invoke(item) as? View
                        if(view==null) {missingRow++;continue}
                        if(!allowed(api,view.context))break
                        val binding=adapterGetter.invoke(view)?.let {adapterEntry.invoke(it)}
                        if(binding!=null && entry.isInstance(binding) && binding!==item) {
                            missingRow++;current("collection_binding_skip","row被另一个NotificationEntry复用；忽略旧entry，未读取通知内容")
                            continue
                        }
                        if(effects.containsKey(view) || captures[view]?.committed==true ||
                            NotificationLocalDismissContract.coveredByAncestor(view,{it.parent as? View}) {
                                acceptedRows.contains(it) || effects.containsKey(it) || captures[it]?.committed==true
                            }) {duplicate++;continue}
                        val prepared=clearPrepared.firstOrNull {it.item.get()===item && it.row.get()===view &&
                            captures[view]===it.capture && !it.capture.cancelled}
                        if(prepared!=null)warm++
                        val old=captures[view]?.takeIf {!it.cancelled && SystemClock.uptimeMillis()-it.createdAt<4950L}
                        val visible=Rect()
                        if(old==null && (!view.isShown || view.alpha<=.01f || !view.getGlobalVisibleRect(visible) || visible.isEmpty)) {
                            missedCapture++
                            current("collection_capture_skip","当前无可见像素且没有准备快照；rowShown=${view.isShown}; rowAlpha=${view.alpha}; rootShown=${view.rootView.isShown}; rootVisibility=${view.rootView.windowVisibility}")
                            continue
                        }
                        if(request(view)!=null)candidates.add(item to view)
                        else {
                            missedCapture++
                            current("collection_capture_skip","rowShown=${view.isShown}; rowAttached=${view.isAttachedToWindow}; rootShown=${view.rootView.isShown}; rootVisibility=${view.rootView.windowVisibility}; prepared=${prepared!=null}; close=${prepared?.close}")
                        }
                    }
                    current("collection_candidates","entries=${entries.size}; prepared=$warm; capture=${candidates.size}; retained=$retained; missingRow=$missingRow; duplicate=$duplicate; captureSkipped=$missedCapture")
                }
                val result=chain.proceed()
                for((item,view) in candidates)safe {
                    val after=route.state.invoke(item).toString()
                    val accepted=NotificationLocalDismissContract.accepted(true,"NOT_DISMISSED",after)
                    clearPrepared.removeAll {it.item.get()===item && it.row.get()===view}
                    if(accepted && allowed(api,view.context)) {
                        collectionAcceptSequence++
                        dismiss(view)
                        current("mixed_group_commit","集合已确认普通通知删除；state=$after; rowShown=${view.isShown}; rootShown=${view.rootView.isShown}; parent关系不作为门限")
                    } else {
                        cancel(view)
                        current("collection_rejected","未确认新删除，保持原生；state=$after")
                    }
                }
                result
            }
            collection.declaredMethods.filter {it.name in setOf("dismissNotifications","locallyDismissNotifications")}
                .forEach {runCatching {api.deoptimize(it)}}
            runCatching {api.deoptimize(stack.getDeclaredMethod("onClearAllAnimationsEnd",Int::class.javaPrimitiveType,List::class.java))}
            report("mixed_group_route","COS17集合提交及可见行准备已接入；不要求父子关系，不修改常驻保留和删除列表")
        }

        private fun installClearAllClose(api:XposedInterface,loader:ClassLoader) {
            val stack=Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",false,loader)
            val callback=Class.forName(stack.name+"\$\$ExternalSyntheticLambda7",false,loader)
            // COS17 shares ONE Lambda5 completion between the end consumer and
            // post-collapse action (stock source lines 1606-1626). Lambda5 false
            // runs onClearAllAnimationsEnd; true posts the native equivalent.
            // The no-close branch must run that same native callback, not a
            // synthetic collection deletion or a second clearNotifications call.
            val route=NotificationClearAllContract.resolve(callback,stack) ?: error("Unknown clear-all callback contract")
            val completionRoute=NotificationLocalDismissContract.completionRoute(route.completion.type,stack)
                ?: error("Unknown clear-all completion contract")
            api.hook(completionRoute).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val receiver=chain.getThisObject()
                val once=if(Looper.myLooper()==Looper.getMainLooper())clearCompletions[receiver] else null
                // Only our exact retained callback instances are guarded. The
                // original first invocation always executes, including the
                // OEM's post-to-main path; its registered collapse replay is a no-op.
                if(once!=null && !once.claim())null else chain.proceed()
            }
            val expanded=stack.getDeclaredField("mIsExpanded").apply { isAccessible=true }
            val needsClose=stack.getDeclaredField("mShadeNeedsToClose").apply { isAccessible=true }
            api.hook(route.method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(replayingClose || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val receiver=chain.getThisObject() ?: return@intercept chain.proceed()
                val owner=route.owner.get(receiver) as? View ?: return@intercept chain.proceed()
                val retain=runCatching {
                    route.close.getBoolean(receiver) && expanded.getBoolean(owner) && needsClose.getBoolean(owner) &&
                        owner.isAttachedToWindow && owner.isShown && allowed(api,owner.context) &&
                        hasVisibleClearPreparation(owner.rootView)
                }.getOrDefault(false)
                if(retain) {
                    val completion=route.completion.get(receiver)
                    if(completion!=null) {
                        // Field writes and matching happen before native execution.
                        // If the ROM refuses the field write, take the original path.
                        val changed=runCatching {route.close.setBoolean(receiver,false)}.isSuccess
                        if(changed) {
                            clearCompletions[completion]=NotificationLocalDismissContract.OnceCompletion()
                            val acceptedBefore=collectionAcceptSequence
                            val shadeEpoch=shadeEpochs[owner] ?: 0
                            val result=try {chain.proceed()} finally {route.close.setBoolean(receiver,true)}
                            val argument=chain.getArg(0)
                            // Lambda5 can post its native commit. This single queued
                            // continuation follows it, then holds only ACCEPTED dust.
                            // Rejected/intercepted rows never dissolve or delay collapse.
                            ui.post {safe {
                                val close={
                                    replayingClose=true
                                    try {route.method.invoke(receiver,argument);Unit} finally {replayingClose=false}
                                }
                                val stillOpen=owner.isAttachedToWindow && owner.isShown &&
                                    (shadeEpochs[owner] ?: 0)==shadeEpoch &&
                                    runCatching {expanded.getBoolean(owner)}.getOrDefault(false)
                                if(stillOpen) {
                                    var deferred=false
                                    try {
                                        val delay=if(collectionAcceptSequence!=acceptedBefore && allowed(api,owner.context))
                                            acceptedShadeHold(owner.rootView) else 0L
                                        deferred=deferClearAll(owner,delay,close)
                                        current("clear_close_accepted","native completion executed once; acceptedHold=${delay}ms")
                                    } finally {
                                        // Even a diagnostic/scheduling error must release the
                                        // original close path; completion itself remains once.
                                        if(!deferred)safe {close()}
                                    }
                                }
                            }}
                            return@intercept result
                        }
                    }
                }
                var deferred=false
                safe {
                    if(route.close.getBoolean(receiver) && expanded.getBoolean(owner) && needsClose.getBoolean(owner) && allowed(api,owner.context)) {
                        val delay=acceptedShadeHold(owner.rootView)
                        if(delay>0 || shadeCloses.containsKey(owner)) {
                            val argument=chain.getArg(0)
                            deferred=deferClearAll(owner,delay) {
                                replayingClose=true
                                try {route.method.invoke(receiver,argument)} finally {replayingClose=false}
                            }
                        }
                    }
                }
                if(deferred)null else chain.proceed()
            }
            runCatching {api.deoptimize(completionRoute)}
            // Manual shade closing takes priority. Never collapse a newly reopened panel later.
            stack.getDeclaredMethod("setIsExpanded",Boolean::class.javaPrimitiveType).let { method ->
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if(chain.getArg(0)==false && Looper.myLooper()==Looper.getMainLooper())
                        (chain.getThisObject() as? View)?.let {owner ->
                            shadeEpochs[owner]=(shadeEpochs[owner] ?: 0)+1
                            cancelShadeClose(owner)
                        }
                    chain.proceed()
                }
            }
            stack.declaredMethods.filter { it.name=="clearNotifications" }.forEach { runCatching { api.deoptimize(it) } }
            report("clear_all_route","一键清除收起回调已接入；只在粒子播放期间短暂等待")
        }

        private fun acceptedShadeHold(root:View):Long {
            val now=SystemClock.uptimeMillis()
            return maxOf(
                effects.values.filter {it.belongsTo(root)}.maxOfOrNull {it.shadeHoldMs(now)} ?: 0L,
                captures.values.filter {it.committed && !it.cancelled && it.root.get()===root}
                    .maxOfOrNull {clearAllHoldMs(speed,0)} ?: 0L)
        }

        /** Own one bounded callback, never an Xposed Chain. Also used by the lifecycle probe. */
        internal fun deferClearAll(owner:View,delay:Long,completion:()->Unit):Boolean {
            if(shadeCloses.containsKey(owner))return true
            if(delay<=0 || !owner.isAttachedToWindow || !owner.isShown)return false
            val close=ShadeClose(owner,completion)
            shadeCloses[owner]=close
            owner.addOnAttachStateChangeListener(close)
            ui.postDelayed(close,delay.coerceAtMost(900L))
            report("clear_all_wait","等待粒子播放 ${delay.coerceAtMost(900L)}ms 后由系统收起")
            return true
        }
        internal fun cancelShadeClose(owner:View) { shadeCloses[owner]?.finish(false) }
        private class ShadeClose(owner:View,private var completion:(()->Unit)?):Runnable,View.OnAttachStateChangeListener {
            private val owner=WeakReference(owner)
            override fun run() { finish(true) }
            fun finish(run:Boolean) {
                val callback=completion ?: return
                completion=null;ui.removeCallbacks(this)
                val view=owner.get()
                if(view!=null) { shadeCloses.remove(view);view.removeOnAttachStateChangeListener(this) }
                if(run && view?.isAttachedToWindow==true) safe { callback() }
            }
            override fun onViewAttachedToWindow(v:View) {}
            override fun onViewDetachedFromWindow(v:View) { finish(false) }
        }

        internal fun onGesture(view: View, event: NotificationMotionContract.Event) {
            if (Looper.myLooper() != Looper.getMainLooper()) return
            report("event_${event.name.lowercase()}","${event.name}: ${view.javaClass.simpleName}")
            when (event) {
                // A touch-down is not deletion. Record only once native dismissal
                // begins, or explicit clear-all selects a visible clearable row.
                NotificationMotionContract.Event.PREPARE -> Unit
                NotificationMotionContract.Event.REQUEST -> request(view)
                NotificationMotionContract.Event.ACCEPT -> dismiss(view)
                NotificationMotionContract.Event.CANCEL -> cancel(view)
                NotificationMotionContract.Event.COMMIT -> if (captures[view]?.requested == true) dismiss(view)
                NotificationMotionContract.Event.ABORT -> abort(view)
            }
        }

        private inline fun safe(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                retryAt = SystemClock.uptimeMillis() + 30_000L
                current("error","${error.javaClass.simpleName}: ${error.message}")
                try { Lg.w("LiquidGlass", "notification particles skipped; native dismissal retained", error) }
                catch (_: Throwable) { }
            }
        }
        private fun allowed(api: XposedInterface, context: Context): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) return false
            if (!membershipGranted()) {
                current("gate","member=false; enabled=false; reason=member_confirmation_missing")
                enabled = false; readAt = -1L; stopAll(); return false
            }
            val now = SystemClock.uptimeMillis()
            if (now < retryAt) {
                current("gate","member=true; enabled=$enabled; reason=error_cooldown")
                return false
            }
            if (readAt < 0 || now - readAt >= 500) {
                readAt = now
                val prefs = Prefs.read(api)
                masterEnabled=prefs.getBoolean(Prefs.KEY_MASTER,false)
                featureEnabled=prefs.getBoolean(KEY_ENABLED,false)
                enabled=masterEnabled && featureEnabled
                speed = Prefs.readIntCompat(prefs, KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
                if (!enabled) stopAll()
                current("enabled",if(enabled)"enabled" else "disabled")
            }
            val power = context.getSystemService(PowerManager::class.java)
            val animators=ValueAnimator.areAnimatorsEnabled()
            val interactive=power?.isInteractive==true
            val powerSave=power?.isPowerSaveMode==true
            val result=enabled && animators && interactive && !powerSave
            current("gate","member=true; master=$masterEnabled; feature=$featureEnabled; enabled=$enabled; animators=$animators; interactive=$interactive; powerSave=$powerSave; allowed=$result")
            return result
        }

        private class Capture(val root: WeakReference<View>, val x: Float, val y: Float,
            val width: Int, val height: Int, val density: Float, val node: RenderNode,
            val createdAt: Long = SystemClock.uptimeMillis()) {
            @Volatile var bitmap: Bitmap? = null
            @Volatile var cancelled = false
            var requested = false
            var committed = false
            var consumed = false
            var rowTranslation=0f
            var releaseX=x;var releaseY=y
            var direction=1f;var initialProgress=0f
            var hiddenAlpha:Float?=null
            var finished: (() -> Unit)? = null
        }
        private fun prepare(view: View): Capture? {
            captures.remove(view)?.cancelled = true
            val root = view.rootView
            if (!view.isShown || !view.isHardwareAccelerated || root == view ||
                view.width <= 0 || view.height <= 0 || captures.size >= MAX_ACTIVE || effects.size >= MAX_ACTIVE) return null
            val pos = IntArray(2);val parent = IntArray(2)
            view.getLocationInWindow(pos);root.getLocationInWindow(parent)
            if (pos[1] + view.height <= parent[1] || pos[1] >= parent[1] + root.height) return null
            // ExpandableNotificationRow may measure its tallest content layout even while a
            // short card is visible. Record the native actual-height, not that offscreen area.
            val actualHeight=runCatching { (view.javaClass.getMethod("getActualHeight").invoke(view) as Number).toInt() }
                .getOrDefault(view.height).coerceIn(1,view.height)
            val node = RenderNode("LiquidGlass dismissed notification")
            node.setPosition(0, 0, view.width, actualHeight)
            val canvas = node.beginRecording(view.width, actualHeight)
            try {
                // COS17 slides mTranslateableViews instead of the row itself. Compensate only
                // that child offset in the recording canvas; never mutate the host View.
                val contentShift = runCatching {
                    (view.javaClass.getMethod("getTranslation").invoke(view) as Number).toFloat() - view.translationX
                }.getOrDefault(0f)
                if (contentShift.isFinite()) canvas.translate(-contentShift, 0f)
                view.draw(canvas)
            } finally { node.endRecording() }
            val c = Capture(WeakReference(root), (pos[0]-parent[0]).toFloat(), (pos[1]-parent[1]).toFloat(),
                view.width, actualHeight, view.resources.displayMetrics.density, node)
            captures[view] = c
            c.rowTranslation=view.translationX
            current("capture","GPU recorded ${c.width}x${c.height}; root=${root.javaClass.simpleName}")
            val weak = WeakReference(view)
            ui.postDelayed({ safe { weak.get()?.let { row ->
                if (captures[row] === c) { captures.remove(row);c.cancelled = true }
            } } }, 5000L)
            try {
                worker.execute {
                    try {
                        if (!c.cancelled) {
                            val image = snapshot(c)
                            // Never recycle an image that may have reached a recorded shader.
                            if (!c.cancelled) { c.bitmap = image;current("snapshot","hardware texture ${image.width}x${image.height}") }
                        }
                    } catch (error: Throwable) {
                        ui.post { safe { throw error } }
                    } finally {
                        c.node.discardDisplayList()
                        ui.post { safe { c.finished?.invoke() } }
                    }
                }
            } catch (error: Throwable) {
                captures.remove(view);c.cancelled = true;node.discardDisplayList();throw error
            }
            return c
        }
        private fun request(view: View): Capture? {
            if (effects.containsKey(view)) return null
            val old = captures[view]
            val c = if (old == null || old.cancelled || SystemClock.uptimeMillis()-old.createdAt > 4950)
                prepare(view) else old
            c?.requested = true
            return c
        }
        private fun dismiss(view: View) {
            val image = request(view) ?: return
            if (image.committed) return
            image.committed = true
            val translation=runCatching { (view.javaClass.getMethod("getTranslation").invoke(view) as Number).toFloat() }.getOrDefault(view.translationX)
            image.releaseX=handoffX(image.x,image.rowTranslation,translation)
            image.direction=if(translation<0f)-1f else 1f
            image.initialProgress=handoffProgress(translation,image.width)
            // Keep the committed row until the bounded GPU job completes; native removal can
            // otherwise release its last strong reference before the image is ready.
            image.finished = { start(view, image) }
            if (image.bitmap != null) start(view, image)
        }
        private fun start(view: View, c: Capture) {
            if (c.cancelled || c.consumed || !c.committed) return
            if (!membershipGranted()) { c.cancelled = true; captures.remove(view); stopAll(); return }
            val bitmap = c.bitmap ?: return
            val root = c.root.get() ?: return
            val power = root.context.getSystemService(PowerManager::class.java)
            if (!root.isAttachedToWindow || !root.isShown || power?.isInteractive != true ||
                power.isPowerSaveMode || !ValueAnimator.areAnimatorsEnabled() || effects.size >= MAX_ACTIVE) {
                current("collection_start_skip","rootAttached=${root.isAttachedToWindow}; rootShown=${root.isShown}; rootVisibility=${root.windowVisibility}; active=${effects.size}; 原生删除仍执行")
                captures.remove(view);c.cancelled = true;return
            }
            c.consumed = true;captures.remove(view)
            if(view.isAttachedToWindow) {
                val current=runCatching { (view.javaClass.getMethod("getTranslation").invoke(view) as Number).toFloat() }.getOrDefault(view.translationX)
                val currentX=handoffX(c.x,c.rowTranslation,current)
                if(current.isFinite() && (currentX-c.releaseX)*c.direction>=0f)c.releaseX=currentX
            }
            val dust = Dust(root, bitmap, c.releaseX, c.releaseY, c.width, c.height, c.density, speed,c.direction,c.initialProgress,
                remainsAllowed = ::membershipGranted) {
                c.hiddenAlpha?.let { old -> if(view.alpha==0f)view.alpha=old };c.hiddenAlpha=null
                effects.remove(view)
                if(!membershipGranted())ui.post { stopAll() } // Release deferred native shade close as well.
            }
            effects[view] = dust
            dust.start()
            c.hiddenAlpha=view.alpha;view.alpha=0f
            current("handoff","已从滑动位置接管：x=${c.releaseX.toInt()}；原生删除回调继续运行")
            current("playing","GPU dust started")
        }
        private fun cancel(view: View) {
            val c = captures[view]
            if (c?.committed == true || effects.containsKey(view)) return
            captures.remove(view)?.cancelled = true
        }
        private fun abort(view: View) {
            captures.remove(view)?.cancelled = true
            effects.remove(view)?.stop()
        }
        internal fun stopAll() {
            if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { stopAll() };return }
            releaseClearPreparation(true)
            captures.values.forEach { it.cancelled = true };captures.clear()
            effects.values.toList().forEach { it.stop() };effects.clear()
            // Disabling particles releases native completion immediately, without another hold.
            shadeCloses.values.toList().forEach { it.finish(true) }
        }

        /** Pure GPU render-to-texture. No getPixels, software Canvas, CPU scaling or blur. */
        private fun snapshot(c: Capture): Bitmap = snapshotNode(c.node,c.width,c.height)
        internal fun snapshotNode(source:RenderNode,width:Int,height:Int,maxWidth:Int=768,maxHeight:Int=384):Bitmap {
            val scale = minOf(1f,maxWidth.toFloat()/width,maxHeight.toFloat()/height)
            val w = (width*scale).toInt().coerceAtLeast(1);val h = (height*scale).toInt().coerceAtLeast(1)
            val reader = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val renderer = HardwareRenderer()
            val node = RenderNode("LiquidGlass particle snapshot")
            try {
                renderer.isOpaque = false;renderer.setSurface(reader.surface);renderer.setContentRoot(node)
                node.setPosition(0,0,w,h)
                val canvas = node.beginRecording(w,h)
                try { canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);canvas.scale(scale,scale);canvas.drawRenderNode(source) }
                finally { node.endRecording() }
                val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                    HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED) == 0)
                val image = reader.acquireNextImage() ?: error("Particle image unavailable")
                try {
                    val buffer = image.hardwareBuffer ?: error("Particle buffer unavailable")
                    try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("Particle bitmap unavailable") }
                    finally { buffer.close() }
                } finally { image.close() }
            } finally { renderer.destroy();reader.close();node.discardDisplayList() }
        }

        internal class Dust(private val root: View, bitmap: Bitmap, x: Float, y: Float,
            width: Int, height: Int, density: Float, private val speed: Int, direction:Float=1f,
            private val initialProgress:Float=0f,private val vertical:Boolean=false,
            private val remainsAllowed: (() -> Boolean)? = null, private val ended: () -> Unit)
            : Drawable(), Choreographer.FrameCallback, View.OnAttachStateChangeListener {
            private val shader = RuntimeShader(SHADER)
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@Dust.shader }
            private val born = SystemClock.uptimeMillis()
            private val duration = durationMs(speed)
            private var stopped = false
            private val frameClock = Choreographer.getInstance()
            private val pad = minOf(width*.4f,180f*density)
            private var lastDraw = 0L
            private var lastPowerCheck = 0L
            private var powerAllowed = true
            fun belongsTo(view:View)=root===view
            fun shadeHoldMs(now:Long)=if(stopped)0L else clearAllHoldMs(speed,now-born)
            init {
                setBounds((x-pad).toInt(),(y-pad).toInt(),(x+width+pad).toInt(),(y+height+pad).toInt())
                val source = BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
                source.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                shader.setInputShader("uImage",source)
                shader.setFloatUniform("uSize",width.toFloat(),height.toFloat())
                // Child-shader sampling uses explicit coordinates; do not rely on localMatrix.
                shader.setFloatUniform("uImageScale",bitmap.width.toFloat()/width,bitmap.height.toFloat()/height)
                shader.setFloatUniform("uPad",pad)
                shader.setFloatUniform("uCell",(1.0f*density).coerceAtLeast(1.2f))
                shader.setFloatUniform("uProgress",initialProgress)
                shader.setFloatUniform("uDirection",direction)
                shader.setFloatUniform("uVertical",if(vertical)1f else 0f)
            }
            fun start() { root.overlay.add(this);root.addOnAttachStateChangeListener(this);frameClock.postFrameCallback(this) }
            fun stop() {
                if (stopped) return
                stopped = true;frameClock.removeFrameCallback(this)
                root.overlay.remove(this);root.removeOnAttachStateChangeListener(this);ended()
            }
            override fun doFrame(time: Long) {
                try {
                    if (stopped) return
                    if (remainsAllowed?.invoke() == false) { stop();return }
                    val now = SystemClock.uptimeMillis()
                    val elapsed = now-born
                    if (now-lastPowerCheck >= 200L) {
                        lastPowerCheck=now
                        val power = root.context.getSystemService(PowerManager::class.java)
                        powerAllowed=power?.isInteractive == true && !power.isPowerSaveMode && ValueAnimator.areAnimatorsEnabled()
                    }
                    if (elapsed >= duration || !root.isShown || root.windowVisibility != View.VISIBLE || !powerAllowed) { stop();return }
                    if (time-lastDraw >= 30_000_000L) {
                        lastDraw=time;shader.setFloatUniform("uProgress",initialProgress+(1f-initialProgress)*elapsed.toFloat()/duration);invalidateSelf()
                    }
                    frameClock.postFrameCallback(this)
                } catch(error:Throwable) { stop();safe { throw error } }
            }
            override fun draw(canvas: Canvas) {
                if (stopped || !canvas.isHardwareAccelerated) return
                if (remainsAllowed?.invoke() == false) { ui.post { stop() };return }
                val saved=canvas.save()
                try { canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
                    canvas.drawRect(0f,0f,bounds.width().toFloat(),bounds.height().toFloat(),paint)
                } catch(error:Throwable) { ui.post { stop();safe { throw error } } }
                finally { canvas.restoreToCount(saved) }
            }
            override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
            @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun onViewAttachedToWindow(v: View) { }
            override fun onViewDetachedFromWindow(v: View) { stop() }
        }

        // All per-fragment motion, grain coverage, source colour and fading execute in AGSL on GPU.
        // Smooth inverse advection avoids a CPU particle buffer or a sparse blur sampling lattice.
        internal const val SHADER = """
            uniform shader uImage;
            uniform float2 uSize;
            uniform float2 uImageScale;
            uniform float uProgress;
            uniform float uCell;
            uniform float uPad;
            uniform float uDirection;
            uniform float uVertical;
            float hash(float2 p) { return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
            float2 flow(float2 p, float t) {
                float2 n=p/max(uSize,float2(1.0));
                float sign=uDirection<0.0?-1.0:1.0;
                float2 horizontal=float2(sign*(0.35+0.18*sin(n.y*9.0+n.x*4.0))*uSize.x,
                    (-0.28+0.18*sin(n.x*8.0+n.y*3.0))*uSize.y);
                float2 vertical=float2(0.12*sin(n.y*8.0+n.x*3.0)*uSize.x,
                    sign*(0.35+0.18*sin(n.x*9.0+n.y*4.0))*uSize.y);
                return mix(horizontal,vertical,uVertical)*t*t;
            }
            half4 main(float2 pos) {
                float t=clamp(uProgress,0.0,1.0);
                if(t>=0.999)return half4(0.0);
                float2 p=pos-uPad;
                float2 q=p;
                for(int i=0;i<3;i++)q=p-flow(q,t);
                float2 id=floor(q/uCell);
                float rnd=hash(id);
                float wave=0.24*(1.0-q.y/uSize.y)+0.10*rnd;
                float dust=smoothstep(wave,wave+0.13,t);
                float2 centre=0.15+0.70*float2(hash(id+17.0),hash(id+41.0));
                float2 grain=fract(q/uCell)-centre;
                float radius=0.10+0.15*hash(id+71.0);
                float dotMask=1.0-smoothstep(radius-0.10,radius+0.10,length(grain));
                float fade=1.0-smoothstep(0.36+0.28*rnd,0.78+0.20*rnd,t);
                float inside=(q.x>=0.0 && q.y>=0.0 && q.x<uSize.x && q.y<uSize.y)?1.0:0.0;
                half4 motes=uImage.eval((id+centre)*uCell*uImageScale)*half(dotMask*dust*fade*inside);
                half4 solid=half4(0.0);
                if(p.x>=0.0 && p.y>=0.0 && p.x<uSize.x && p.y<uSize.y) {
                    float localWave=0.24*(1.0-p.y/uSize.y)+0.10*hash(floor(p/uCell));
                    float keep=1.0-smoothstep(localWave,localWave+0.13,t);
                    solid=uImage.eval(p*uImageScale)*half(keep);
                }
                return motes+solid*(1.0-motes.a);
            }
        """
    }
}

/** Reject changed OEM callback layouts rather than guessing which boolean controls collapse. */
object NotificationClearAllContract {
    data class Route(val method:Method,val owner:Field,val close:Field,val completion:Field)
    fun resolve(callback:Class<*>,stack:Class<*>):Route? {
        if(!java.util.function.Consumer::class.java.isAssignableFrom(callback))return null
        val fields=callback.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val owner=fields.singleOrNull { it.type==stack } ?: return null
        val close=fields.singleOrNull { it.type==Boolean::class.javaPrimitiveType } ?: return null
        if(fields.size!=3 || fields.none { java.util.function.Consumer::class.java.isAssignableFrom(it.type) })return null
        val method=callback.declaredMethods.singleOrNull { it.name=="accept" && it.returnType==Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && !Modifier.isAbstract(it.modifiers) } ?: return null
        val completion=fields.singleOrNull {java.util.function.Consumer::class.java.isAssignableFrom(it.type)} ?: return null
        owner.isAccessible=true;close.isAccessible=true;completion.isAccessible=true;method.isAccessible=true
        return Route(method,owner,close,completion)
    }
}

/** Match the actual View argument, including COS17 snapChild(float,float,View).
 * Kept separate from Xposed so ROM-shaped fixtures can exercise the same resolver. */
object NotificationMotionContract {
    enum class Event { PREPARE, REQUEST, ACCEPT, CANCEL, COMMIT, ABORT }
    data class Route(val method: Method, val index: Int, val event: Event)
    fun routes(type: Class<*>): List<Route> = type.declaredMethods.mapNotNull { method ->
        val event = when (method.name) {
            "onDownUpdate" -> Event.PREPARE
            "dismissChild", "dismiss", "superDismissChild" -> Event.REQUEST
            "snapChild", "snapClosed", "snapOpen", "onChildSnappedBack" -> Event.CANCEL
            "onSwipeOutAnimStart" -> Event.ACCEPT
            "onChildDismissed", "handleChildViewDismissed" -> Event.COMMIT
            "onChildNotDismissed" -> Event.ABORT
            else -> return@mapNotNull null
        }
        val index = method.parameterTypes.indexOfFirst { View::class.java.isAssignableFrom(it) }
        if (index < 0 || method.returnType != Void.TYPE || Modifier.isAbstract(method.modifiers)) null else Route(method, index, event)
    }
}
