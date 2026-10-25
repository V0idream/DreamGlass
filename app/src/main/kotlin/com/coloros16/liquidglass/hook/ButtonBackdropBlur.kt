package com.coloros16.liquidglass.hook

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** DreamGlass, 2026-10-25. Three fractional boxes approximate a Gaussian.
 * Preserve premultiplied floating-point channels through all passes; quantize only at output. */
object ButtonBackdropBlur {
    fun radius(base: Int, strength: Int): Float =
        if (base <= 0) 0f else base.toFloat() * strength.coerceIn(0, 200) / 100f

    /** Prefer <=4 screen pixels per working pixel; preserve more detail for weak blur.
     * Never enlarge the source. Bound memory to 524288 pixels and longest edge to 1536. */
    fun workingSize(w: Int, h: Int, screenW: Float, screenH: Float, radius: Float): IntArray {
        require(w > 0 && h > 0 && screenW > 0 && screenH > 0)
        val step = (radius / 6f).coerceIn(1f, 4f)
        val scale = minOf(1.0, screenW.toDouble() / (step * w), screenH.toDouble() / (step * h),
            1536.0 / maxOf(w, h), sqrt(524288.0 / (w.toDouble() * h)))
        return intArrayOf(floor(w * scale).toInt().coerceAtLeast(1), floor(h * scale).toInt().coerceAtLeast(1))
    }

    fun apply(pixels: IntArray, width: Int, height: Int, sigma: Float): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (!sigma.isFinite() || sigma <= 0f) return pixels.copyOf()
        val variance = sigma.coerceAtMost(1024f).toDouble().let { it * it } / 3.0
        val r = floor((sqrt(1.0 + 12.0 * variance) - 1.0) / 2.0).toInt()
        val count = 2 * r + 1
        // Weighted outer taps match variance continuously, including subpixel radii.
        val edge = ((variance * count - r.toDouble() * (r + 1) * count / 3.0) /
            (2.0 * ((r + 1).toDouble() * (r + 1) - variance))).coerceIn(0.0, 1.0).toFloat()
        var source = FloatArray(pixels.size * 4)
        for (i in pixels.indices) {
            val p = pixels[i]; val a = (p ushr 24).toFloat(); val j = i * 4
            source[j] = a
            source[j + 1] = (p ushr 16 and 255) * a / 255f
            source[j + 2] = (p ushr 8 and 255) * a / 255f
            source[j + 3] = (p and 255) * a / 255f
        }
        var target = FloatArray(source.size)
        repeat(3) {
            box(source, target, width, height, r, edge, true)
            val swap = source; source = target; target = swap
            box(source, target, width, height, r, edge, false)
            val swap2 = source; source = target; target = swap2
        }
        return IntArray(pixels.size) { i ->
            val j = i * 4; val alpha = source[j].coerceIn(0f, 255f); val a = alpha.roundToInt()
            fun straight(c: Int) = if (a == 0) 0 else
                (source[j + c] * 255f / alpha).roundToInt().coerceIn(0, 255)
            (a shl 24) or (straight(1) shl 16) or (straight(2) shl 8) or straight(3)
        }
    }

    private fun box(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, edge: Float, horizontal: Boolean) {
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val weight = 2 * r + 1 + 2 * edge
        for (line in 0 until lines) {
            fun index(x: Int) = (if (horizontal) line * w + x.coerceIn(0, length - 1)
                else x.coerceIn(0, length - 1) * w + line) * 4
            val sums = FloatArray(4)
            for (x in -r..r) {
                val j = index(x)
                for (c in 0..3) sums[c] += src[j + c]
            }
            for (x in 0 until length) {
                val j = index(x); val lo = index(x - r - 1); val hi = index(x + r + 1)
                val remove = index(x - r); val add = index(x + r + 1)
                for (c in 0..3) {
                    dst[j + c] = (sums[c] + edge * (src[lo + c] + src[hi + c])) / weight
                    sums[c] += src[add + c] - src[remove + c]
                }
            }
        }
    }
}
