/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.animation.ValueAnimator
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
        return if (incoming) degrees.coerceIn(0f,45f)*(1-p)*(1-p)*(1-p)
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
        fun begin(view: View?, incoming: Boolean, fly: Boolean, source: String) {
            if (!ready || view==null || !row.isInstance(view) || !allowed(view)) return
            active.remove(view)?.stop()
            if (active.size>=12) return
            val weak=WeakReference(view)
            val animation=Animation(view,incoming,Prefs.notifTiltDeg(api).toFloat(),
                if (fly) Prefs.notifFlyScale(api)/100f else 0f,
                { weak.get()?.let(::allowed) == true }) {
                weak.get()?.let { active.remove(it) }
            }
            active[view]=animation;animation.start()
            RuntimeDiagnostics.mark("notification-tilt-event","COS17 通知行 3D 倾斜已触发：$source")
        }
        var mounted=0
        for (method in row.declaredMethods.filter { it.name=="performAddAnimation" || it.name=="performRemoveAnimation" }) {
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                runCatching { begin(chain.getThisObject() as? View,method.name=="performAddAnimation",false,method.name) }
                result
            };mounted++
        }
        val show=heads.getDeclaredMethod("show",View::class.java)
        api.hook(show).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            runCatching { begin(chain.getArg(0) as? View,true,true,"heads-up show") };result
        };mounted++
        // animateTo is the actual spring start; dismiss() may only enqueue until the incoming
        // spring ends. Starting at dismiss() would consume the effect while waiting.
        val animate=heads.getDeclaredMethod("animateTo",Float::class.javaPrimitiveType,fn0)
        val hold=heads.getDeclaredField("holdY").apply { isAccessible=true }
        api.hook(animate).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            runCatching {
                val animator=chain.getThisObject()!!
                val destination=chain.getArg(0) as Float
                if (destination < hold.getFloat(animator)-1f)
                    begin(getView.invoke(control.get(animator)) as? View,false,true,"heads-up remove spring")
            };result
        };mounted++
        ready=true
        RuntimeDiagnostics.mark("notification-tilt","COS17 通知行 RenderNode 入口已安装：$mounted；等待通知")
        true
    } catch (error:Throwable) { Lg.w("lg-tilt","COS17 row tilt contract unavailable",error);false }

    /** Saves only owned properties and restores them conditionally; never zero native swipe X/Y.
     * Public to the debug fixture so detach, interruption and host overrides are actually rendered.
     */
    internal class Animation(view: View, private val incoming: Boolean, private val degrees: Float,
        private val shrink: Float, private val allowed: () -> Boolean, private val ended: () -> Unit)
        : Choreographer.FrameCallback, ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val target=WeakReference(view)
        private val clock=Choreographer.getInstance()
        private val born=SystemClock.uptimeMillis()
        private val observer=view.viewTreeObserver
        private var stopped=false
        private val baseline=floatArrayOf(view.rotationX,view.scaleX,view.scaleY,view.cameraDistance)
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
                if (view==null || !view.isAttachedToWindow || !view.isShown || view.windowVisibility!=View.VISIBLE ||
                    SystemClock.uptimeMillis()-born>=450 || !allowed()) return stop()
                view.invalidate();clock.postFrameCallback(this)
            } catch (_:Throwable) { stop() }
        }
        override fun onPreDraw():Boolean {
            val view=target.get() ?: return true
            try {
                if (!stopped && allowed()) {
                    val values=floatArrayOf(view.rotationX,view.scaleX,view.scaleY,view.cameraDistance)
                    for (i in values.indices) if (!applied || values[i]!=last[i]) baseline[i]=values[i]
                    val p=((SystemClock.uptimeMillis()-born)/450f).coerceIn(0f,1f)
                    val rotation=angle(p,incoming,degrees)
                    val amount=if (incoming) (1f-p)*(1f-p)*(1f-p) else sin(PI*p).toFloat()
                    val scale=1f-shrink.coerceIn(0f,.8f)*amount
                    last[0]=baseline[0]+rotation;last[1]=baseline[1]*scale;last[2]=baseline[2]*scale
                    last[3]=maxOf(baseline[3],view.resources.displayMetrics.density*1800f)
                    view.rotationX=last[0];view.scaleX=last[1];view.scaleY=last[2]
                    view.cameraDistance=last[3]
                    applied=true
                }
            } catch (_:Throwable) { stop() }
            return true
        }
        fun stop() {
            if (stopped) return
            stopped=true;clock.removeFrameCallback(this)
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            target.get()?.let { view ->
                view.removeOnAttachStateChangeListener(this)
                if (applied) {
                    if (view.rotationX==last[0]) view.rotationX=baseline[0]
                    if (view.scaleX==last[1]) view.scaleX=baseline[1]
                    if (view.scaleY==last[2]) view.scaleY=baseline[2]
                    if (view.cameraDistance==last[3]) view.cameraDistance=baseline[3]
                    view.invalidate()
                }
            };ended()
        }
        override fun onViewDetachedFromWindow(v:View)=stop()
        override fun onViewAttachedToWindow(v:View) {}
    }
}
