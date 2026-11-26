package com.coloros16.liquidglass.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlin.math.min

/** Draws the control lens and its glyph atop the same backdrop as the surrounding island. */
@SuppressLint("AppCompatCustomView") // The runtime view is also created under the SystemUI theme.
internal class GlassIslandControl(
    context: Context,
    action: IslandAction,
    private val material: IslandMaterial?,
) : TextView(context) {
    private val glyph = action.glyph
    private val primary = glyph == MediaGlyph.PLAY || glyph == MediaGlyph.PAUSE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Path()
    private var rim: Shader? = null
    private var sheen: Shader? = null

    init {
        gravity = Gravity.CENTER
        minHeight = context.islandDp(48)
        isFocusable = true
        isClickable = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(2f * resources.displayMetrics.density, 0f, 1f, 0x8009101a.toInt())
        if (glyph == null) {
            text = action.label
            textSize = 13f
            setPadding(context.islandDp(10), context.islandDp(4), context.islandDp(10), context.islandDp(4))
        } else contentDescription = action.label
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        rim = LinearGradient(0f, 0f, w * .82f, h.toFloat(),
            intArrayOf(0xe4f8ffff.toInt(), 0x429ec2e7, 0x917eafc9.toInt()), null, Shader.TileMode.CLAMP)
        sheen = LinearGradient(0f, 0f, w * .45f, h.toFloat(),
            intArrayOf(if (primary) 0x68e3faff else 0x43d9f5ff, 0x189bccef, 0x3551769b), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(w, h) * .5f
        val lens = material?.invoke(canvas, this, radius) == true
        paint.style = Paint.Style.FILL
        paint.color = if (lens) {
            if (primary) 0x4a1d384e else 0x54132031
        } else if (primary) 0xff34516a.toInt() else 0xff26384a.toInt()
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        paint.alpha = 255
        paint.shader = sheen
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        paint.shader = null
        if (isPressed || isFocused) {
            paint.color = if (isPressed) 0x47d1f4ff else 0x23d1f4ff
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        }
        paint.alpha = 255
        paint.shader = rim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * if (isFocused) 1.7f else 1f
        val inset = paint.strokeWidth * .55f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius - inset, radius - inset, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        if (glyph == null) super.onDraw(canvas) else drawGlyph(canvas, glyph, w, h)
    }

    private fun drawGlyph(canvas: Canvas, glyph: MediaGlyph, w: Float, h: Float) {
        val size = context.islandDp(if (primary) 24 else 21).toFloat()
        val saved = canvas.save()
        canvas.translate((w - size) / 2f, (h - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        if (glyph == MediaGlyph.PREVIOUS) { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
        paint.color = 0xfff6fbff.toInt()
        when (glyph) {
            MediaGlyph.PAUSE -> {
                canvas.drawRoundRect(5f, 3.5f, 10f, 20.5f, 1.2f, 1.2f, paint)
                canvas.drawRoundRect(14f, 3.5f, 19f, 20.5f, 1.2f, 1.2f, paint)
            }
            MediaGlyph.PLAY, MediaGlyph.PREVIOUS, MediaGlyph.NEXT -> {
                val skip = glyph != MediaGlyph.PLAY
                val x = if (skip) 3f else 6f
                val tip = if (skip) 17f else 21f
                icon.rewind()
                icon.moveTo(x, 5f)
                icon.quadTo(x, 3f, x + 1.7f, 4f)
                icon.lineTo(tip - .8f, 11f)
                icon.quadTo(tip + .6f, 12f, tip - .8f, 13f)
                icon.lineTo(x + 1.7f, 20f)
                icon.quadTo(x, 21f, x, 19f)
                icon.close()
                canvas.drawPath(icon, paint)
                if (skip) canvas.drawRoundRect(18.5f, 4f, 21f, 20f, 1f, 1f, paint)
            }
            MediaGlyph.LIKE, MediaGlyph.UNLIKE -> {
                icon.rewind()
                icon.moveTo(12f, 20.5f)
                icon.cubicTo(10.5f, 19f, 3f, 14.3f, 3f, 9.6f)
                icon.cubicTo(3f, 4f, 9.3f, 2.8f, 12f, 7.1f)
                icon.cubicTo(14.7f, 2.8f, 21f, 4f, 21f, 9.6f)
                icon.cubicTo(21f, 14.3f, 13.5f, 19f, 12f, 20.5f)
                icon.close()
                if (glyph == MediaGlyph.LIKE) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.9f
                    paint.strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(icon, paint)
                paint.style = Paint.Style.FILL
            }
        }
        canvas.restoreToCount(saved)
    }
}

internal class MediaWave(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffe9f7ff.toInt() }
    private val heights = floatArrayOf(.35f, .71f, .52f, .27f)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val step = width / 4f
        val half = step * .24f
        heights.forEachIndexed { index, portion ->
            val x = (index + .5f) * step
            val y = (height * portion) / 2f
            canvas.drawRoundRect(x - half, height / 2f - y, x + half, height / 2f + y, half, half, paint)
        }
    }
}
