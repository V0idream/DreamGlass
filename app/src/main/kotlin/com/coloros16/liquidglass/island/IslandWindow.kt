package com.coloros16.liquidglass.island

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

internal class IslandWindow(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun toggle()
        fun collapseCard()
        fun page(direction: Int)
        fun dismiss()
        fun select(id: String)
        fun restore()
        fun interacted()
        fun cardTouchChanged(touching: Boolean)
        fun attached()
        fun surfaceRemoved(view: View)
    }

    private data class Slot(val root: FrameLayout, val params: WindowManager.LayoutParams)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val slots = LinkedHashMap<String, Slot>()
    private val touchHandler = Handler(Looper.getMainLooper())
    private val collapseFromOutside = Runnable(callbacks::collapseCard)
    private val touchLocation = IntArray(2)
    private var displayKey: String? = null
    private var ready = false
    private var wasExpanded = false
    private fun dp(n: Int) = context.islandDp(n)
    fun roots(): List<View> = slots.values.map { it.root }

    fun render(items: List<IslandActivity>, expanded: Boolean, insets: WindowInsets?, animation: Boolean): Boolean {
        val primary = items.firstOrNull() ?: return false
        val bounds = wm.currentWindowMetrics.bounds
        if (bounds.width() < dp(145) || bounds.height() < dp(170)) return false
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val camera = camera(insets?.displayCutout, screenWidth, screenHeight)
        val vertical = IslandPolicy.capsuleVertical(camera?.width() ?: 0, camera?.top ?: 0, camera?.bottom ?: 0,
            dp(1), dp(4), dp(30))
        val y = vertical.top
        val capsuleHeight = vertical.height
        val statusBarBottom = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
        val stack = prefs.getBoolean(IslandPrefs.STACK, false)
        val showSource = prefs.getBoolean(IslandPrefs.SHOW_SOURCE, true)
        val left = prefs.getBoolean(IslandPrefs.LEFT, true)
        val right = prefs.getBoolean(IslandPrefs.RIGHT, true)
        val capWidth = dp(prefs.getInt(IslandPrefs.WIDTH, IslandPrefs.DEFAULT_WIDTH).coerceIn(128, 250))
        val mainWidth = (capWidth + if (expanded) dp(12) else 0).coerceAtMost(screenWidth - dp(16))
        val targetCenter = if (camera == null) screenWidth / 2 else if (camera.centerX() < screenWidth / 3)
            camera.left + mainWidth / 2 - dp(12) else camera.centerX()
        val x = (targetCenter - mainWidth / 2).coerceIn(dp(8), screenWidth - mainWidth - dp(8))
        val cameraIn = camera?.let { rect -> Rect(rect).apply {
            inset(-dp(3), 0)
            offset(-x, -y)
        }.takeIf { it.intersect(0, 0, mainWidth, capsuleHeight) } }
        val glass = expanded && prefs.getBoolean(IslandPrefs.GLASS, false)
        val cards = IslandCards(context, if (glass) IslandGlass::draw else null)
        val radius = dp(prefs.getInt(IslandPrefs.CORNER, 24).coerceIn(14, 36)).toFloat()
        val opacity = prefs.getInt(IslandPrefs.OPACITY, 62).coerceIn(20, 88)
        val shown = if (expanded && stack) items else if (!expanded && !primary.urgent) items.take(3) else listOf(primary)
        val key = listOf(shown.map { item -> "${item.id}:${item.changedAt}:${item.title}:${item.detail}:${System.identityHashCode(item.lyrics)}" }, expanded,
            x, y, screenWidth, screenHeight, cameraIn, capsuleHeight, statusBarBottom, stack, left, right, glass, opacity, radius, showSource,
            context.resources.configuration.fontScale).toString()
        if (key == displayKey) return true
        val previouslyExpanded = wasExpanded
        wasExpanded = expanded
        displayKey = key
        val wanted = mutableSetOf("main")
        val main = materialSurface(dp(30).toFloat(), opacity, glass, solidBlack = !expanded)
        cards.capsule(main, primary, cameraIn, capsuleHeight)
        configureGesture(main.getChildAt(0), side = false) { callbacks.toggle() }
        if (!place("main", main, mainWidth, x, y, false)) return false
        if (expanded) {
            val cardWidth = (screenWidth - dp(32)).coerceAtMost(dp(476))
            val cardY = maxOf(statusBarBottom, y + capsuleHeight) + dp(8)
            val cardX = (screenWidth - cardWidth) / 2
            val body = IslandCardScroll(context, (screenHeight - cardY - dp(20)).coerceAtLeast(dp(64)),
                (callbacks::collapseCard).takeUnless { stack }) {
                if (!stack) callbacks.page(it)
            }.apply { isFillViewport = false; isVerticalScrollBarEnabled = stack }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            (if (stack) items else listOf(primary)).forEachIndexed { index, activity ->
                val panel = materialSurface(if (stack) radius * .85f else radius, opacity, glass)
                panel.addView(cards.card(activity, activity.packageName.takeIf { showSource && it.isNotEmpty() }?.let(::label),
                    callbacks::interacted, ::toast))
                if (stack && index != 0) panel.setOnClickListener { callbacks.select(activity.id) }
                column.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { if (index != 0) topMargin = dp(6) })
            }
            body.addView(column)
            if (!place("card", body, cardWidth, cardX, cardY, animation && !previouslyExpanded, outside = true)) return false
            wanted.add("card")
        }
        if (!expanded && !primary.urgent) {
            val sides = buildList { if (left) add(-1); if (right) add(1) }
            shown.drop(1).zip(sides).forEach { (activity, side) ->
                val width = dp(74)
                val sx = if (side < 0) x - width - dp(6) else x + mainWidth + dp(6)
                if (sx >= dp(3) && sx + width <= screenWidth - dp(3)) {
                    val name = if (side < 0) "left" else "right"
                    val surface = materialSurface(dp(20).toFloat(), opacity, glass = false, solidBlack = true)
                    cards.capsule(surface, activity, null, capsuleHeight, glyph = false)
                    configureGesture(surface, side = true) { callbacks.select(activity.id) }
                    if (place(name, surface, width, sx, y, false)) wanted.add(name)
                }
            }
        }
        (slots.keys - wanted).forEach(::remove)
        return true
    }

    fun isReady() = ready
    fun layoutReady() = slots["main"]?.root?.let { it.isLaidOut && it.isAttachedToWindow } == true

    fun reveal() {
        if (ready) return
        ready = layoutReady()
        if (ready) slots.values.forEach(::enable)
    }

    fun clear() {
        ready = false
        displayKey = null
        wasExpanded = false
        touchHandler.removeCallbacks(collapseFromOutside)
        slots.keys.toList().forEach(::remove)
    }

    private fun materialSurface(radius: Float, opacity: Int, glass: Boolean, solidBlack: Boolean = false): IslandSurface =
        IslandSurface(context, radius, opacity, if (glass) IslandGlass::draw else null, solidBlack)

    private fun enable(slot: Slot) {
        slot.params.flags = slot.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(slot.root, slot.params)
        slot.root.alpha = 1f
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun place(name: String, content: View, width: Int, x: Int, y: Int, animate: Boolean, outside: Boolean = false): Boolean {
        slots[name]?.let { slot ->
            return runCatching {
                slot.root.removeAllViews()
                slot.root.addView(content, FrameLayout.LayoutParams(-1, -2))
                slot.params.width = width; slot.params.x = x; slot.params.y = y
                wm.updateViewLayout(slot.root, slot.params)
                if (animate) animate(content)
            }.isSuccess
        }
        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHandler.removeCallbacks(collapseFromOutside)
                if (outside && event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (!touchesIsland(event.rawX, event.rawY)) {
                        touchHandler.removeCallbacks(collapseFromOutside)
                        touchHandler.postDelayed(collapseFromOutside, 45L)
                    }
                    return false
                }
                if (outside && event.actionMasked == MotionEvent.ACTION_DOWN) callbacks.cardTouchChanged(true)
                val handled = super.dispatchTouchEvent(event)
                if (outside && (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL ||
                        (!handled && event.actionMasked == MotionEvent.ACTION_DOWN))) callbacks.cardTouchChanged(false)
                return handled
            }
        }.apply { addView(content, FrameLayout.LayoutParams(-1, -2)) }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            (if (outside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)
        val lp = WindowManager.LayoutParams(width, -2, 2017, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "DreamGlass Island"
        }
        return runCatching {
            root.alpha = 0f
            wm.addView(root, lp)
            slots[name] = Slot(root, lp)
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (r <= l || b <= t || slots[name]?.root !== root) return
                    v.removeOnLayoutChangeListener(this)
                    root.post {
                        if (slots[name]?.root !== root || !root.isAttachedToWindow) return@post
                        val completed = runCatching {
                            if (ready) enable(Slot(root, lp)) else if (name == "main") callbacks.attached()
                            if (animate && root.alpha == 1f) animate(content)
                        }.isSuccess
                        if (!completed) { clear(); callbacks.attached() }
                    }
                }
            })
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    callbacks.surfaceRemoved(v)
                    if (slots[name]?.root === root) { slots.remove(name); callbacks.attached() }
                }
            })
        }.isSuccess
    }

    private fun touchesIsland(x: Float, y: Float): Boolean = slots.values.any { slot ->
        slot.root.getLocationOnScreen(touchLocation)
        x >= touchLocation[0] && x < touchLocation[0] + slot.root.width &&
            y >= touchLocation[1] && y < touchLocation[1] + slot.root.height
    }

    private fun animate(content: View) {
        content.scaleY = .96f
        content.pivotY = 0f
        content.animate().scaleY(1f).setDuration(prefs.getInt(IslandPrefs.ANIMATION, 220)
            .coerceIn(0, 500).toLong()).start()
    }

    private fun remove(id: String) {
        if (id == "card") touchHandler.removeCallbacks(collapseFromOutside)
        slots.remove(id)?.let(::dispose)
    }
    private fun dispose(slot: Slot) {
        slot.root.animate().cancel()
        runCatching { wm.removeViewImmediate(slot.root) }
        callbacks.surfaceRemoved(slot.root)
    }

    private fun camera(cutout: DisplayCutout?, width: Int, height: Int): Rect? {
        val overrideSize = prefs.getInt(IslandPrefs.CAMERA_SIZE, 0)
        if (overrideSize > 0) {
            val x = (width * prefs.getInt(IslandPrefs.CAMERA_X, 50).coerceIn(1, 99) / 100)
            val y = dp(prefs.getInt(IslandPrefs.CAMERA_Y, 10).coerceIn(0, 52)).coerceAtMost(height / 5)
            val radius = dp(overrideSize.coerceIn(6, 35)) / 2
            return Rect((x - radius).coerceAtLeast(0), (y - radius).coerceAtLeast(0),
                (x + radius).coerceAtMost(width), y + radius)
        }
        return cutout?.boundingRectTop?.takeUnless(Rect::isEmpty)
    }

    private fun configureGesture(view: View, side: Boolean, tap: () -> Unit) {
        view.setOnClickListener { tap() }
        if (side) view.setOnLongClickListener { callbacks.restore(); true }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var x = 0f
        var y = 0f
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - x) > slop || abs(event.rawY - y) > slop) target.cancelLongPress()
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - x
                    val dy = event.rawY - y
                    when {
                        abs(dx) > slop * 3 && abs(dx) > abs(dy) * 1.5f -> {
                            if (side) callbacks.page(if (dx < 0) 1 else -1) else callbacks.dismiss()
                            true
                        }
                        dy < -slop * 3 && !side -> { callbacks.restore(); true }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    private fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    private fun toast(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}
