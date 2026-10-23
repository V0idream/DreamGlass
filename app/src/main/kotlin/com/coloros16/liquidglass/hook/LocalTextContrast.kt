/* DreamGlass, 2026-10-23. Per-region contrast sampling; GPL-3.0. */
package com.coloros16.liquidglass.hook

import kotlin.math.floor
import kotlin.math.pow

/** Pure color math. Uses linear sRGB luminance, not a gamma-encoded RGB average. */
object LocalTextContrast {
    private val linear = DoubleArray(256) { value ->
        val c = value / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * linear[(argb ushr 16) and 255] +
            0.7152 * linear[(argb ushr 8) and 255] + 0.0722 * linear[argb and 255]

    fun readableColor(original: Int, luminance: Double): Int {
        if (!luminance.isFinite()) return original
        val l = luminance.coerceIn(0.0, 1.0)
        val blackContrast = (l + 0.05) / 0.05
        val whiteContrast = 1.05 / (l + 0.05)
        return (original and -0x1000000) or if (blackContrast >= whiteContrast) 0 else 0x00ffffff
    }
}

/** Immutable, small summed-area map. Constructed on the capture worker; queries are O(1). */
class BackgroundContrastMap private constructor(
    private val columns: Int, private val rows: Int,
    private val light: DoubleArray, private val coverage: DoubleArray,
) {
    private fun integral(table: DoubleArray, x: Double, y: Double): Double {
        val ix = floor(x).toInt().coerceIn(0, columns)
        val iy = floor(y).toInt().coerceIn(0, rows)
        val nx = (ix + 1).coerceAtMost(columns)
        val ny = (iy + 1).coerceAtMost(rows)
        val fx = x - ix; val fy = y - iy; val stride = columns + 1
        val top = table[iy * stride + ix] * (1 - fx) + table[iy * stride + nx] * fx
        val bottom = table[ny * stride + ix] * (1 - fx) + table[ny * stride + nx] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Normalized screenshot coordinates. No overlapping pixels or invalid geometry => no decision. */
    fun mean(left: Float, top: Float, right: Float, bottom: Float): Double? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        val l = left.coerceIn(0f, 1f).toDouble() * columns
        val r = right.coerceIn(0f, 1f).toDouble() * columns
        val t = top.coerceIn(0f, 1f).toDouble() * rows
        val b = bottom.coerceIn(0f, 1f).toDouble() * rows
        if (r <= l || b <= t) return null
        fun sum(table: DoubleArray) = integral(table, r, b) - integral(table, l, b) -
            integral(table, r, t) + integral(table, l, t)
        val weight = sum(coverage)
        return if (weight > 0.000001) (sum(light) / weight).coerceIn(0.0, 1.0) else null
    }

    companion object {
        @JvmStatic fun fromPixels(pixels: IntArray, width: Int, height: Int): BackgroundContrastMap {
            require(width > 0 && height > 0 && width.toLong() * height <= pixels.size)
            val cols = minOf(width, 64)
            val rows = (height.toLong() * cols / width).toInt().coerceIn(1, 192)
            val stride = cols + 1
            val light = DoubleArray(stride * (rows + 1))
            val weight = DoubleArray(light.size)
            for (y in 0 until rows) for (x in 0 until cols) {
                var l = 0.0; var a = 0.0
                // Four distributed probes per cell; never read a Bitmap from the UI thread.
                for (py in 0..1) for (px in 0..1) {
                    val sx = ((x + (px + 0.5) / 2) * width / cols).toInt().coerceIn(0, width - 1)
                    val sy = ((y + (py + 0.5) / 2) * height / rows).toInt().coerceIn(0, height - 1)
                    val color = pixels[sy * width + sx]
                    val alpha = ((color ushr 24) and 255) / 255.0
                    l += LocalTextContrast.luminance(color) * alpha; a += alpha
                }
                val i = (y + 1) * stride + x + 1
                light[i] = l + light[i - 1] + light[i - stride] - light[i - stride - 1]
                weight[i] = a + weight[i - 1] + weight[i - stride] - weight[i - stride - 1]
            }
            return BackgroundContrastMap(cols, rows, light, weight)
        }
    }
}
