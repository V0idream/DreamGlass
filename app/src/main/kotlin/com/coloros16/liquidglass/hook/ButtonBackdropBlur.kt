package com.coloros16.liquidglass.hook

import kotlin.math.roundToInt

/** DreamGlass, 2026-10-24. Bounded three-box Gaussian approximation on a worker-sized image.
 * Pixels enter/leave as straight ARGB; filtering uses premultiplied channels to avoid color halos. */
object ButtonBackdropBlur {
    fun radius(base: Int, strength: Int): Float =
        if (base <= 0) 0f else base.toFloat() * strength.coerceIn(0, 200) / 100f

    fun apply(pixels: IntArray, width: Int, height: Int, sigma: Float): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (!sigma.isFinite() || sigma <= 0f) return pixels.copyOf()
        val radius = sigma.roundToInt().coerceIn(1, 1024)
        var source = IntArray(pixels.size) { i ->
            val p = pixels[i]; val a = p ushr 24
            (a shl 24) or ((((p ushr 16 and 255) * a + 127) / 255) shl 16) or
                ((((p ushr 8 and 255) * a + 127) / 255) shl 8) or (((p and 255) * a + 127) / 255)
        }
        var target = IntArray(pixels.size)
        repeat(3) {
            box(source, target, width, height, radius, true)
            val swap = source; source = target; target = swap
            box(source, target, width, height, radius, false)
            val swap2 = source; source = target; target = swap2
        }
        return IntArray(source.size) { i ->
            val p = source[i]; val a = p ushr 24
            fun straight(shift: Int) = if (a == 0) 0 else
                (((p ushr shift and 255) * 255 + a / 2) / a).coerceAtMost(255)
            (a shl 24) or (straight(16) shl 16) or (straight(8) shl 8) or straight(0)
        }
    }

    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val count = 2 * r + 1
        for (line in 0 until lines) {
            fun index(x: Int) = if (horizontal) line * w + x.coerceIn(0, length - 1)
                else x.coerceIn(0, length - 1) * w + line
            var a = 0; var red = 0; var green = 0; var blue = 0
            for (x in -r..r) {
                val p = src[index(x)]
                a += p ushr 24; red += p ushr 16 and 255; green += p ushr 8 and 255; blue += p and 255
            }
            for (x in 0 until length) {
                dst[index(x)] = ((a / count) shl 24) or ((red / count) shl 16) or
                    ((green / count) shl 8) or (blue / count)
                val remove = src[index(x - r)]; val add = src[index(x + r + 1)]
                a += (add ushr 24) - (remove ushr 24)
                red += (add ushr 16 and 255) - (remove ushr 16 and 255)
                green += (add ushr 8 and 255) - (remove ushr 8 and 255)
                blue += (add and 255) - (remove and 255)
            }
        }
    }
}
