package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Weather 17.9.4 uses COUI cards over the animated weather background and separate control
 * backdrop views. Keep the native card fill, animations and foreground in their own layers. */
internal object WeatherSurfaceAdapter {
    private const val PACKAGE = "com.coloros.weather2"
    private val homeGlassIds = setOf("more_glass_bg", "center_glass_bg", "city_manager_glass_bg")
    private val homeBlurIds = setOf("more_blur_layer", "center_blur_layer", "city_manager_blur_layer")
    private val cardIds = setOf("coui_card", "hourlyCardView", "futureDayCardView", "air_quality_item_card",
        "meteorology_item_card", "sun_view_card", "cctvcard", "index_operations_item_card")
    private val controlIds = setOf("btn_add_glass_bg", "btn_special_attention_glass_bg", "center_glass_bg",
        "city_manager_glass_bg", "more_glass_bg", "play_voice_glass_bg",
        "precipitation_cloud_map_glass_bg", "settings_glass_bg", "weather_home_page_feedback")
    private val controlOwners = WeakHashMap<View, Boolean>()
    private val resourceRoles = HashMap<Int, Int>()
    private var installed = false
    private var cardType: Class<*>? = null
    private var cardRadius: Method? = null

    fun install(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean,
        observeCard: (View) -> Unit, backdropApplied: (View) -> Boolean) {
        installed = true
        cardType = runCatching {
            Class.forName("com.coui.appcompat.cardview.COUICardView", false, loader).also {
                check(View::class.java.isAssignableFrom(it))
                cardRadius = it.getMethod("getRadius").takeIf { method ->
                    method.returnType == Float::class.javaPrimitiveType
                }
            }
        }.getOrNull()
        // The preview may construct its capsule view programmatically, without a fixed ID.
        observeNativeControl(api, loader, "com.oplus.weather.main.utils.WeatherPreviewBottomButtonStyleApplier")
        observeNativeControl(api, loader, "com.oplus.weather.utils.feedback.WeatherHomePageFeedbackStyleApplier")
        installHomeButtonStyles(api, loader, enabled)
        installCardFill(api, loader, observeCard, backdropApplied)
        RuntimeDiagnostics.mark("weather-glass-contract",
            if (cardType != null) "已识别天气原生卡片"
            else "天气原生卡片接口未匹配")
    }

