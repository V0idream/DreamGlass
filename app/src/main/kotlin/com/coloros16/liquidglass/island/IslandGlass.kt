package com.coloros16.liquidglass.island

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.hook.BlurDrawHook

internal object IslandGlass {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var roots = emptyList<View>()
    @Volatile private var glass = false
    private var posted = false
    private var lastRendered: Boolean? = null
    private val pulse = object : Runnable {
        override fun run() {
            posted = false
            if (!demand()) return
            BlurDrawHook.refreshIslandBackdrop(false)
            schedule()
        }
    }

    fun windows() = roots
    fun demand() = glass && roots.any { it.isAttachedToWindow && it.alpha > 0f && it.isShown }

    fun enabled(value: Boolean) {
        if (glass == value) return
        glass = value
        if (value) refresh() else cancel()
    }

    fun setWindows(value: List<View>) {
        if (roots == value) { schedule(); return }
        roots = value.toList()
        lastRendered = null
        if (glass && value.isNotEmpty()) refresh() else cancel()
    }

    fun removeWindow(view: View) = setWindows(roots.filterNot { it === view })

    fun draw(canvas: Canvas, view: View, radius: Float): Boolean {
        if (!glass) return false
        val rendered = runCatching { BlurDrawHook.drawIslandSurface(canvas, view, radius) }.getOrDefault(false)
        if (view.parent === roots.firstOrNull() && lastRendered != rendered) {
            lastRendered = rendered
            RuntimeDiagnostics.update(IslandPrefs.MATERIAL_STATUS, if (rendered) "液态玻璃正在绘制"
                else "暂未取得可用背景，使用普通底色")
        }
        return rendered
    }

    fun refresh() {
        if (!glass || roots.isEmpty()) return
        BlurDrawHook.refreshIslandBackdrop(true)
        schedule()
    }

    private fun schedule() {
        if (posted || !demand() || !BlurDrawHook.mayRefreshIslandBackdrop()) return
        posted = true
        main.postDelayed(pulse, 200L)
    }

    private fun cancel() {
        if (posted) main.removeCallbacks(pulse)
        posted = false
    }
}
