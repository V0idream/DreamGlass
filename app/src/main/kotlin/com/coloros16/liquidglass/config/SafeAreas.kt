package com.coloros16.liquidglass.config

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader

internal object SafeAreas {
    const val MAX_REGIONS = 16
    const val MAX_POINTS = 192
    private const val SIZE = 512
    enum class Scene(val key: String, val title: String) {
        DESKTOP("glass_safe_desktop", "桌面安全区域"),
        LOCK("glass_safe_lock", "锁屏安全区域")
    }
    data class Point(val x: Float, val y: Float)
    data class Mapping(val xx: Float, val xy: Float, val x: Float,
                       val yx: Float, val yy: Float, val y: Float) {
        companion object {
            fun rect(left: Float, top: Float, width: Float, height: Float, w: Int, h: Int): Mapping? {
                if (w <= 0 || h <= 0 || width <= 0f || height <= 0f) return null
                return Mapping(width / w, 0f, left / w, 0f, height / h, top / h)
            }
            fun affine(m: FloatArray, w: Int, h: Int): Mapping? {
                if (w <= 0 || h <= 0) return null
                return Mapping(m[0] / w, m[1] / w, m[2] / w, m[3] / h, m[4] / h, m[5] / h)
            }
        }
    }
    fun encode(regions: List<List<Point>>) = regions.take(MAX_REGIONS).joinToString(";") { region ->
        region.take(MAX_POINTS).joinToString("/") { p ->
            "${(p.x.coerceIn(0f, 1f) * 10000).toInt()},${(p.y.coerceIn(0f, 1f) * 10000).toInt()}"
        }
    }
    fun decode(value: String?): List<List<Point>> = value.orEmpty().take(40_000)
        .split(';', limit = MAX_REGIONS + 1).take(MAX_REGIONS)
        .mapNotNull { region ->
            val points = region.split('/', limit = MAX_POINTS + 1).take(MAX_POINTS).mapNotNull { pair ->
                val comma = pair.indexOf(',')
                if (comma < 0) return@mapNotNull null
                val x = pair.substring(0, comma).toIntOrNull() ?: return@mapNotNull null
                val y = pair.substring(comma + 1).toIntOrNull() ?: return@mapNotNull null
                if (x !in 0..10000 || y !in 0..10000) null else Point(x / 10000f, y / 10000f)
            }
            points.takeIf { it.size >= 3 }
        }
    fun path(points: List<Point>, left: Float, top: Float, w: Float, h: Float): Path =
        Path().apply {
            fillType = Path.FillType.EVEN_ODD
            points.forEachIndexed { i, p ->
                if (i == 0) moveTo(left + p.x * w, top + p.y * h)
                else lineTo(left + p.x * w, top + p.y * h)
            }
            close()
        }

