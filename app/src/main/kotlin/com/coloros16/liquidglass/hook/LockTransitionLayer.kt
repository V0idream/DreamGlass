package com.coloros16.liquidglass.hook

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.format.DateFormat
import android.view.View
import com.coloros16.liquidglass.config.AodComposition
import com.coloros16.liquidglass.config.AodCrop
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class LockTransitionStyle(
    val title: String, val duration: Long, val sleepDuration: Long, val imageAspect: Float,
) {
    SYSTEM("系统默认", 0, 0, 1f),
    PHOTO("相片与快门", 700, 380, .88f),
    LETTER("信封与信纸", 820, 420, 1f / .63f);

    fun durationBetween(from: Float, to: Float): Long {
        val full = if (to > from) duration else sleepDuration
        return (full * abs(to - from)).toLong().coerceAtLeast(1L)
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/** The same timeline is evaluated in either direction, for both the system and preview. */
internal object LockTransitionMotion {
    fun ease(progress: Float, start: Float, end: Float): Float {
        val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun blend(start: Float, end: Float, t: Float) = start + (end - start) * t
}

internal data class AodBattery(val percent: Int, val charging: Boolean) {
    companion object {
        fun read(intent: Intent?): AodBattery? {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return null
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                (status == BatteryManager.BATTERY_STATUS_FULL && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            return AodBattery((level * 100f / scale).roundToInt().coerceIn(0, 100), charging)
        }
    }
}

internal class LockTransitionLayer(context: Context, private val preview: Boolean = false) : View(context) {
    var wallpaper: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            updateFraming()
        }
    var style = LockTransitionStyle.SYSTEM
        set(value) {
            if (field == value) return
            field = value
            updateFraming()
        }
    var progress = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    var composition = AodComposition()
        set(value) {
            if (field == value) return
            field = value
            describePreview()
            updateFraming()
        }
    var battery: AodBattery? = null
        set(value) {
            if (field == value) return
            field = value
            batteryText = value?.let { "%.0f%%".format(Locale.getDefault(), it.percent.toFloat()) }.orEmpty()
            describePreview()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clockFont = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val detailFont = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val area = RectF()
    private val frame = RectF()
    private val imageRect = RectF()
    private val bitmapDest = RectF()
    private val path = Path()
    private var framing = AodCrop.FULL
    private var sample: LinearGradient? = null
    private var ambientTime = ""
    private var ambientDate = ""
    private var batteryText = ""
    private var ambientMinute = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = if (preview) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        area.set(0f, 0f, w.toFloat(), h.toFloat())
        if (preview && h > 0) sample = LinearGradient(0f, 0f, w * .65f, h.toFloat(),
            intArrayOf(0xff293b59.toInt(), 0xff627b93.toInt(), 0xffb49882.toInt()), null, Shader.TileMode.CLAMP)
    }

    fun updateAmbientClock() {
        val now = Date()
        val locale = Locale.getDefault()
        ambientTime = DateFormat.getTimeFormat(context).format(now)
        ambientDate = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), now).toString()
        ambientMinute = now.time / 60_000L
        describePreview()
        invalidate()
    }

    private fun describePreview() {
        if (!preview) return
        contentDescription = buildList {
            add(style.title + "息屏预览")
            if (composition.clock && ambientTime.isNotEmpty()) add(ambientTime)
            if (composition.date && ambientDate.isNotEmpty()) add(ambientDate)
            battery?.takeIf { composition.battery }?.let { status ->
                add("电量 " + batteryText + if (status.charging) "，正在充电" else "")
            }
        }.joinToString("，")
    }

    private fun updateFraming() {
        framing = wallpaper?.let { composition.crop.framing(it.width.toFloat() / it.height, style.imageAspect) } ?: AodCrop.FULL
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (area.isEmpty || style == LockTransitionStyle.SYSTEM || (!preview && wallpaper == null)) return
        val p = progress.coerceIn(0f, 1f)
        if (!preview && p == 1f) return
        val seam = LockTransitionMotion.ease(p, .88f, 1f)
        val ambient = 1f - LockTransitionMotion.ease(p, .035f, .32f)
        val saved = canvas.save()
        // Release the canvas into the ROM's ordinary lockscreen after the wallpaper opens.
        if (!preview && seam > 0f) canvas.saveLayerAlpha(area, (255f * (1f - seam)).toInt())
        try {
            canvas.drawColor(Color.BLACK)
            val unit = min(area.width(), area.height())
            val x = ((ambientMinute % 7L) - 3L) * unit * .003f * ambient
            val y = (((ambientMinute / 7L) % 7L) - 3L) * unit * .003f * ambient
            val lifted = canvas.save()
            try {
                canvas.translate(x, y)
                when (style) {
                    LockTransitionStyle.PHOTO -> drawPhoto(canvas, p)
                    LockTransitionStyle.LETTER -> drawLetter(canvas, p)
                    else -> Unit
                }
                if (ambient > 0f) drawAmbient(canvas, unit, ambient)
            } finally {
                canvas.restoreToCount(lifted)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawAmbient(c: Canvas, unit: Float, alpha: Float) {
        c.drawColor(opacity(Color.BLACK, .39f * alpha))
        val x = area.centerX()
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = detailFont
        if (composition.date) {
            paint.color = opacity(0xffaebac8.toInt(), .78f * alpha)
            paint.textSize = unit * .038f
            c.drawText(ambientDate, x, area.height() * if (composition.clock) .137f else .216f, paint)
        }
        if (composition.clock) {
            paint.color = opacity(0xffe5edf8.toInt(), .84f * alpha)
            paint.textSize = unit * .117f
            paint.typeface = clockFont
            c.drawText(ambientTime, x, area.height() * .216f, paint)
        }
        val status = battery ?: return
        if (!composition.battery) return
        val y = area.height() * .831f
        paint.typeface = detailFont
        paint.textSize = unit * .036f
        val textWidth = paint.measureText(batteryText)
        val iconWidth = unit * .059f
        val gap = unit * .018f
        val left = x - (iconWidth + gap + textWidth) / 2f
        val top = y - unit * .015f
        val bottom = y + unit * .015f
        val right = left + iconWidth * .88f
        paint.color = opacity(0xff344451.toInt(), .68f * alpha)
        c.drawRoundRect(left - gap, y - unit * .035f, x * 2f - left + gap, y + unit * .035f, unit * .035f, unit * .035f, paint)
        val tint = if (status.charging) 0xff99d9ba.toInt() else 0xffbdcbd9.toInt()
        paint.color = opacity(tint, .88f * alpha)
        val radius = unit * .005f
        val inset = unit * .006f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * .003f
        c.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        paint.style = Paint.Style.FILL
        c.drawRoundRect(right + inset * .5f, y - radius, left + iconWidth, y + radius, radius, radius, paint)
        if (status.charging) {
            val mid = (left + right) / 2f
            path.reset()
            path.moveTo(mid + inset * .35f, top + inset * .45f)
            path.lineTo(mid - inset * 1.1f, y + inset * .3f)
            path.lineTo(mid - inset * .15f, y + inset * .3f)
            path.lineTo(mid - inset * .35f, bottom - inset * .45f)
            path.lineTo(mid + inset * 1.1f, y - inset * .3f)
            path.lineTo(mid + inset * .15f, y - inset * .3f)
            path.close()
            c.drawPath(path, paint)
        } else if (status.percent > 0) {
            val start = left + inset
            val end = start + (right - inset - start) * status.percent / 100f
            c.drawRoundRect(start, top + inset, end, bottom - inset, radius * .5f, radius * .5f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        c.drawText(batteryText, left + iconWidth + gap, y - (paint.ascent() + paint.descent()) * .5f, paint)
    }

    private fun drawPhoto(c: Canvas, p: Float) {
        val w = area.width(); val h = area.height(); val unit = min(w, h)
        val grow = LockTransitionMotion.ease(p, .16f, .98f)
        val straighten = LockTransitionMotion.ease(p, .03f, .56f)
        val lift = LockTransitionMotion.ease(p, .04f, .27f) * (1f - grow)
        val edge = unit * .023f
        val photoWidth = unit * .64f
        val cw = LockTransitionMotion.blend(photoWidth + edge * 2f, w, grow)
        val ch = LockTransitionMotion.blend(photoWidth / style.imageAspect + edge * 3.7f, h, grow)
        val cy = LockTransitionMotion.blend(h * .565f, h * .5f, grow) - unit * .042f * lift
        frame.set((w - cw) / 2, cy - ch / 2, (w + cw) / 2, cy + ch / 2)
        val turn = c.save()
        c.rotate(-6f * (1f - straighten), w / 2f, cy)
        val radius = unit * .015f * (1f - grow)
        finish(c, frame, radius, 0xfffbf5ea.toInt(), 1f - LockTransitionMotion.ease(p, .35f, .84f), unit)
        val border = edge * (1f - grow)
        imageRect.set(frame.left + border, frame.top + border, frame.right - border, frame.bottom - border * 2.7f)
        backdrop(c, imageRect, radius * .55f, selection = 1f - grow)
        if (p > .08f && p < .58f) drawShutter(c, p, unit)
        c.restoreToCount(turn)
    }

    private fun drawShutter(c: Canvas, p: Float, unit: Float) {
        val amount = LockTransitionMotion.ease(p, .08f, .28f) * (1f - LockTransitionMotion.ease(p, .28f, .58f))
        val save = c.save()
        c.clipRect(imageRect)
        val cx = imageRect.centerX(); val cy = imageRect.centerY()
        val outer = imageRect.width() + imageRect.height()
        val inner = LockTransitionMotion.blend(outer * .58f, unit * .033f, amount)
        for (i in 0 until 6) {
            val a = (i * PI / 3.0) + amount * .51
            val b = a + PI / 3.0
            path.reset()
            path.moveTo(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner)
            path.lineTo(cx + cos(b).toFloat() * inner, cy + sin(b).toFloat() * inner)
            path.lineTo(cx + cos(b + .10).toFloat() * outer, cy + sin(b + .10).toFloat() * outer)
            path.lineTo(cx + cos(a - .10).toFloat() * outer, cy + sin(a - .10).toFloat() * outer)
            path.close()
            paint.color = Color.argb((amount * 219f).toInt(), 21 + i * 3, 25 + i * 3, 31 + i * 3)
            c.drawPath(path, paint)
        }
        val flash = LockTransitionMotion.ease(p, .265f, .30f) * (1f - LockTransitionMotion.ease(p, .30f, .43f))
        if (flash > 0f) c.drawColor(Color.argb((flash * 38f).toInt(), 255, 249, 237))
        c.restoreToCount(save)
    }

    private fun drawLetter(c: Canvas, p: Float) {
        val w = area.width(); val h = area.height(); val unit = min(w, h)
        val pull = LockTransitionMotion.ease(p, .19f, .60f)
        val expand = LockTransitionMotion.ease(p, .39f, .98f)
        val envelopeAlpha = 1f - LockTransitionMotion.ease(p, .48f, .80f)
        val open = LockTransitionMotion.ease(p, .02f, .34f)
        val flap = cos(PI * open).toFloat()
        val width = unit * .76f
        val height = width / style.imageAspect
        val left = (w - width) / 2f
        val top = h * .60f - height / 2f + height * .48f * pull
        val right = left + width; val bottom = top + height
        frame.set(left, top, right, bottom)
        if (envelopeAlpha > 0f) {
            finish(c, frame, unit * .021f, 0xffc4a189.toInt(), envelopeAlpha, unit)
            if (flap < 0f) polygon(c, left, top, right, top, w / 2f,
                top + height * .58f * flap, 0xffe6c9a8.toInt(), envelopeAlpha)
        }
        if (pull > 0f) {
            val sheetW = LockTransitionMotion.blend(width * .84f, w, expand)
            val sheetH = LockTransitionMotion.blend(height * 1.12f, h, expand)
            val sheetTop = LockTransitionMotion.blend(top + height * (.29f - 1.35f * pull), 0f, expand)
            val sheetSave = c.save()
            c.clipRect(0f, 0f, w, LockTransitionMotion.blend(bottom, h, expand))
            frame.set((w - sheetW) / 2f, sheetTop, (w + sheetW) / 2f, sheetTop + sheetH)
            val edge = unit * .019f * (1f - expand)
            val paperAlpha = LockTransitionMotion.ease(p, .19f, .36f)
            finish(c, frame, edge, 0xfffaf3e7.toInt(), (1f - expand) * paperAlpha, unit)
            imageRect.set(frame.left + edge, frame.top + edge, frame.right - edge, frame.bottom - edge)
            backdrop(c, imageRect, edge * .35f, paperAlpha, selection = 1f - expand)
            c.restoreToCount(sheetSave)
        }
        if (envelopeAlpha > 0f) {
            frame.set(left, top, right, bottom)
            val radius = unit * .021f
            val front = c.save()
            path.reset()
            path.addRoundRect(frame, radius, radius, Path.Direction.CW)
            c.clipPath(path)
            backdrop(c, frame, radius, envelopeAlpha, selection = 1f)
            polygon(c, left, top, left, bottom, w / 2f, top + height * .60f, 0xffe9caaa.toInt(), envelopeAlpha * .31f)
            polygon(c, right, top, right, bottom, w / 2f, top + height * .60f, 0xff493329.toInt(), envelopeAlpha * .37f)
            polygon(c, left, bottom, right, bottom, w / 2f, top + height * .36f, 0xffefd3b1.toInt(), envelopeAlpha * .38f)
            if (flap > 0f) {
                polygon(c, left, top, right, top, w / 2f, top + height * .62f * flap,
                    0xfff7ddbd.toInt(), envelopeAlpha * .46f)
                paint.color = opacity(0xffa85552.toInt(), envelopeAlpha * flap)
                c.drawOval(w / 2f - width * .058f, top + (height * .57f - width * .058f) * flap,
                    w / 2f + width * .058f, top + (height * .57f + width * .058f) * flap, paint)
            }
            c.restoreToCount(front)
        }
    }

    private fun backdrop(c: Canvas, rect: RectF, radius: Float, alpha: Float = 1f, selection: Float = 0f) {
        if (rect.isEmpty || alpha <= 0f) return
        val saved = c.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        val bmp = wallpaper?.takeUnless { it.isRecycled || (!c.isHardwareAccelerated && it.config == Bitmap.Config.HARDWARE) }
        paint.color = opacity(Color.WHITE, alpha)
        if (bmp != null) {
            val scale = maxOf(
                rect.width() / (bmp.width * LockTransitionMotion.blend(1f, framing.width, selection)),
                rect.height() / (bmp.height * LockTransitionMotion.blend(1f, framing.height, selection)),
            )
            val width = (bmp.width * scale).coerceAtLeast(rect.width())
            val height = (bmp.height * scale).coerceAtLeast(rect.height())
            val left = (rect.centerX() - width * LockTransitionMotion.blend(.5f, framing.centerX, selection))
                .coerceIn(minOf(rect.right - width, rect.left), rect.left)
            val top = (rect.centerY() - height * LockTransitionMotion.blend(.5f, framing.centerY, selection))
                .coerceIn(minOf(rect.bottom - height, rect.top), rect.top)
            bitmapDest.set(left, top, left + width, top + height)
            c.drawBitmap(bmp, null, bitmapDest, paint)
        } else if (preview) {
            paint.shader = sample
            c.drawRect(rect, paint)
            paint.shader = null
        }
        c.restoreToCount(saved)
    }

    private fun finish(c: Canvas, rect: RectF, radius: Float, color: Int, strength: Float, unit: Float) {
        if (strength <= 0f) return
        for (spread in 3 downTo 1) {
            val s = unit * .006f * spread
            paint.color = Color.argb((strength * 18).toInt(), 0, 0, 0)
            c.drawRoundRect(rect.left - s, rect.top, rect.right + s, rect.bottom + s * 2, radius + s, radius + s, paint)
        }
        paint.color = opacity(color, strength)
        c.drawRoundRect(rect, radius, radius, paint)
    }

    private fun polygon(c: Canvas, x: Float, y: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int, alpha: Float) {
        path.reset()
        path.moveTo(x, y); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        paint.color = opacity(color, alpha)
        c.drawPath(path, paint)
    }

    private fun opacity(color: Int, alpha: Float) = (color and 0xffffff) or ((255f * alpha).toInt().coerceIn(0, 255) shl 24)
}
