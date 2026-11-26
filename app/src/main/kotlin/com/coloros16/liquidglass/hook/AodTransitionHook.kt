package com.coloros16.liquidglass.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.coloros16.liquidglass.config.AodComposition
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference

internal enum class AodTransitionPhase {
    AOD, LOCK_SCREEN, UNLOCKED, OTHER;

    fun destination(next: AodTransitionPhase): Float? = when {
        this == AOD && next == LOCK_SCREEN -> 1f
        (this == LOCK_SCREEN || this == UNLOCKED) && next == AOD -> 0f
        else -> null
    }

    companion object {
        fun fromNative(name: String?) = when (name) {
            "NORMAL_AOD", "WORKSHOP_AOD", "PANORAMIC_AOD" -> AOD
            "KEYGUARD" -> LOCK_SCREEN
            "UNLOCK" -> UNLOCKED
            else -> OTHER
        }
    }
}

/** Lives in the same SystemUI window as the native AOD. The ROM still owns doze and screen wake. */
internal object AodTransitionHook {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var root: WeakReference<FrameLayout>? = null
    private var layer: LockTransitionLayer? = null
    private var animator: ValueAnimator? = null
    private var animationTarget: Float? = null
    private var preparedUntil = 0L
    private var phase = AodTransitionPhase.OTHER
    private var style = LockTransitionStyle.SYSTEM
    private var composition = AodComposition()
    private var battery: AodBattery? = null
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var installed = false

