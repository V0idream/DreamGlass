package com.coloros16.liquidglass.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.coloros16.liquidglass.config.AodCrop

internal class AodCropView(
    context: Context,
    private val image: Bitmap,
    private val windowAspect: Float,
    initial: AodCrop,
    private val onSelection: (AodCrop, Float) -> Unit,
) : View(context) {
    private val viewport = RectF()
    private val imageBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private val imageAspect = image.width.toFloat() / image.height
    private val base = AodCrop.FULL.framing(imageAspect, windowAspect)
    var crop = initial.framing(imageAspect, windowAspect)
        private set
    val zoom get() = (base.width / crop.width).coerceIn(1f, AodCrop.MAX_ZOOM)
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var focusX = 0f
        private var focusY = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            focusX = detector.focusX
            focusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val moved = crop.pan((focusX - detector.focusX) / viewport.width() * crop.width,
                (focusY - detector.focusY) / viewport.height() * crop.height)
            focusX = detector.focusX
            focusY = detector.focusY
            val anchorX = moved.left + (focusX - viewport.left) / viewport.width() * moved.width
            val anchorY = moved.top + (focusY - viewport.top) / viewport.height() * moved.height
            update(moved.zoom(detector.scaleFactor, anchorX, anchorY, base))
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        isClickable = true
        contentDescription = "壁纸构图。拖动调整位置，双指捏合缩放。"
    }

    fun setZoom(value: Float) = update(crop.zoom(value / zoom, crop.centerX, crop.centerY, base))

    fun reset() = update(base)

    private fun update(next: AodCrop) {
        if (next == crop) return
        crop = next
        onSelection(crop, zoom)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = 3f * density
        val width = minOf(w - inset * 2f, (h - inset * 2f) * windowAspect).coerceAtLeast(0f)
        val height = width / windowAspect
        viewport.set((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        if (viewport.isEmpty) return
        val scaledWidth = viewport.width() / crop.width
        val scaledHeight = scaledWidth / imageAspect
        val left = viewport.left - crop.left * scaledWidth
        val top = viewport.centerY() - crop.centerY * scaledHeight
        imageBounds.set(left, top, left + scaledWidth, top + scaledHeight)
        val saved = canvas.save()
        canvas.clipRect(viewport)
        paint.color = Color.WHITE
        canvas.drawBitmap(image, null, imageBounds, paint)
        canvas.restoreToCount(saved)
        paint.color = 0xffa8e6ff.toInt()
        paint.strokeWidth = density * 1.5f
        paint.style = Paint.Style.STROKE
        canvas.drawRect(viewport, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (viewport.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!viewport.contains(event.x, event.y)) return false
            tracking = true
            parent.requestDisallowInterceptTouchEvent(true)
        }
        if (!tracking) return false
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !pinch.isInProgress) {
                    update(crop.pan((lastX - event.x) / viewport.width() * crop.width,
                        (lastY - event.y) / viewport.height() * crop.height))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        val pointer = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
        lastX = event.getX(pointer)
        lastY = event.getY(pointer)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
