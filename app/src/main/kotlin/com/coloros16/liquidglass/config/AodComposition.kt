package com.coloros16.liquidglass.config

internal data class AodCrop private constructor(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * .5f
    val centerY get() = (top + bottom) * .5f

    /** Both ratios use image coordinates. A framing window never extends past the image. */
    fun framing(imageAspect: Float, windowAspect: Float): AodCrop {
        val ratio = windowAspect / imageAspect
        val fullWidth = minOf(1f, ratio)
        val fullHeight = minOf(1f, 1f / ratio)
        val zoom = maxOf(fullWidth / width, fullHeight / height).coerceIn(1f, MAX_ZOOM)
        return around(centerX, centerY, fullWidth / zoom, fullHeight / zoom)
    }

    fun pan(dx: Float, dy: Float) = around(centerX + dx, centerY + dy, width, height)

    fun zoom(factor: Float, anchorX: Float, anchorY: Float, base: AodCrop): AodCrop {
        if (!factor.isFinite() || factor <= 0f) return this
        // Base is the largest allowed window. Factor > 1 zooms into the source.
        val applied = factor.coerceIn(width / base.width, width / base.width * MAX_ZOOM)
        return around(anchorX + (centerX - anchorX) / applied, anchorY + (centerY - anchorY) / applied,
            (width / applied).coerceAtMost(base.width), (height / applied).coerceAtMost(base.height))
    }

    private fun around(x: Float, y: Float, w: Float, h: Float): AodCrop {
        val l = (x - w * .5f).coerceIn(0f, 1f - w)
        val t = (y - h * .5f).coerceIn(0f, 1f - h)
        return AodCrop(l, t, l + w, t + h)
    }

    companion object {
        val FULL = AodCrop(0f, 0f, 1f, 1f)
        const val MAX_ZOOM = 4f

        fun fromCorners(x1: Float, y1: Float, x2: Float, y2: Float): AodCrop? {
            if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
            val left = minOf(x1, x2).coerceIn(0f, 1f)
            val top = minOf(y1, y2).coerceIn(0f, 1f)
            val right = maxOf(x1, x2).coerceIn(0f, 1f)
            val bottom = maxOf(y1, y2).coerceIn(0f, 1f)
            if (right - left < .0001f || bottom - top < .0001f) return null
            return AodCrop(left, top, right, bottom)
        }
    }
}

internal data class AodComposition(
    val clock: Boolean = true,
    val date: Boolean = true,
    val battery: Boolean = true,
    val crop: AodCrop = AodCrop.FULL,
) {
    fun encode() = listOf(clock, date, battery, crop.left, crop.top, crop.right, crop.bottom).joinToString(",")

    companion object {
        const val KEY_PREFIX = "aod_composition_"

        fun decode(value: String?): AodComposition {
            val parts = value?.split(',', limit = 8) ?: return AodComposition()
            if (parts.size != 7) return AodComposition()
            val corners = parts.drop(3).map { it.toFloatOrNull() ?: Float.NaN }
            return AodComposition(
                clock = parts[0].toBooleanStrictOrNull() ?: true,
                date = parts[1].toBooleanStrictOrNull() ?: true,
                battery = parts[2].toBooleanStrictOrNull() ?: true,
                crop = AodCrop.fromCorners(corners[0], corners[1], corners[2], corners[3]) ?: AodCrop.FULL,
            )
        }
    }
}
