/* DreamGlass, 2026-10-25. Draw-only QS icon recoloring; GPL-3.0. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/** No drawable/tint mutation: recolor only the ImageView.onDraw foreground on a bounded layer.
 * A cached black/white paint preserves the already-rendered alpha, including disabled opacity. */
class QsIconContrastRenderer(
    private val enabled: () -> Boolean,
    private val panelActive: () -> Boolean,
    private val frameToken: () -> Any?,
    private val resolve: (ImageView, Int) -> Int?,
) {
    private val tracked = WeakHashMap<ImageView, Boolean>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var scheduled = false
    private var lastEnabled = false
    private var lastFrame: Any? = null
    private val black = Paint().apply { colorFilter = BlendModeColorFilter(Color.BLACK, BlendMode.SRC_IN) }
    private val white = Paint().apply { colorFilter = BlendModeColorFilter(Color.WHITE, BlendMode.SRC_IN) }

    fun eligible(view: ImageView): Boolean {
        if (view.width <= 0 || view.height <= 0 || !view.isEnabled) return false
        val maxSide = 160 * view.resources.displayMetrics.density
        if (view.width > maxSide || view.height > maxSide) return false
        val id = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { return false }
        if (!QsIconContrastPolicy.iconId(id)) return false
        var cursor: View? = view; var qs = false
        repeat(16) {
            val current = cursor ?: return qs
            val name = current.javaClass.name
            if (QsIconContrastPolicy.excluded(name)) return false
            if (QsIconContrastPolicy.qsHost(name)) qs = true
            cursor = current.parent as? View
        }
        return qs
    }

    private fun tint(view: ImageView): Int? {
        val drawable = view.drawable ?: return null
        if (drawable is android.graphics.drawable.Animatable) return null
        // PorterDuffColorFilter's color/mode getters are hidden APIs. Record public
        // integer-tint setter arguments instead of reflecting private framework fields.
        val explicit = view.colorFilter
        if (explicit != null) return filterColor(explicit)
        if (view.imageTintList == null) return drawable.colorFilter?.let(::filterColor)
        val list = view.imageTintList ?: return null // Unknown colors/logos stay native; no pixel reads here.
        val color = list.getColorForState(view.drawableState, list.defaultColor)
        val mode = view.imageTintBlendMode
        return color.takeIf { mode == null || mode == BlendMode.SRC_IN || (mode == BlendMode.SRC_ATOP && color ushr 24 == 255) }
    }

    private val knownNeutralFilters = WeakHashMap<ColorFilter, Int>()
    fun rememberFilter(filter: ColorFilter?, color: Int, mode: PorterDuff.Mode?) {
        if (filter == null || !QsIconContrastPolicy.neutral(color)) return
        if (mode != PorterDuff.Mode.SRC_IN && !(mode == PorterDuff.Mode.SRC_ATOP && color ushr 24 == 255)) return
        synchronized(knownNeutralFilters) {
            if (knownNeutralFilters.size >= 512) knownNeutralFilters.clear()
            knownNeutralFilters[filter] = color
        }
    }

    private fun filterColor(filter: ColorFilter): Int? = when (filter) {
        is BlendModeColorFilter -> filter.color.takeIf { color ->
            filter.mode == BlendMode.SRC_IN || (filter.mode == BlendMode.SRC_ATOP && color ushr 24 == 255)
        }
        is PorterDuffColorFilter -> synchronized(knownNeutralFilters) { knownNeutralFilters[filter] }
        else -> null
    }

    /** Also used by the debug-only Android rendering regression probe. */
    fun <T> draw(view: ImageView, canvas: Canvas, originalDraw: () -> T): T {
        // Vendor snapshot/render helpers may draw a View on a worker. Leave those paths native;
        // ownership, weak tracking and view coordinates belong to the UI thread.
        if (Looper.myLooper() !== Looper.getMainLooper()) return originalDraw()
        val desired = try {
            if (!eligible(view)) null else {
                if (tracked.size >= 512 && !tracked.containsKey(view)) tracked.clear()
                tracked[view] = true
                watch()
                val original = tint(view)
                if (!enabled() || original == null || !QsIconContrastPolicy.neutral(original)) null else {
                    resolve(view, original)?.takeIf { (it and 0xffffff) != (original and 0xffffff) }
                }
            }
        } catch (_: Throwable) { null }
        if (desired == null) return originalDraw()
        // Remember an actual recolored recording immediately, before the first polling tick.
        // A rapid toggle-off must invalidate it even if that tick has not run yet.
        lastEnabled = true
        // Opaque replacement tint: the original rendered alpha is already in this layer.
        val saved = canvas.saveLayer(0f, 0f, view.width.toFloat(), view.height.toFloat(),
            if ((desired and 0xffffff) == 0) black else white)
        return try { originalDraw() } finally { canvas.restoreToCount(saved) }
    }

    fun invalidateTracked() {
        if (Looper.myLooper() !== Looper.getMainLooper()) { handler.post { invalidateTracked() }; return }
        if (!enabled() && !lastEnabled) return
        for (view in tracked.keys.toList()) if (view.isAttachedToWindow && view.isShown) view.invalidate()
    }

    private fun watch() {
        if (scheduled || !panelActive() || (!enabled() && !lastEnabled)) return
        scheduled = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                scheduled = false
                RuntimeWorkPolicy.guarded({}) {
                    if (!panelActive() || tracked.keys.none { it.isAttachedToWindow && it.isShown }) return@guarded
                    val active = enabled(); val frame = frameToken()
                    if (active != lastEnabled || (active && frame !== lastFrame)) invalidateTracked()
                    lastEnabled = active; lastFrame = frame
                    if (active) { scheduled = true; handler.postDelayed(this, 500L) }
                }
            }
        }, 500L)
    }
}


/** Framework hooking is kept separate so the renderer can run in a normal Android debug app. */
object QsIconContrastHook {
    fun install(api: XposedInterface, renderer: QsIconContrastRenderer) {
        try {
            api.hook(ImageView::class.java.getDeclaredMethod("onDraw", Canvas::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val view = chain.getThisObject() as? ImageView ?: return@intercept chain.proceed()
                    val canvas = chain.getArg(0) as? Canvas ?: return@intercept chain.proceed()
                    renderer.draw(view, canvas) { chain.proceed() }
                }
            Lg.i("DreamGlass-icons", "QS neutral icon draw hook mounted; preference controls activation")
        } catch (t: Throwable) {
            Lg.w("DreamGlass-icons", "Icon contrast unavailable; keep native icons", t)
        }
        for (type in listOf(ImageView::class.java, android.graphics.drawable.Drawable::class.java)) {
            try {
                val method = type.getDeclaredMethod("setColorFilter", Int::class.javaPrimitiveType, PorterDuff.Mode::class.java)
                api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    val mode = chain.getArg(1) as? PorterDuff.Mode
                    val result = chain.proceed()
                    try {
                        val filter = when (val host = chain.getThisObject()) {
                            is ImageView -> host.colorFilter
                            is android.graphics.drawable.Drawable -> host.colorFilter
                            else -> null
                        }
                        if (color != null) renderer.rememberFilter(filter, color, mode)
                    } catch (_: Throwable) { }
                    result
                }
            } catch (t: Throwable) {
                Lg.w("DreamGlass-icons", "Optional integer tint observer unavailable: ${type.simpleName}", t)
            }
        }
    }
}