    private class Mask(val texture: Shader, val left: Float, val top: Float, val right: Float, val bottom: Float)
    private val empty by lazy { LinearGradient(0f, 0f, 1f, 1f, Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP) }
    private val executor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "DreamGlass-safe-areas").apply { isDaemon = true } } }
    @Volatile private var desktop: Mask? = null
    @Volatile private var lock: Mask? = null
    private val listeners = mutableMapOf<Scene, Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    @Synchronized
    fun install(scene: Scene, prefs: SharedPreferences, invalidate: () -> Unit = {}) {
        listeners.remove(scene)?.let { (p, l) -> p.unregisterOnSharedPreferenceChangeListener(l) }
        fun update() {
            executor.execute {
                val regions = decode(runCatching { prefs.getString(scene.key, null) }.getOrNull())
                val mask = if (regions.isEmpty()) null else {
                    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    regions.forEach { canvas.drawPath(path(it, 0f, 0f, SIZE.toFloat(), SIZE.toFloat()), paint) }
                    val all = regions.flatten()
                    Mask(BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        if (android.os.Build.VERSION.SDK_INT >= 33) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    }, all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y })
                }
                when (scene) { Scene.DESKTOP -> desktop = mask; Scene.LOCK -> lock = mask }
                invalidate()
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == scene.key || key == null) update()
        }
        listeners[scene] = prefs to listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        update()
    }
    private fun mask(scene: Scene?) = when (scene) { Scene.DESKTOP -> desktop; Scene.LOCK -> lock; null -> null }
    fun stamp(scene: Scene): Int = System.identityHashCode(mask(scene))
    fun active(scene: Scene) = mask(scene) != null

    /** Match the editor's full display coordinates, not a window excluding system bars. */
    fun lockMapping(affine: FloatArray, width: Float, height: Float, screenW: Int, screenH: Int): Mapping? {
        val mask = lock ?: return null
        val map = Mapping.affine(affine, screenW, screenH) ?: return null
        if (!map.intersects(width, height, mask.left, mask.top, mask.right, mask.bottom)) return null
        return map
    }

    fun Mapping.intersects(width: Float, height: Float, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val dx = xx * width
        val dy = xy * height
        val ex = yx * width
        val ey = yy * height
        // Include a one-texel fringe for the bilinear mask edge.
        val fringe = 1f / SIZE
        return x + maxOf(dx, 0f) + maxOf(dy, 0f) >= left - fringe &&
            x + minOf(dx, 0f) + minOf(dy, 0f) <= right + fringe &&
            y + maxOf(ex, 0f) + maxOf(ey, 0f) >= top - fringe &&
            y + minOf(ex, 0f) + minOf(ey, 0f) <= bottom + fringe
    }

    fun desktopMapping(left: Float, top: Float, width: Float, height: Float, w: Int, h: Int): Mapping? {
        val mask = desktop ?: return null
        if (w <= 0 || h <= 0 || left / w >= mask.right || top / h >= mask.bottom ||
            (left + width) / w <= mask.left || (top + height) / h <= mask.top) return null
        return Mapping.rect(left, top, width, height, w, h)
    }

    /** Cache display geometry and scratch buffers; null masks never query view position. */
    class ViewMapper {
        private val matrix = android.graphics.Matrix()
        private val values = FloatArray(9)
        private val origin = IntArray(2)
        private val size = android.graphics.Point()
        private var displayId = -1
        private var rotation = -1
        private var config = 0
        @Suppress("DEPRECATION")
        private fun dimensions(view: android.view.View): Boolean {
            val display = view.display ?: return false
            val configuration = view.resources.configuration.hashCode()
            if (displayId != display.displayId || rotation != display.rotation || config != configuration) {
                display.getRealSize(size)
                displayId = display.displayId; rotation = display.rotation; config = configuration
            }
            return size.x > 0 && size.y > 0
        }
        fun lock(view: android.view.View, affine: FloatArray, width: Float, height: Float): Mapping? =
            if (active(Scene.LOCK) && dimensions(view)) lockMapping(affine, width, height, size.x, size.y) else null

        fun desktop(view: android.view.View, bounds: android.graphics.RectF): Mapping? {
            if (!active(Scene.DESKTOP) || !dimensions(view)) return null
            matrix.reset()
            view.transformMatrixToGlobal(matrix)
            matrix.getValues(values)
            view.getLocationOnScreen(origin)
            values[2] = origin[0].toFloat(); values[5] = origin[1].toFloat()
            // Local bounds can start away from the view origin (e.g. the suggestion card).
            val x = values[0] * bounds.centerX() + values[1] * bounds.centerY() + values[2]
            val y = values[3] * bounds.centerX() + values[4] * bounds.centerY() + values[5]
            val w = kotlin.math.abs(values[0]) * bounds.width() + kotlin.math.abs(values[1]) * bounds.height()
            val h = kotlin.math.abs(values[3]) * bounds.width() + kotlin.math.abs(values[4]) * bounds.height()
            if (desktopMapping(x - w / 2, y - h / 2, w, h, size.x, size.y) == null) return null
            return Mapping.affine(values, size.x, size.y)
        }
        fun desktop(view: android.view.View, screenBounds: android.graphics.RectF, preMapped: Boolean): Mapping? {
            if (!preMapped) return desktop(view, screenBounds)
            if (!active(Scene.DESKTOP) || !dimensions(view)) return null
            val unit = desktopMapping(screenBounds.left, screenBounds.top, screenBounds.width(), screenBounds.height(), size.x, size.y) ?: return null
            return unit.copy(xx = 1f / size.x, yy = 1f / size.y)
        }
    }

    class Binding {
        private var owner: java.lang.ref.WeakReference<RuntimeShader>? = null
        private var texture: Shader? = null
        private var last: Mapping? = null
        fun set(shader: RuntimeShader, scene: Scene?, map: Mapping?) {
            val mask = if (map == null) null else mask(scene)
            val target = mask?.texture ?: empty
            val changed = owner?.get() !== shader
            if (changed || texture !== target) shader.setInputShader("uSafeArea", target)
            val mapping = if (mask == null) null else map
            if (changed || mapping != last) {
                shader.setFloatUniform("uSafeEnabled", if (mapping != null) 1f else 0f)
                if (mapping != null) {
                    shader.setFloatUniform("uSafeX", mapping.xx, mapping.xy, mapping.x)
                    shader.setFloatUniform("uSafeY", mapping.yx, mapping.yy, mapping.y)
                }
            }
            if (changed) owner = java.lang.ref.WeakReference(shader)
            texture = target
            last = mapping
        }
    }
    const val AGSL = """
        uniform shader uSafeArea;
        uniform float uSafeEnabled;
        uniform float3 uSafeX;
        uniform float3 uSafeY;
        float safeDistortion(float2 p) {
            if (uSafeEnabled < 0.5) return 1.0;
            float3 h = float3(p, 1.0);
            float2 uv = float2(dot(uSafeX, h), dot(uSafeY, h));
            if (any(lessThan(uv, float2(0.0))) || any(greaterThan(uv, float2(1.0)))) return 1.0;
            return 1.0 - float(uSafeArea.eval(uv * 512.0).a);
        }
    """
}
