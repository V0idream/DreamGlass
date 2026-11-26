package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Only the system shade's behind scrim is replaced. Foreground and keyguard scrims stay native. */
internal class ShadeWallpaperHook {
    private val progress = ShadeWallpaperProgress()
    private val behindViews = WeakHashMap<View, Boolean>()
    private var scrim: WeakReference<View>? = null
    @Volatile private var drawnRoot: WeakReference<View>? = null
    @Volatile private var enabled = false
    private var installed = false
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    val hasBackdrop: Boolean get() = enabled && drawnRoot?.get() != null

    fun appliesTo(host: View): Boolean =
        enabled && drawnRoot?.get() === host.rootView

    fun invalidate() {
        scrim?.get()?.postInvalidateOnAnimation()
    }

    fun reset() {
        progress.reset()
        drawnRoot = null
        invalidate()
    }

    fun install(
        api: XposedInterface,
        loader: ClassLoader,
        isBehind: (View) -> Boolean,
        unlocked: () -> Boolean,
        draw: (Canvas, View, Int) -> Boolean,
        sourceChanged: () -> Unit,
    ) {
        if (installed) return
        installed = true
        runCatching {
            val store = Prefs.read(api)
            fun readSetting(): Boolean = store.getBoolean(Prefs.KEY_MASTER, false) &&
                store.getBoolean(Prefs.KEY_SHADE_LOCK_WALLPAPER, Prefs.DEFAULT_SHADE_LOCK_WALLPAPER)

            val manager = Class.forName("com.android.systemui.shade.ShadeExpansionStateManager", false, loader)
            val panelType = "com.android.systemui.shade.PanelType"
            val float = Float::class.javaPrimitiveType
            val bool = Boolean::class.javaPrimitiveType
            val methods = manager.declaredMethods.filter { method ->
                if (method.name != "onPanelExpansionChanged") return@filter false
                val p = method.parameterTypes
                p.size in 3..5 && p[0] == float && p[1] == bool && p[2] == bool &&
                    (p.size == 3 || p.size == 4 && p[3] == float ||
                        p.size == 5 && p[3] == String::class.java && p[4].name == panelType)
            }
            check(methods.isNotEmpty()) { "Shade expansion callback unavailable" }
            val onDraw = Class.forName("com.android.systemui.scrim.ScrimView", false, loader)
                .getDeclaredMethod("onDraw", Canvas::class.java)
            enabled = readSetting()
            val ui = Handler(Looper.getMainLooper())
            listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == Prefs.KEY_SHADE_LOCK_WALLPAPER || key == Prefs.KEY_MASTER) ui.post {
                    val requested = runCatching { readSetting() }.getOrDefault(false)
                    if (requested != enabled) {
                        enabled = requested
                        drawnRoot = null
                        invalidate()
                        if (progress.alpha > 0) sourceChanged()
                    }
                }
            }.also(store::registerOnSharedPreferenceChangeListener)
            for (method in methods) {
                val split = method.parameterCount == 5
                api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val fraction = chain.getArg(0) as Float
                    val panel = if (split) (chain.getArg(4) as? Enum<*>)?.name else null
                    if (progress.update(panel, fraction) && enabled) {
                        if (progress.alpha == 0 && drawnRoot != null) {
                            drawnRoot = null
                            sourceChanged()
                        }
                        invalidate()
                    }
                    chain.proceed()
                }
            }
            api.hook(onDraw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.getThisObject() as? View ?: return@intercept chain.proceed()
                if (!behindViews.getOrPut(view) { isBehind(view) } || view.display?.displayId != Display.DEFAULT_DISPLAY) {
                    return@intercept chain.proceed()
                }
                if (scrim?.get() !== view) scrim = WeakReference(view)
                val alpha = progress.alpha
                val canvas = chain.getArg(0) as? Canvas
                if (enabled && alpha > 0 && unlocked() && canvas != null && draw(canvas, view, alpha)) {
                    val root = view.rootView
                    if (drawnRoot?.get() !== root) {
                        drawnRoot = WeakReference(root)
                        sourceChanged()
                        RuntimeDiagnostics.mark("shade_lock_wallpaper", "下拉面板已绘制锁屏壁纸")
                    }
                    null
                } else {
                    if (drawnRoot != null) {
                        drawnRoot = null
                        sourceChanged()
                    }
                    chain.proceed()
                }
            }
        }.onFailure { Lg.w("DreamGlass-ShadeWallpaper", "Shade wallpaper hooks unavailable", it) }
    }
}

/** ColorOS split NC/QS sends updates for each panel independently. */
internal class ShadeWallpaperProgress {
    private var notification = 0f
    private var quickSettings = 0f
    private var shade = 0f
    var alpha = 0
        private set

    fun update(panel: String?, fraction: Float): Boolean {
        if (!fraction.isFinite()) return false
        val value = fraction.coerceIn(0f, 1f)
        when (panel) {
            "NOTIFICATION_PANEL" -> notification = value
            "QS_PANEL" -> quickSettings = value
            null, "SHADE_PANEL" -> shade = value
            else -> return false
        }
        val p = (maxOf(notification, quickSettings, shade) / 0.85f).coerceAtMost(1f)
        val next = (255f * p * p * (3f - 2f * p)).toInt()
        if (alpha == next) return false
        alpha = next
        return true
    }

    fun reset() {
        notification = 0f
        quickSettings = 0f
        shade = 0f
        alpha = 0
    }
}
