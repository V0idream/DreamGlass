/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.coloros16.liquidglass.hook.AppGlassEffect
import com.coloros16.liquidglass.hook.CalculatorBackgroundLease
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Uses the OEM *background* RenderNode API on this Activity's synthetic content.
 * A normal app can be denied access to that API. Such a result is recorded, not
 * bypassed. In particular, View.setRenderEffect is not an equivalent fallback.
 *
 * Each phase uses exactly the same nodes and geometry. The only difference
 * between the final two phases is AppGlassEffect's external GPU blur chain.
 * PixelCopy captures only our window; no vendor app or user image is read.
 */
class NativeBackdropProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val hosts = mutableListOf<Host>()
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var output: File
    private var bridge: Method? = null
    private var baseline: Bitmap? = null
    private var destroyed = false
    private var nativeCalls = 0
    private var phaseIndex = 0
    private val retainedEffects = mutableListOf<RenderEffect>()
    private val phaseResults = JSONArray()
    private val phaseNames = listOf("baseline", "local_coordinates", "shared_no_external_blur", "shared_external_blur_20")
    private val report = JSONObject().apply {
        put("schema", 1)
        put("status", "PENDING")
        put("testScope", "Owned synthetic Activity using the actual OEM background API; not vendor app Hook acceptance")
        put("vendorHookTested", false)
        put("foregroundRenderEffectUsed", false)
        put("hiddenApiPolicyChanged", false)
        put("windowReadback", "PixelCopy of this Activity only; output alpha is opaque and cannot prove intermediate alpha")
        put("sdk", Build.VERSION.SDK_INT)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("hardware", Build.HARDWARE)
        put("emulatorHeuristic", Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator", true))
        put("phases", phaseResults)
    }

    private inner class ProbeHost : FrameLayout(this) {
        var hardwareDrawObserved = false
        private val marker = Paint().apply { color = Color.argb(1, 255, 0, 0) }
        init { setWillNotDraw(false); background = ColorDrawable(Color.TRANSPARENT) }
        override fun onDraw(canvas: Canvas) {
            hardwareDrawObserved = hardwareDrawObserved || canvas.isHardwareAccelerated
            // Keep a display list even when the parent has no foreground paint.
            canvas.drawRect(width - 2f, height - 2f, width.toFloat(), height.toFloat(), marker)
            super.onDraw(canvas)
        }
    }

    private data class Host(
        val name: String,
        val view: ProbeHost,
        val circular: Boolean = false,
        val glyphChild: Boolean = false,
        val negativeControl: Boolean = false,
        val maskInset: Boolean = false
    ) {
        fun rect(): RectF {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            // Fixture pivots are zero, with no rotated or scaled ancestors.
            return RectF(location[0].toFloat(), location[1].toFloat(),
                location[0] + view.width * view.scaleX, location[1] + view.height * view.scaleY)
        }
        fun localMask(): RectF = if (maskInset) RectF(16f, 12f, view.width - 18f, view.height - 20f)
            else RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        fun windowMask(): RectF {
            val origin = rect()
            val local = localMask()
            return RectF(origin.left + local.left * view.scaleX, origin.top + local.top * view.scaleY,
                origin.left + local.right * view.scaleX, origin.top + local.bottom * view.scaleY)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = File(filesDir, "native-backdrop-probe").apply { mkdirs() }
        root = FrameLayout(this).apply { background = SyntheticBackdrop() }
        status = TextView(this).apply {
            text = "OEM backdrop probe · synthetic window\nPreparing native API and PixelCopy…"
            setTextColor(Color.WHITE); textSize = 14f; setPadding(16, 12, 16, 0)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.post {
            try {
                buildFixture()
                resolveNativeApi()
                handler.postDelayed({ runPhase() }, 450)
            } catch (error: Throwable) { fail(error, "fixture_initialization") }
        }
    }

    private fun buildFixture() {
        val pad = max(18, (root.width * .045f).roundToInt())
        val header = max(90, (root.height * .10f).roundToInt())
        val cellWidth = (root.width - pad * 3) / 2
        val rowHeight = min((root.height - header - pad * 4) / 3, (cellWidth * .82f).roundToInt())
        require(cellWidth >= 80 && rowHeight >= 65) { "Window too small for measurable probe geometry" }
        val labelHeight = min(27, rowHeight / 5)
        val nodeHeight = rowHeight - labelHeight
        fun add(name: String, column: Int, row: Int, circular: Boolean = false,
                inset: Boolean = false, scaled: Boolean = false, glyph: Boolean = false,
                negative: Boolean = false, maskInset: Boolean = false) {
            val x = pad + column * (cellWidth + pad)
            val y = header + row * (rowHeight + pad)
            root.addView(TextView(this).apply {
                text = name; textSize = 11f; setTextColor(Color.WHITE)
            }, FrameLayout.LayoutParams(cellWidth, labelHeight).apply { leftMargin = x; topMargin = y })
            val container = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
            root.addView(container, FrameLayout.LayoutParams(cellWidth, nodeHeight).apply {
                leftMargin = x; topMargin = y + labelHeight
            })
            val view = ProbeHost()
            val left = if (inset) 17 else 0
            val top = if (inset) 23 else 0
            val width = if (circular) min(cellWidth, nodeHeight) else cellWidth - if (inset) 48 else 0
            val height = if (circular) width else nodeHeight - if (inset) 45 else 0
            if (scaled) { view.pivotX = 0f; view.pivotY = 0f; view.scaleX = .78f; view.scaleY = .78f }
            if (negative || glyph) view.background = ColorDrawable(Color.BLACK)
            if (glyph) {
                CalculatorBackgroundLease.sync(view, true)
                view.addView(View(this).apply { background = PlusGlyph() }, FrameLayout.LayoutParams(-1, -1))
            }
            container.addView(view, FrameLayout.LayoutParams(width, height).apply { leftMargin = left; topMargin = top })
            hosts += Host(name, view, circular, glyph, negative, maskInset)
        }
        add("plain · nonzero origin", 0, 0)
        add("inset child · 17 / 23 px", 1, 0, inset = true)
        add("scale 0.78 · pivot 0", 0, 1, scaled = true)
        add("circle · parent FX / SVG child", 1, 1, circular = true, glyph = true)
        add("mask inset · 16 / 12 / 18 / 20 px", 0, 2, maskInset = true)
        add("opaque parent · negative control", 1, 2, negative = true)
        report.put("fixture", JSONObject().put("width", root.width).put("height", root.height)
            .put("nodeSizeUnits", "physical pixels").put("inset", JSONArray(listOf(17, 23, 31, 22))))
    }

    private fun resolveNativeApi() {
        val api = JSONObject().put("class", "com.oplus.view.OplusViewBackgroundRenderEffect")
            .put("method", "setBackgroundRenderEffect(RenderEffect, View)")
            .put("classAvailable", false).put("methodAvailable", false)
        report.put("nativeApi", api)
        try {
            val type = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect")
            api.put("classAvailable", true)
            bridge = type.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
            api.put("methodAvailable", true)
        } catch (error: Throwable) {
            api.put("error", describe(error))
            report.put("availabilityFailure", true)
            // Still capture a baseline so API denial is distinguishable from no window/readback.
        }
    }

    private fun runPhase() {
        if (destroyed) return
        try {
            val name = phaseNames[phaseIndex]
            status.text = "OEM backdrop probe · $name\nSynthetic source only · actual native background API"
            retainedEffects.clear()
            for (host in hosts) {
                val mask = host.localMask()
                val effect = when (phaseIndex) {
                    0 -> null
                    1 -> coordinateEffect(host.view.width, host.view.height)
                    else -> AppGlassEffect().create(LiquidGlassShader.Params(
                        viewportWidth = mask.width(), viewportHeight = mask.height(),
                        cornerRadius = if (host.circular) min(mask.width(), mask.height()) / 2f else min(mask.width(), mask.height()) * .26f,
                        blurRadius = if (phaseIndex == 3) 20f else 0f,
                        surfacePolish = true, debugGlassBounds = true), bounds = mask)
                }
                effect?.let { retainedEffects += it }
                if (bridge != null) {
                    bridge!!.invoke(null, effect, host.view)
                    nativeCalls++
                }
                host.view.invalidate()
            }
            root.invalidate()
            handler.postDelayed({ capturePhase(name) }, 500)
        } catch (error: Throwable) { fail(error, "apply_${phaseNames[phaseIndex]}") }
    }

    private fun coordinateEffect(width: Int, height: Int): RenderEffect {
        val shader = RuntimeShader("""
            uniform shader uBackdrop;
            uniform float2 uSize;
            half4 main(float2 p) {
                float2 q = p / uSize;
                float bx = q.x < 0.25 ? 0.0 : (q.x < 0.5 ? 1.0 : (q.x < 0.75 ? 2.0 : 3.0));
                float by = q.y < 0.25 ? 0.0 : (q.y < 0.5 ? 1.0 : (q.y < 0.75 ? 2.0 : 3.0));
                half source = uBackdrop.eval(p).r;
                return half4((bx + 0.25) * 0.25, (by + 0.25) * 0.25, 0.125 + source * 0.001, 1.0);
            }
        """.trimIndent())
        shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        shader.setInputShader("uBackdrop", LinearGradient(0f, 0f, 1f, 1f, Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP))
        return RenderEffect.createRuntimeShaderEffect(shader, "uBackdrop")
    }

    private fun capturePhase(name: String, retry: Boolean = false) {
        if (destroyed) return
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (destroyed) { bitmap.recycle(); return@request }
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    if (!retry && result == PixelCopy.ERROR_SOURCE_NO_DATA) {
                        handler.postDelayed({ capturePhase(name, true) }, 300)
                    } else fail(IllegalStateException("PixelCopy result=$result"), "readback_$name")
                    return@request
                }
                try {
                    File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val measurements = JSONArray()
                    for (host in hosts) measurements.put(measure(host, bitmap))
                    phaseResults.put(JSONObject().put("name", name).put("pixelCopyResult", result).put("hosts", measurements))
                    if (phaseIndex == 0) baseline = bitmap else bitmap.recycle()
                    writeReport()
                    if (bridge == null) finishReport("UNAVAILABLE", "Native class/method not accessible from this normal app; no fallback or bypass attempted")
                    else if (++phaseIndex < phaseNames.size) handler.postDelayed({ runPhase() }, 150)
                    else evaluate()
                } catch (error: Throwable) {
                    if (bitmap !== baseline && !bitmap.isRecycled) bitmap.recycle()
                    fail(error, "measure_$name")
                }
            }, handler)
        } catch (error: Throwable) { bitmap.recycle(); fail(error, "request_$name") }
    }

    private fun measure(host: Host, bitmap: Bitmap): JSONObject {
        val hostRect = host.rect()
        val rect = if (phaseIndex >= 2) host.windowMask() else hostRect
        val json = JSONObject().put("name", host.name).put("viewWidth", host.view.width).put("viewHeight", host.view.height)
            .put("viewScale", JSONArray(listOf(host.view.scaleX, host.view.scaleY)))
            .put("windowRect", rectJson(hostRect)).put("maskRectHostLocal", rectJson(host.localMask()))
            .put("expectedMaskWindowRect", rectJson(host.windowMask())).put("hardwareDrawObserved", host.view.hardwareDrawObserved)
            .put("opaqueMetadata", host.view.isOpaque).put("negativeControl", host.negativeControl)
        if (phaseIndex == 1 && !host.negativeControl) {
            val xEdges = bandEdges(bitmap, rect, true)
            val yEdges = bandEdges(bitmap, rect, false)
            val expectedX = listOf(.25f, .5f, .75f).map { it * rect.width() }
            val expectedY = listOf(.25f, .5f, .75f).map { it * rect.height() }
            fun aligned(actual: List<Int>, expected: List<Float>, extent: Float): Boolean =
                actual.size == 3 && actual.zip(expected).all { abs(it.first - it.second) <= max(3f, extent * .015f) }
            json.put("coordinateBands", JSONObject().put("xTransitionsLocalPx", JSONArray(xEdges))
                .put("yTransitionsLocalPx", JSONArray(yEdges)).put("expectedXLocalPx", JSONArray(expectedX))
                .put("expectedYLocalPx", JSONArray(expectedY))
                .put("localCoordinatesMatch", aligned(xEdges, expectedX, rect.width()) && aligned(yEdges, expectedY, rect.height()))
                .put("interpretation", "Step transitions at 25/50/75 percent verify p is View-local; avoids assuming a transfer function for RGB"))
            val samples = JSONArray()
            for (q in listOf(.125f, .375f, .625f, .875f)) samples.put(JSONObject().put("normalizedXY", q)
                .put("expectedShaderLocalXY", JSONArray(listOf(q * host.view.width, q * host.view.height)))
                .put("argb", argb(sample(bitmap, rect.left + q * rect.width(), rect.top + q * rect.height()))))
            json.put("coordinateColorSamples", samples)
        }
        if (phaseIndex >= 2 && !host.negativeControl) {
            var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1; var count = 0
            val margin = min(16, max(3, (rect.width() * .03f).roundToInt()))
            // Scan the full host, including the inset strips: an erroneous mask
            // at 0,0 must not escape detection by falling outside the expected ROI.
            val x0 = max(0, hostRect.left.roundToInt() - margin); val y0 = max(0, hostRect.top.roundToInt() - margin)
            val x1 = min(bitmap.width - 1, hostRect.right.roundToInt() + margin); val y1 = min(bitmap.height - 1, hostRect.bottom.roundToInt() + margin)
            for (y in y0..y1) for (x in x0..x1) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) < 55 && Color.green(color) > 195 && Color.blue(color) > 230) {
                    count++; left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
                }
            }
            val tolerance = max(4f, max(rect.width(), rect.height()) * .025f)
            val aligned = count > 20 && abs(left - rect.left) <= tolerance && abs(top - rect.top) <= tolerance &&
                abs(right + 1 - rect.right) <= tolerance && abs(bottom + 1 - rect.bottom) <= tolerance
            val mask = JSONObject().put("cyanPixels", count).put("geometryMatchesView", aligned)
                .put("expectedWindowBounds", rectJson(rect)).put("tolerancePx", tolerance)
            if (count > 0) mask.put("measuredWindowBounds", JSONArray(listOf(left, top, right + 1, bottom + 1)))
                .put("centerOffsetPx", JSONArray(listOf((left + right + 1) / 2f - rect.centerX(), (top + bottom + 1) / 2f - rect.centerY())))
                .put("sizeRatio", JSONArray(listOf((right - left + 1) / rect.width(), (bottom - top + 1) / rect.height())))
            json.put("sharedShaderMask", mask)
            val corners = JSONArray(); var cornerMatch = true
            for (q in listOf(.025f to .025f, .975f to .025f, .025f to .975f, .975f to .975f)) {
                val x = rect.left + rect.width() * q.first; val y = rect.top + rect.height() * q.second
                val before = sample(baseline!!, x, y); val after = sample(bitmap, x, y)
                val delta = colorDistance(before, after)
                cornerMatch = cornerMatch && delta <= 20
                corners.put(JSONObject().put("windowXY", JSONArray(listOf(x, y))).put("baselineArgb", argb(before))
                    .put("outputArgb", argb(after)).put("maxRgbDelta", delta)
                    .put("unexpectedBlack", Color.red(after) < 8 && Color.green(after) < 8 && Color.blue(after) < 8 && colorDistance(before, Color.BLACK) > 25))
            }
            json.put("corners", corners).put("roundedCornerBackgroundPreserved", cornerMatch)
            if (host.maskInset) {
                val samples = JSONArray(); var outsideMatch = true
                val points = listOf((hostRect.left + 8f) to rect.centerY(), (hostRect.right - 9f) to rect.centerY(),
                    rect.centerX() to (hostRect.top + 6f), rect.centerX() to (hostRect.bottom - 10f))
                for ((x, y) in points) {
                    val before = sample(baseline!!, x, y); val after = sample(bitmap, x, y)
                    val delta = colorDistance(before, after)
                    outsideMatch = outsideMatch && delta <= 20
                    samples.put(JSONObject().put("windowXY", JSONArray(listOf(x, y))).put("baselineArgb", argb(before))
                        .put("outputArgb", argb(after)).put("maxRgbDelta", delta))
                }
                json.put("outsideInsetMask", JSONObject().put("backgroundContinuous", outsideMatch).put("samples", samples)
                    .put("scope", "Passthrough outside the host-local mask; optical refraction inside the mask is intentionally not compared to an unfiltered source"))
            }
            if (host.glyphChild) {
                val center = sample(bitmap, rect.centerX(), rect.centerY())
                json.put("svgAsChildBackground", JSONObject().put("centerArgb", argb(center))
                    .put("foregroundPreserved", Color.red(center) > 235 && Color.green(center) > 235 && Color.blue(center) > 235))
            }
        }
        return json
    }

    private fun bandEdges(bitmap: Bitmap, rect: RectF, horizontal: Boolean): List<Int> {
        val extent = (if (horizontal) rect.width() else rect.height()).roundToInt()
        val result = mutableListOf<Int>()
        var previous = -1
        for (i in 3 until extent - 3) {
            val color = if (horizontal) sample(bitmap, rect.left + i, rect.top + rect.height() * .18f)
                else sample(bitmap, rect.left + rect.width() * .18f, rect.top + i)
            val channel = if (horizontal) Color.red(color) else Color.green(color)
            if (previous >= 0 && abs(channel - previous) > 20 && (result.isEmpty() || i - result.last() > 3)) result += i
            previous = channel
        }
        return result
    }

    private fun evaluate() {
        var matches = true
        val problems = JSONArray()
        for (i in 0 until phaseResults.length()) {
            val phase = phaseResults.getJSONObject(i)
            val measured = phase.getJSONArray("hosts")
            for (j in 0 until measured.length()) {
                val host = measured.getJSONObject(j)
                if (host.optBoolean("negativeControl")) continue
                fun check(pass: Boolean, reason: String) {
                    if (!pass) { matches = false; problems.put("${phase.getString("name")}/${host.getString("name")}: $reason") }
                }
                check(host.getBoolean("hardwareDrawObserved"), "no hardware draw observed")
                if (i == 1) check(host.getJSONObject("coordinateBands").getBoolean("localCoordinatesMatch"), "native shader coordinates differ from View-local geometry")
                if (i >= 2) {
                    check(host.getJSONObject("sharedShaderMask").getBoolean("geometryMatchesView"), "cyan mask does not match visual View bounds")
                    check(host.getBoolean("roundedCornerBackgroundPreserved"), "rounded corners changed the underlying background")
                    if (host.has("outsideInsetMask")) check(host.getJSONObject("outsideInsetMask").getBoolean("backgroundContinuous"), "host-local inset strips do not preserve the background")
                    if (host.has("svgAsChildBackground")) check(host.getJSONObject("svgAsChildBackground").getBoolean("foregroundPreserved"), "SVG child background did not remain white")
                }
            }
        }
        report.put("measurementFailures", problems)
        val comparisons = JSONArray()
        val noBlur = phaseResults.getJSONObject(2).getJSONArray("hosts")
        val blurred = phaseResults.getJSONObject(3).getJSONArray("hosts")
        for (index in 0 until noBlur.length()) {
            val before = noBlur.getJSONObject(index)
            val after = blurred.getJSONObject(index)
            if (before.optBoolean("negativeControl")) continue
            val originalMask = before.getJSONObject("sharedShaderMask")
            val blurMask = after.getJSONObject("sharedShaderMask")
            val comparison = JSONObject().put("name", before.getString("name"))
                .put("externalBlurRadiusPx", JSONArray(listOf(0, 20)))
                .put("bothMasksDetected", originalMask.has("measuredWindowBounds") && blurMask.has("measuredWindowBounds"))
            if (originalMask.has("measuredWindowBounds") && blurMask.has("measuredWindowBounds")) {
                val originalBounds = originalMask.getJSONArray("measuredWindowBounds")
                val blurBounds = blurMask.getJSONArray("measuredWindowBounds")
                val originalCenter = originalMask.getJSONArray("centerOffsetPx")
                val blurCenter = blurMask.getJSONArray("centerOffsetPx")
                val boundsDelta = JSONArray()
                val centerDelta = JSONArray()
                for (axis in 0..3) boundsDelta.put(blurBounds.getDouble(axis) - originalBounds.getDouble(axis))
                for (axis in 0..1) centerDelta.put(blurCenter.getDouble(axis) - originalCenter.getDouble(axis))
                comparison.put("blurMinusNoBlurBoundsPx", boundsDelta)
                    .put("blurMinusNoBlurCenterPx", centerDelta)
            }
            comparisons.put(comparison)
        }
        report.put("blurComparison", JSONObject().put("description",
            "Identical hosts and optics params; only AppGlassEffect external GPU blur radius changes from 0 to 20 physical px")
            .put("hosts", comparisons))
        finishReport(if (matches) "PASS" else "OBSERVED_MISMATCH", if (matches)
            "Native calls, hardware PixelCopy, local coordinate bands, mask geometry, corner passthrough and SVG foreground all match this synthetic fixture"
            else "Measurements disagree with at least one expected native backdrop property; inspect per-phase JSON and PNGs before changing production geometry")
    }

    private fun fail(error: Throwable, stage: String) {
        report.put("failureStage", stage).put("error", describe(error))
        finishReport(if (stage.startsWith("apply_")) "NATIVE_CALL_FAILED" else "FAIL", "Probe stopped; no substitute render channel or privilege escalation was attempted")
    }

    private fun finishReport(result: String, detail: String) {
        report.put("status", result).put("detail", detail).put("nativeCalls", nativeCalls)
            .put("windowHardwareAccelerated", root.isHardwareAccelerated)
        writeReport()
        status.text = "$result · $detail\nfiles/native-backdrop-probe/report.json"
        baseline?.recycle(); baseline = null
        android.util.Log.i("NativeBackdropProbe", "$result; nativeCalls=$nativeCalls; report=${File(output, "report.json")}")
    }

    private fun writeReport() { File(output, "report.json").writeText(report.toString(2), Charsets.UTF_8) }
    private fun rectJson(rect: RectF) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun sample(bitmap: Bitmap, x: Float, y: Float) = bitmap.getPixel(x.roundToInt().coerceIn(0, bitmap.width - 1), y.roundToInt().coerceIn(0, bitmap.height - 1))
    private fun argb(color: Int) = "#%08X".format(color)
    private fun colorDistance(a: Int, b: Int) = max(abs(Color.red(a) - Color.red(b)), max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))))
    private fun describe(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return "${cause.javaClass.name}: ${cause.message}".take(1600)
    }

    override fun onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null)
        for (host in hosts) {
            runCatching { bridge?.invoke(null, null, host.view) }
            if (host.glyphChild) CalculatorBackgroundLease.sync(host.view, false)
        }
        retainedEffects.clear(); baseline?.recycle(); baseline = null
        super.onDestroy()
    }

    private class PlusGlyph : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        override fun draw(canvas: Canvas) {
            val size = min(bounds.width(), bounds.height()).toFloat()
            paint.strokeWidth = max(5f, size * .055f)
            val half = size * .14f; val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            canvas.drawLine(x - half, y, x + half, y, paint); canvas.drawLine(x, y - half, x, y + half, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class SyntheticBackdrop : Drawable() {
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            paint.shader = LinearGradient(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
                intArrayOf(Color.rgb(76, 15, 63), Color.rgb(184, 88, 31), Color.rgb(42, 22, 61)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint); paint.shader = null
            val cell = max(14, min(bounds.width(), bounds.height()) / 23)
            paint.color = Color.argb(50, 240, 138, 182)
            for (row in 0..bounds.height() / cell) for (column in 0..bounds.width() / cell) {
                if ((row + column) % 2 == 0) canvas.drawRect((column * cell).toFloat(), (row * cell).toFloat(),
                    ((column + 1) * cell).toFloat(), ((row + 1) * cell).toFloat(), paint)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }
}
