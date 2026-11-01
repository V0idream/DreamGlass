/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.sin

/** COS17 renders some cards through a new background path. Transform the row RenderNode,
 * once, after native pre-draw instead of relying on a background shader being re-recorded.
 * No translation is written: native dragging, removal and touch coordinates remain authoritative.
 */
object NotificationTiltCompat {
    private val active = WeakHashMap<View, Animation>()
    fun stopAll() {
        if (Looper.myLooper()!=Looper.getMainLooper()) {
            android.os.Handler(Looper.getMainLooper()).post { stopAll() };return
        }
        active.values.toList().forEach { it.stop() };active.clear()
    }
    fun angle(progress: Float, incoming: Boolean, degrees: Float): Float {
        if (!progress.isFinite() || !degrees.isFinite()) return 0f
        val p=progress.coerceIn(0f,1f)
        if (p==0f || p==1f) return 0f
        return if (incoming) degrees.coerceIn(0f,45f)*sin(PI*p).toFloat()
        else -degrees.coerceIn(0f,45f)*sin(PI*p).toFloat()
    }
    fun install(api: XposedInterface, loader: ClassLoader): Boolean = try {
        val row=Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",false,loader)
        val heads=Class.forName("com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpAnimator",false,loader)
        // This overload is present in the supplied stock 17.99.02, not the legacy 16 path.
        val fn0=Class.forName("kotlin.jvm.functions.Function0",false,loader)
        heads.getDeclaredMethod("onY",Float::class.javaPrimitiveType,fn0)
        val control=heads.getDeclaredField("control").apply { isAccessible=true }
        val container=Class.forName("com.oplus.systemui.notification.headsup.windowframe.HeadsUpContainerWindow",false,loader)
        val getView=container.getDeclaredMethod("getView")
        var ready=false
        var master=false
        var readAt = -1L
        var powerReadAt = -1L
        var powerAllowed = false
        fun allowed(view: View): Boolean {
            val now=SystemClock.uptimeMillis()
            if (readAt<0 || now-readAt>=500L) {
                readAt=now;master=runCatching { Prefs.masterEnabled(api) }.getOrDefault(false)
            }
            if (powerReadAt<0 || now-powerReadAt>=100L) {
                powerReadAt=now
                val power=view.context.getSystemService(PowerManager::class.java)
                powerAllowed=power?.isInteractive==true && !power.isPowerSaveMode && ValueAnimator.areAnimatorsEnabled()
            }
            return Looper.myLooper()==Looper.getMainLooper() && master && powerAllowed && Prefs.notifTiltEnabled(api)
        }
        fun begin(view: View?, incoming: Boolean, fly: Boolean, source: String, progress:(()->Float?)?=null) {
            if (!ready || view==null || !row.isInstance(view) || !allowed(view)) return
            active.remove(view)?.stop()
            if (active.size>=12) return
            val weak=WeakReference(view)
            val animation=Animation(view,incoming,Prefs.notifTiltDeg(api).toFloat(),
                if (fly) Prefs.notifFlyScale(api)/100f else 0f,
                { weak.get()?.let(::allowed) == true },progress) {
                weak.get()?.let { active.remove(it) }
            }
            active[view]=animation;animation.start()
            RuntimeDiagnostics.mark("notification-tilt-event","COS17 通知行 3D 倾斜已触发：$source")
        }
        var mounted=0
        val base=Class.forName("com.android.systemui.statusbar.notification.row.ActivatableNotificationView",false,loader)
        val fraction=base.getDeclaredMethod("getAppearAnimationFraction")
        val started=row.getDeclaredMethod("onAppearAnimationStarted",Boolean::class.javaPrimitiveType)
        api.hook(started).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            runCatching {
                val view=chain.getThisObject() as? View
                val incoming=chain.getArg(0) as Boolean
                val weak=WeakReference(view)
                begin(view,incoming,false,"native appear start") {
                    weak.get()?.let { (fraction.invoke(it) as Float).takeIf { f->f.isFinite() && f in 0f..1f } }
                        ?.let { if(incoming)it else 1f-it }
                }
            };result
        };mounted++
        val finished=row.getDeclaredMethod("onAppearAnimationFinished",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
        api.hook(finished).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            (chain.getThisObject() as? View)?.let { active.remove(it)?.stop() };result
        };mounted++
        // animateTo is the actual spring start; dismiss() may only enqueue until the incoming
        // spring ends. Starting at dismiss() would consume the effect while waiting.
        val animate=heads.getDeclaredMethod("animateTo",Float::class.javaPrimitiveType,fn0)
        val hold=heads.getDeclaredField("holdY").apply { isAccessible=true }
        api.hook(animate).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val animator=chain.getThisObject()!!
            val view=runCatching { getView.invoke(control.get(animator)) as? View }.getOrNull()
            val from=view?.translationY ?: 0f
            val destination=chain.getArg(0) as Float
            val result=chain.proceed()
            runCatching {
                if(kotlin.math.abs(destination-from)>1f) {
                    val incoming=destination>=hold.getFloat(animator)-1f
                    val weak=WeakReference(view)
                    begin(view,incoming,true,"native heads-up spring") {
                        weak.get()?.let { ((it.translationY-from)/(destination-from)).coerceIn(0f,1f) }
                    }
                }
            };result
        };mounted++
        // Actual callbacks can be inlined into these known OEM animation callers.
        listOf(started,finished,animate).forEach { api.deoptimize(it) }
        heads.declaredMethods.filter { it.name in setOf("show","dismiss","resetDrag") }.forEach { api.deoptimize(it) }
        runCatching {
            val listener=Class.forName(base.name+"\$2",false,loader)
            listener.declaredMethods.filter { it.name=="onAnimationStart" || it.name=="onAnimationEnd" }.forEach { api.deoptimize(it) }
        }
        ready=true
        RuntimeDiagnostics.mark("notification-tilt","COS17 通知行 RenderNode 入口已安装：$mounted；等待通知")
        true
    } catch (error:Throwable) { Lg.w("lg-tilt","COS17 row tilt contract unavailable",error);false }

    /** Saves only owned properties and restores them conditionally; never zero native swipe X/Y.
     * Public to the debug fixture so detach, interruption and host overrides are actually rendered.
     */
    internal class Animation(view: View, private val incoming: Boolean, private val degrees: Float,
        private val shrink: Float, private val allowed: () -> Boolean, private val progress:(()->Float?)?=null,
        private val ended: () -> Unit)
        : Choreographer.FrameCallback, ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val target=WeakReference(view)
        private val clock=Choreographer.getInstance()
        private val born=SystemClock.uptimeMillis()
        private var visibleAt=-1L
        private val visibleRect=Rect()
        private val observer=view.viewTreeObserver
        private var stopped=false
        private val baseline=floatArrayOf(view.rotationX,view.scaleX,view.scaleY,view.cameraDistance,view.pivotX,view.pivotY)
        private val last=baseline.copyOf()
        private var applied=false
        fun start() {
            val view=target.get() ?: return stop()
            view.addOnAttachStateChangeListener(this);observer.addOnPreDrawListener(this)
            clock.postFrameCallback(this);view.invalidate()
        }
        override fun doFrame(frameTimeNanos:Long) {
            val view=target.get()
            if (stopped) return
            try {
                val elapsed=SystemClock.uptimeMillis()-born
                if(view==null || elapsed>=2000 || !allowed())return stop()
                // OEM may enqueue an appearance before attachment/layout. Wait only
                // within this bounded animation, rather than consume it off screen.
                if(!view.isAttachedToWindow || !view.isShown || view.windowVisibility!=View.VISIBLE) {
                    if(visibleAt>=0 || elapsed>=1000)return stop()
                }
                if(progress==null && visibleAt>=0 && SystemClock.uptimeMillis()-visibleAt>=450)return stop()
                view.invalidate();clock.postFrameCallback(this)
            } catch (_:Throwable) { stop() }
        }
        override fun onPreDraw():Boolean {
            val view=target.get() ?: return true
            try {
                if(!stopped && !allowed()) { stop();return true }
                if (!stopped && view.isAttachedToWindow && view.isShown && view.windowVisibility==View.VISIBLE &&
                    view.width>0 && view.height>0 && view.getGlobalVisibleRect(visibleRect)) {
                    if(visibleAt<0)visibleAt=SystemClock.uptimeMillis()
                    val values=floatArrayOf(view.rotationX,view.scaleX,view.scaleY,view.cameraDistance,view.pivotX,view.pivotY)
                    for (i in values.indices) if (!applied || values[i]!=last[i]) baseline[i]=values[i]
                    val p=progress?.invoke()?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?:
                        ((SystemClock.uptimeMillis()-visibleAt)/450f).coerceIn(0f,1f)
                    if(p>=.999f) { stop();return true }
                    val rotation=angle(p,incoming,degrees)
                    val amount=if (incoming) (1f-p)*(1f-p)*(1f-p) else sin(PI*p).toFloat()
                    val scale=1f-shrink.coerceIn(0f,.8f)*amount
                    last[0]=baseline[0]+rotation;last[1]=baseline[1]*scale;last[2]=baseline[2]*scale
                    last[3]=maxOf(view.width.toFloat(),view.height.toFloat(),view.resources.displayMetrics.density*240f)*1.25f
                    last[4]=view.width/2f;last[5]=0f
                    view.pivotX=last[4];view.pivotY=last[5]
                    view.rotationX=last[0];view.scaleX=last[1];view.scaleY=last[2]
                    view.cameraDistance=last[3]
                    applied=true
                    RuntimeDiagnostics.mark("notification-tilt-draw","可见通知行已应用 3D 变换；跟随原生动画阶段")
                }
            } catch (_:Throwable) { stop() }
            return true
        }
        fun stop() {
            if (stopped) return
            stopped=true;clock.removeFrameCallback(this)
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            target.get()?.let { view ->
                // A pre-attachment observer can be merged into the attached tree.
                val current=view.viewTreeObserver
                if(current!==observer && current.isAlive)current.removeOnPreDrawListener(this)
                view.removeOnAttachStateChangeListener(this)
                if (applied) {
                    if (view.rotationX==last[0]) view.rotationX=baseline[0]
                    if (view.scaleX==last[1]) view.scaleX=baseline[1]
                    if (view.scaleY==last[2]) view.scaleY=baseline[2]
                    if (view.cameraDistance==last[3]) view.cameraDistance=baseline[3]
                    if (view.pivotX==last[4]) view.pivotX=baseline[4]
                    if (view.pivotY==last[5]) view.pivotY=baseline[5]
                    view.invalidate()
                }
            };ended()
        }
        override fun onViewDetachedFromWindow(v:View)=stop()
        override fun onViewAttachedToWindow(v:View) {}
    }
}