    fun install(api: XposedInterface, loader: ClassLoader) {
        if (installed) return
        runCatching {
            val type = Class.forName("com.oplus.systemui.aod.display.SmoothTransitionController", false, loader)
            val state = Class.forName(type.name + "\$SmoothTransitionUiState", false, loader)
            val change = type.getDeclaredMethod("handleSmoothTransitionDisplayWhileUiStateChanged", state, state)
            api.hook(change).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val previous = AodTransitionPhase.fromNative((chain.getArg(0) as? Enum<*>)?.name)
                val next = AodTransitionPhase.fromNative((chain.getArg(1) as? Enum<*>)?.name)
                val result = chain.proceed()
                onMain { changePhase(previous, next) }
                result
            }
            runCatching {
                val early = type.getDeclaredMethod("prepareSmtWhileGoingToSleepEarly")
                val powerOff = type.getDeclaredMethod("getPowerOffUiState")
                api.hook(early).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val owner = chain.getThisObject()
                    onMain {
                        if (style != LockTransitionStyle.SYSTEM && owner != null) {
                            val next = runCatching { powerOff.invoke(owner) as? Enum<*> }.getOrNull()?.name
                            if (AodTransitionPhase.fromNative(next) == AodTransitionPhase.AOD) prepareSleep()
                        }
                    }
                    result
                }
            }.onFailure { Lg.w("DreamGlass-AodTransition", "Early native sleep callback unavailable", it) }
            val prefs = Prefs.read(api)
            fun updateStyle() {
                val next = if (prefs.getBoolean(Prefs.KEY_MASTER, false))
                    LockTransitionStyle.fromKey(prefs.getString(Prefs.KEY_AOD_TRANSITION_STYLE, null))
                else LockTransitionStyle.SYSTEM
                composition = AodComposition.decode(prefs.getString(AodComposition.KEY_PREFIX + next.name, null))
                if (next != style) {
                    style = next
                    preparedUntil = 0L
                    stopAnimation()
                    if (style == LockTransitionStyle.SYSTEM) removeLayer()
                }
                refreshWallpaper()
            }
            listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == Prefs.KEY_MASTER || key == Prefs.KEY_AOD_TRANSITION_STYLE ||
                    key.startsWith(AodComposition.KEY_PREFIX)) {
                    onMain { updateStyle() }
                }
            }.also(prefs::registerOnSharedPreferenceChangeListener)
            installed = true
            onMain { updateStyle() }
        }.onFailure { Lg.w("DreamGlass-AodTransition", "Native AOD transition hook unavailable", it) }
    }

    fun attach(view: View) {
        if (!installed || view !is FrameLayout || view.display?.displayId != Display.DEFAULT_DISPLAY) return
        // Only mount on verified ColorOS shades containing the stock AOD family.
        fun contains(name: String, type: String): Boolean {
            val id = view.resources.getIdentifier(name, "id", view.context.packageName)
            return id != 0 && view.findViewById<View>(id)?.javaClass?.name == "com.oplus.systemui.aod.aodclock.off.$type"
        }
        if (!contains("aod_off_layout", "AodRootLayout") &&
            !contains("workshop_aod_black_container", "AodBlackLayout")) return
        onMain {
            if (root?.get() === view) return@onMain
            stopAnimation()
            removeLayer()
            root = WeakReference(view)
            RuntimeDiagnostics.mark("aod_transition_host", "已识别系统息屏容器")
            refreshWallpaper()
        }
    }

    fun detach(view: View) {
        onMain {
            if (root?.get() !== view) return@onMain
            stopAnimation()
            removeLayer()
            root = null
        }
    }

    fun refreshWallpaper() {
        if (!installed) return
        onMain {
            val host = root?.get() ?: return@onMain
            if (style == LockTransitionStyle.SYSTEM) return@onMain
            val image = LockWallpaperBackdrop.get(host.context)
            if (image == null) {
                if (animator == null) layer?.visibility = View.INVISIBLE
                return@onMain
            }
            val view = layer ?: mount(host) ?: return@onMain
            view.style = style
            view.composition = composition
            view.battery = battery
            view.wallpaper = image
            if (animator != null) return@onMain
            if (phase == AodTransitionPhase.AOD || hasPreparedSleep() && view.progress == 0f) {
                view.progress = 0f
                view.updateAmbientClock()
                view.visibility = View.VISIBLE
                RuntimeDiagnostics.mark("aod_transition_wallpaper", "已挂载相片或信封息屏画布")
            } else if (!hasPreparedSleep() || !animate(0f)) {
                view.visibility = View.INVISIBLE
            }
        }
    }

    fun batteryChanged(intent: Intent) {
        if (!installed) return
        onMain {
            battery = AodBattery.read(intent)
            layer?.battery = battery
        }
    }

    fun tickClock() {
        if (!installed || phase != AodTransitionPhase.AOD || animator != null) return
        layer?.takeIf { it.visibility == View.VISIBLE && it.display?.state != Display.STATE_OFF }
            ?.updateAmbientClock()
    }

    private fun mount(host: FrameLayout): LockTransitionLayer? = runCatching {
        LockTransitionLayer(host.context).also {
            it.visibility = View.INVISIBLE
            it.progress = 1f
            host.addView(it, FrameLayout.LayoutParams(-1, -1))
            layer = it
        }
    }.getOrNull()

    private fun changePhase(previous: AodTransitionPhase, next: AodTransitionPhase) {
        if (phase == next) return
        val prepared = hasPreparedSleep()
        val origin = if (previous == AodTransitionPhase.OTHER) phase else previous
        phase = next
        if (prepared && next == AodTransitionPhase.LOCK_SCREEN && origin == AodTransitionPhase.UNLOCKED) return
        preparedUntil = 0L
        if (prepared && next == AodTransitionPhase.AOD && (animationTarget == 0f || layer?.let {
                it.visibility == View.VISIBLE && it.progress == 0f
            } == true)) return
        val target = origin.destination(next)
        if (target != null && style != LockTransitionStyle.SYSTEM && ValueAnimator.areAnimatorsEnabled() &&
            animate(target)) return
        stopAnimation()
        refreshWallpaper()
    }

    private fun hasPreparedSleep() = preparedUntil > SystemClock.uptimeMillis()

    private fun prepareSleep() {
        if (phase == AodTransitionPhase.AOD || hasPreparedSleep() || !ValueAnimator.areAnimatorsEnabled()) return
        val host = root?.get() ?: return
        if (host.display?.state != Display.STATE_ON) return
        val expires = SystemClock.uptimeMillis() + 1_450L
        preparedUntil = expires
        animate(0f)
        main.postAtTime({
            if (preparedUntil == expires) {
                preparedUntil = 0L
                if (phase != AodTransitionPhase.AOD && animationTarget != 1f) {
                    stopAnimation()
                }
            }
        }, expires)
    }

    private fun animate(to: Float): Boolean {
        val host = root?.get() ?: return false
        val image = LockWallpaperBackdrop.get(host.context) ?: return false
        val view = layer ?: mount(host) ?: return false
        val from = if (animator != null) view.progress else 1f - to
        stopAnimation()
        if (from == to) return false
        view.style = style
        view.composition = composition
        view.battery = battery
        view.wallpaper = image
        view.progress = from
        view.updateAmbientClock()
        view.visibility = View.VISIBLE
        val motion = ValueAnimator.ofFloat(from, to)
        motion.duration = style.durationBetween(from, to)
        motion.interpolator = LinearInterpolator()
        motion.addUpdateListener { view.progress = it.animatedValue as Float }
        motion.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== animation) return
                animator = null
                animationTarget = null
                refreshWallpaper()
            }
        })
        animator = motion
        animationTarget = to
        motion.start()
        RuntimeDiagnostics.mark("aod_transition_animation", "已开始息屏与锁屏壁纸过渡")
        return true
    }

    private fun stopAnimation() {
        val old = animator
        animator = null
        animationTarget = null
        old?.cancel()
        layer?.visibility = View.INVISIBLE
    }

    private fun removeLayer() {
        val backdrop = layer
        layer = null
        (backdrop?.parent as? FrameLayout)?.removeView(backdrop)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
