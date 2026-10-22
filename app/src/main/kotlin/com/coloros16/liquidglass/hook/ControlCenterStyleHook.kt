package com.coloros16.liquidglass.hook

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap

/** COS17 control surfaces and the separate decoration behind 2x1 tile icons. */
object ControlCenterStyleHook {
    private const val ICON = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView"
    private const val WIDE = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne"
    @Volatile private var lastRead = -1L
    @Volatile private var soft = true
    @Volatile private var hideIcon = true

    private fun refresh(api: XposedInterface) {
        val now = SystemClock.uptimeMillis()
        if (lastRead >= 0 && now - lastRead < 500L) return
        try {
            val p = Prefs.read(api)
            soft = p.getBoolean(Prefs.KEY_CC_SOFT_SURFACE, true)
            hideIcon = Prefs.glassEnabled(api) && p.getBoolean(Prefs.KEY_CC_HIDE_ICON_BACKGROUND, true)
        } catch (_: Throwable) { /* Retain last known setting during temporary framework disconnects. */ }
        lastRead = now
    }

    fun softness(api: XposedInterface?): Float {
        if (api != null) refresh(api)
        if (soft) RuntimeDiagnostics.mark("control_soft_surface", "控制中心已选择柔光薄边缘材质")
        return if (soft) 1f else 0f
    }

    /** Computed at region registration, never by walking View parents on the render thread. */
    fun isControlHost(view: View?, fallback: String): Boolean {
        fun matches(name: String) = name.startsWith("com.oplus.systemui.plugins.qs.") ||
            name.startsWith("com.oplus.systemui.qs.") || name.startsWith("com.android.systemui.qs.") ||
            name.contains("OplusMediaCarousel") || name.contains("QsSeekBar") || name.contains("QSVerticalSeekBar")
        if (matches(fallback)) return true
        var cursor = view
        repeat(10) {
            val current = cursor ?: return false
            if (matches(current.javaClass.name)) return true
            cursor = current.parent as? View
        }
        return false
    }

    fun mount(api: XposedInterface, loader: ClassLoader) {
        try {
            val iconClass = Class.forName(ICON, false, loader)
            val wideClass = Class.forName(WIDE, false, loader)
            val bgField = iconClass.getDeclaredField("iconBgView").apply { isAccessible = true }
            require(View::class.java.isAssignableFrom(bgField.type))
            // Only main-thread view callbacks touch this map. Preserve the host's original visibility.
            val hidden = WeakHashMap<View, Int>()
            fun update(icon: View) {
                var parent = icon.parent as? View
                var wide = false
                for (i in 0 until 8) {
                    val current = parent ?: break
                    if (wideClass.isInstance(current)) { wide = true; break }
                    parent = current.parent as? View
                }
                val background = bgField.get(icon) as? View ?: return
                refresh(api)
                if (wide && hideIcon) {
                    if (!hidden.containsKey(background)) hidden[background] = background.visibility
                    background.visibility = View.INVISIBLE // Keep layout and touch geometry intact.
                    RuntimeDiagnostics.mark("wide_icon_clean", "横向磁贴独立图标背景已隐藏")
                } else {
                    hidden.remove(background)?.let { background.visibility = it }
                }
            }
            val callbacks = iconClass.declaredMethods.filter {
                it.name in setOf("onLayout", "onAttachedToWindow", "updateIconBgDrawable", "onIconDrawableUpdate")
            }
            require(callbacks.isNotEmpty())
            callbacks.forEach { method ->
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    try { (chain.getThisObject() as? View)?.let { update(it) } }
                    catch (t: Throwable) { Lg.w("DreamGlass-CC", "Icon decoration update failed", t) }
                    result
                }
            }
            RuntimeDiagnostics.mark("control_style_hook", "COS17 横向磁贴图标背景回调已注册")
        } catch (t: Throwable) {
            Lg.w("DreamGlass-CC", "Control-center icon contract unavailable; system icons retained", t)
            RuntimeDiagnostics.mark("control_style_unavailable", t.javaClass.simpleName)
        }
    }
}