    private fun observeNativeControl(api: XposedInterface, loader: ClassLoader, name: String) {
        runCatching {
            val owner = Class.forName(name, false, loader)
            val method = owner.getDeclaredMethod("applyBackdropBlur", View::class.java)
            check(method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType)
            api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    (chain.getArg(0) as? View)?.let { controlOwners[it] = true }
                }
                chain.proceed()
            }
        }.onFailure {
            RuntimeDiagnostics.update("weather-glass-control", "原生按钮入口需匹配：${it.javaClass.simpleName}")
        }
    }

    private fun resourceName(view: View): String? {
        val id = view.id
        if (id ushr 24 != 0x7f) return null
        val name = runCatching { view.resources.getResourceName(id) }.getOrNull() ?: return null
        val prefix = "$PACKAGE:id/"
        return name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** The high-end COUI style paints a different layer and can turn the blur layer off.
     * Route only the three requested controls through Weather's own glass style, which
     * owns the press animation, rounded clip, backdrop and foreground placement. */
    private fun installHomeButtonStyles(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean) {
        runCatching {
            val prefix = "com.oplus.weather.main.home.quality.feel."
            val target = Class.forName(prefix + "HomeButtonStyleTarget", false, loader)
            val helper = Class.forName(prefix + "HomeBottomButtonMaterialStyleHelper", false, loader)
            val glass = Class.forName(prefix + "HomeButtonGlassStyleApplier", false, loader)
            val instance = glass.getField("INSTANCE").get(null)
            val glassBg = target.getMethod("getGlassBg")
            val route = helper.getDeclaredMethod("applyTargetMaterialStyle", target)
            val apply = glass.getDeclaredMethod("applyGlassStyle", target)
            check(route.returnType == Void.TYPE && apply.returnType == Void.TYPE && glassBg.returnType == View::class.java)
            api.hook(route).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val argument = chain.getArg(0)
                val replaced = if (Looper.myLooper() != Looper.getMainLooper()) false else runCatching {
                    val view = glassBg.invoke(argument) as? View ?: return@runCatching false
                    if (resourceName(view) !in homeGlassIds || !enabled()) return@runCatching false
                    controlOwners[view] = true
                    apply.invoke(instance, argument)
                    RuntimeDiagnostics.mark("weather-home-glass", "首页菜单、城市切换和城市管理使用天气原生玻璃背景层")
                    true
                }.onFailure {
                    RuntimeDiagnostics.update("weather-home-glass", "恢复天气原生按钮材质：${it.javaClass.simpleName}")
                }.getOrDefault(false)
                if (replaced) null else chain.proceed()
            }
            for (method in helper.declaredMethods.filter {
                it.name in setOf("refresh", "prepareTitleBarButtonsMaterialStyle", "applyTargetMaterialStyle")
            }) runCatching { api.deoptimize(method) }
        }.onFailure {
            RuntimeDiagnostics.update("weather-home-glass", "首页样式入口未匹配：${it.javaClass.simpleName}")
        }
    }

    private fun installCardFill(api: XposedInterface, loader: ClassLoader,
        observeCard: (View) -> Unit, backdropApplied: (View) -> Boolean) {
        val type = cardType ?: return
        runCatching {
            for (parameter in listOf(android.content.res.ColorStateList::class.java, Integer.TYPE)) {
                val setColor = type.getDeclaredMethod("setCardBackgroundColor", parameter)
                api.hook(setColor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        val view = chain.getThisObject() as? View
                        if (view?.isAttachedToWindow == true && role(view) == AppGlassSurfacePolicy.PANEL)
                            observeCard(view)
                    }
                    result
                }
            }
            val background = Class.forName("com.coui.appcompat.cardview.RoundRectDrawable", false, loader)
            val paintField = background.getDeclaredField("mPaint").apply { isAccessible = true }
            check(paintField.type == Paint::class.java)
            val draw = background.getDeclaredMethod("draw", Canvas::class.java)
            api.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val drawable = chain.getThisObject() as? Drawable
                val canvas = chain.getArg(0) as? Canvas
                val view = drawable?.callback as? View
                if (Looper.myLooper() != Looper.getMainLooper() || canvas?.isHardwareAccelerated != true ||
                    view == null || view.background !== drawable ||
                    role(view) != AppGlassSurfacePolicy.PANEL || !backdropApplied(view)) return@intercept chain.proceed()
                val paint = paintField.get(drawable) as? Paint ?: return@intercept chain.proceed()
                val original = paint.color
                // Preserve the native day/night hue but let the glass underneath remain visible.
                val alpha = Color.alpha(original)
                if (alpha <= 52) return@intercept chain.proceed()
                paint.color = (original and 0x00ffffff) or (52 shl 24)
                try { chain.proceed() } finally { paint.color = original }
            }
            runCatching { api.deoptimize(draw) }
        }.onFailure {
            RuntimeDiagnostics.update("weather-card-glass", "天气卡片保留原生底色：${it.javaClass.simpleName}")
        }
    }

    fun role(view: View): Int? {
        if (!installed) return null
        if (controlOwners[view] == true) return AppGlassSurfacePolicy.CONTROL
        val isCard = cardType?.isInstance(view) == true
        // Programmatic controls enter through their exact stylist above. Only plain
        // background views and the known feedback label use resource lookup here.
        if (!isCard && view.javaClass != View::class.java &&
            view.javaClass != android.widget.TextView::class.java) return null
        val id = view.id
        if (id == View.NO_ID || id ushr 24 != 0x7f) return null
        val declared = resourceRoles.getOrPut(id) {
            when (resourceName(view)) {
                in cardIds -> AppGlassSurfacePolicy.PANEL
                in controlIds, in homeBlurIds -> AppGlassSurfacePolicy.CONTROL
                else -> AppGlassSurfacePolicy.NONE
            }
        }
        return when {
            isCard && declared == AppGlassSurfacePolicy.PANEL -> declared
            !isCard && declared == AppGlassSurfacePolicy.CONTROL -> declared
            else -> null
        }
    }

    fun acceptsBackdrop(view: View, nativeEffect: RenderEffect?): Boolean =
        role(view) == AppGlassSurfacePolicy.PANEL || nativeEffect != null

    fun bounds(view: View): RectF? {
        val role = role(view) ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        if (role == AppGlassSurfacePolicy.PANEL) {
            val outline = Outline()
            val rect = Rect()
            view.background?.getOutline(outline)
            if (outline.getRect(rect) && !rect.isEmpty) return RectF(rect)
        }
        return RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
    }

    fun radius(view: View): Float? = when (role(view)) {
        AppGlassSurfacePolicy.PANEL -> runCatching { cardRadius?.invoke(view) as? Float }
            .getOrNull()?.takeIf { it.isFinite() && it > 0f }
        AppGlassSurfacePolicy.CONTROL -> minOf(view.width, view.height) * .5f
        else -> null
    }
}
