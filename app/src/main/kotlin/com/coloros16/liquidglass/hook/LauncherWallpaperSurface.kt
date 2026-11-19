package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.coloros16.liquidglass.config.SafeAreas
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.glass.SharedGlassParams
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Draws a desktop backdrop only when a usable wallpaper texture is bound. */
internal class LauncherWallpaperSurface {
    private val shader = LiquidGlassShader.create(safeDesktop = true)
    private val safeAreas = SafeAreas.Binding()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@LauncherWallpaperSurface.shader }
    private val position = IntArray(2)
    private val screen = RectF()
    private val crop = RectF()
    private var bitmap: Bitmap? = null
    private var parameters: LiquidGlassShader.Params? = null

    fun draw(view: View, canvas: Canvas, bounds: RectF, radius: Float, config: SharedGlassParams.Snapshot,
        onWallpaperReady: () -> Unit): Boolean {
        if (!canvas.isHardwareAccelerated || !view.isAttachedToWindow || !view.isShown ||
            bounds.isEmpty || !radius.isFinite() || radius < 0f) return false
        view.getLocationOnScreen(position)
        screen.set(bounds)
        screen.offset(position[0].toFloat(), position[1].toFloat())
        val frame = IconGlassWallpaper.resolve(view.context, screen, onWallpaperReady) ?: return false
        val source = frame.sharpBitmap.takeUnless { it.isRecycled } ?: return false
        val id = System.identityHashCode(view)
        GlassRegistry.bind(id, view, view.javaClass.name)
        val params = NativeSurfaceOptics.anchor(SharedGlassParams.resolve(config.values, config.preset,
            GlassRegistry.overridesFor(id), bounds.width(), bounds.height(), GlassRegistry.cornerOverrideFor(id) ?: radius,
            false, config.compactOptics, config.grayBaseLuma)).copy(sourcePreblurred = false)
        safeAreas.set(shader, SafeAreas.Scene.DESKTOP, frame.safeMapping)
        val sourceRect = frame.sourceRect
        if (bitmap !== source || parameters != params || crop != sourceRect) {
            val shaderInput = if (bitmap === source) null else LiquidGlassShader.createSourceBitmapShader(source)
            LiquidGlassShader.setUniforms(shader, params, shaderInput, shaderInput,
                sourceRect = sourceRect, maskRect = RectF(0f, 0f, bounds.width(), bounds.height()))
            bitmap = source
            parameters = params
            crop.set(sourceRect)
        }
        val save = canvas.save()
        try {
            canvas.translate(bounds.left, bounds.top)
            canvas.drawRect(0f, 0f, bounds.width(), bounds.height(), paint)
        } finally {
            canvas.restoreToCount(save)
        }
        return true
    }
}
