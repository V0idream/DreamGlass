package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** Only COS17's QS slider fill and the separate background inside a 2x1 tile. */
object QsSurfaceHook {
    private const val ICON = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView"
    private const val SLIDER = "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar"
    private val iconBackgrounds = Collections.synchronizedMap(WeakHashMap<Drawable, WeakReference<View>>())

    fun install(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean) {
        installSlider(api, loader, enabled)
        installIconBackground(api, loader, enabled)
    }

    private fun installIconBackground(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean) {
        try {
            val icon = Class.forName(ICON, false, loader)
            val background = icon.getDeclaredField("iconBgView").apply { isAccessible = true }
            for (method in arrayOf(
                icon.getDeclaredMethod("onAttachedToWindow"),
                icon.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                icon.getDeclaredMethod("updateIconBgDrawable"),
            )) {
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val owner = chain.getThisObject() as? View
                        val drawable = (background.get(owner) as? View)?.background
                        if (owner != null && drawable != null) iconBackgrounds[drawable] = WeakReference(owner)
                    } catch (_: Throwable) { }
                    result
                }
            }
            val transition = Class.forName("com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable", false, loader)
            api.hook(transition.getDeclaredMethod("draw", Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val owner = iconBackgrounds[chain.getThisObject()]?.get()
                    if (owner != null && hasWideTileParent(owner) && enabled()) {
                        RuntimeDiagnostics.mark("qs_icon_background", "横向按钮独立图标底层已跳过；保留图标和状态色")
                        null
                    } else chain.proceed()
                }
            RuntimeDiagnostics.mark("qs_icon_hook", "已挂载横向按钮图标底层；待实际绘制")
        } catch (t: Throwable) {
            Lg.w("DreamGlass-QS", "Icon background compatibility unavailable; keep native drawing", t)
        }
    }

    private fun hasWideTileParent(icon: View): Boolean {
        var cursor = icon.parent as? View
        repeat(8) {
            val view = cursor ?: return false
            if (QsSurfacePolicy.isWideTile(view.javaClass.name)) return true
            cursor = view.parent as? View
        }
        return false
    }

    private class ClipState(val path: Path = Path(), val rect: RectF = RectF(), val radii: FloatArray = FloatArray(8)) {
        var adapter: Any? = null
    }

    private fun installSlider(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean) {
        try {
            val slider = Class.forName(SLIDER, false, loader)
            val bounds = QsSurfacePolicy.field(slider, "mClipProgressRect")
            val radius = QsSurfacePolicy.field(slider, "mCurProgressRadius")
            val weight = QsSurfacePolicy.field(slider, "mBackgroundRoundCornerWeight")
            val states = Collections.synchronizedMap(WeakHashMap<View, ClipState>())
            // Use the ROM's own smooth-corner constructor, rather than guessing a fixed radius.
            val smooth = try {
                val type = Class.forName("com.oplus.posteffect.util.OplusPathAdapterCompatUtils", false, loader)
                Triple(type.getField("INSTANCE").get(null), type.getDeclaredMethod("create", Path::class.java),
                    type.getDeclaredMethod("addSmoothRoundRect", Any::class.java, RectF::class.java,
                        FloatArray::class.java, Path.Direction::class.java, Float::class.javaPrimitiveType!!))
            } catch (_: Throwable) { null }
            api.hook(slider.getDeclaredMethod("drawActiveMixColorTrack", Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val view = chain.getThisObject() as? View
                    val canvas = chain.getArg(0) as? Canvas
                    val clip = if (view != null && canvas != null && enabled()) try {
                        val rect = bounds.get(view) as? Rect
                        val r = radius.getFloat(view)
                        if (rect == null || rect.isEmpty || !r.isFinite() || r <= 0f) null else {
                            val state = synchronized(states) { states.getOrPut(view) { ClipState() } }
                            state.rect.set(rect)
                            state.path.reset()
                            val w = weight.getFloat(view)
                            val rounded = if (smooth != null && w.isFinite() && w != 0f) try {
                                state.adapter = state.adapter ?: smooth.second.invoke(smooth.first, state.path)
                                state.radii.fill(r)
                                smooth.third.invoke(smooth.first, state.adapter, state.rect, state.radii, Path.Direction.CW, w) == true
                            } catch (_: Throwable) { false } else false
                            if (!rounded || state.path.isEmpty) {
                                state.path.reset()
                                state.path.addRoundRect(state.rect, r, r, Path.Direction.CW)
                            }
                            state.path
                        }
                    } catch (t: Throwable) {
                        Lg.w("DreamGlass-QS", "Slider contour unavailable; keep native drawing", t)
                        null
                    } else null
                    if (canvas == null || clip == null) chain.proceed() else {
                        val saved = canvas.save()
                        try {
                            canvas.clipPath(clip)
                            val result = chain.proceed() // Native progress clipping/animation/icon stays intact.
                            RuntimeDiagnostics.mark("qs_slider_clip", "进度填充已受滑杆当前外轮廓裁剪；外观待核验")
                            result
                        } finally {
                            canvas.restoreToCount(saved)
                        }
                    }
                }
            RuntimeDiagnostics.mark("qs_slider_hook", "已挂载控制中心进度外轮廓裁剪；待实际绘制")
        } catch (t: Throwable) {
            Lg.w("DreamGlass-QS", "Slider contour compatibility unavailable; keep native drawing", t)
        }
    }
}

/** Exact ownership rules: never remove a round 1x1 tile or a status-bar icon's background. */
object QsSurfacePolicy {
    fun isWideTile(name: String): Boolean = name ==
        "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne"

    fun polishMaterial(host: String?): Boolean {
        if (host == null) return false
        // RegionEntry records simple names; ancestor checks use fully qualified class names.
        val simple = host.substringAfterLast(':').substringAfterLast('.')
        return host.startsWith("com.oplus.systemui.qs.") || host.contains(".plugins.qs.") ||
            host.contains(".systemui.qs.") || simple == "NotificationBackgroundView" ||
            host.startsWith("com.oplus.systemui.notification.") ||
            simple.startsWith("OplusQS") || simple.startsWith("OplusQs")
    }

    fun buttonMaterial(host: String?): Boolean {
        if (host == null) return false
        val simple = host.substringAfterLast(':').substringAfterLast('.')
        val qs = simple.startsWith("OplusQS") || simple.startsWith("OplusQs") || host.contains(".plugins.qs.")
        return qs && (simple.contains("Tile") || simple.contains("IconView") || simple.contains("Button"))
    }

    fun excludesButtonOpacity(host: String?): Boolean {
        val name = host?.lowercase() ?: return false
        return name.contains("seekbar") || name.contains("slider") || name.contains("media") ||
            name.contains(".volume.") || name.contains("volumedialog")
    }

    fun field(type: Class<*>, name: String): Field {
        var cursor: Class<*>? = type
        while (cursor != null) {
            try { return cursor.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { cursor = cursor.superclass }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }
}
