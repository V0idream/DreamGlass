package com.coloros16.liquidglass.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

internal fun Context.islandDp(dp: Int) = (dp * resources.displayMetrics.density + .5f).toInt()
internal typealias IslandMaterial = (Canvas, View, Float) -> Boolean

/** The content and controls are shared between the desktop overlay and the labeled settings preview. */
internal class IslandSurface(
    context: Context,
    private val radius: Float,
    private val opacity: Int,
    private val material: IslandMaterial? = null,
    private val solidBlack: Boolean = false,
) : LinearLayout(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (solidBlack) {
            fill.style = Paint.Style.FILL
            fill.color = Color.BLACK
            val pill = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), pill, pill, fill)
            return
        }
        val glass = material?.invoke(canvas, this, radius) == true
        fill.color = if (glass) Color.argb(opacity.coerceIn(20, 88), 12, 20, 30) else 0xff151b24.toInt()
        fill.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)
        fill.color = if (glass) 0x70e6f6ff else 0x33b6d4e5
        fill.strokeWidth = resources.displayMetrics.density
        fill.style = Paint.Style.STROKE
        val inset = fill.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, fill)
    }
}

internal class IslandCards(private val context: Context, private val controlMaterial: IslandMaterial? = null) {
    private fun dp(value: Int) = context.islandDp(value)

    fun label(value: String, size: Float, strong: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (strong) Color.WHITE else 0xffcbd9e8.toInt())
        if (strong) setTypeface(typeface, Typeface.BOLD)
    }

    fun capsule(surface: IslandSurface, activity: IslandActivity, camera: Rect?, height: Int, glyph: Boolean = true) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        fun title(): View = activity.clockBase?.let { time ->
            Chronometer(context).apply {
                base = time
                isCountDown = activity.countdown
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = start()
                    override fun onViewDetachedFromWindow(v: View) = stop()
                })
            }
        } ?: if (activity.lyrics != null && activity.media != null) IslandLyricText(context, activity, compact = true)
        else label(activity.title, 13f, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }
        val glyphSize = minOf(dp(20), height - dp(3)).coerceAtLeast(1)
        val slot = camera?.width()?.takeIf { it > 0 }
        if (slot != null) {
            val leftWidth = (camera.left - dp(10)).coerceAtLeast(0)
            val leading = LinearLayout(context).apply { gravity = Gravity.CENTER }
            if (glyph && leftWidth >= glyphSize) addIcon(leading, activity, glyphSize)
            line.addView(leading, LinearLayout.LayoutParams(leftWidth, height))
            line.addView(View(context), LinearLayout.LayoutParams(slot, 1))
        } else if (glyph) addIcon(line, activity, glyphSize)
        val titleView = title()
        if (titleView is TextView) {
            titleView.includeFontPadding = false
            titleView.maxLines = 1
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(titleView.textSize, height * .55f))
        }
        line.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        if (activity.kind == IslandKind.MEDIA && activity.media?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            line.addView(MediaWave(context), LinearLayout.LayoutParams(dp(17), minOf(dp(20), height)))
        }
        surface.addView(line, LinearLayout.LayoutParams(-1, height))
    }

    fun card(activity: IslandActivity, source: String?, interacted: () -> Unit, failed: (String) -> Unit): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(13))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        addIcon(header, activity, dp(44))
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        val title = label(activity.title, 17f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heading.addView(title)
        if (!activity.detail.isBlank()) heading.addView(label(activity.detail, 13f).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        })
        if (source != null) heading.addView(label(source, 11f))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        activity.open?.let { open ->
            header.isFocusable = true
            header.setOnClickListener { perform(header, interacted, failed, open) }
        }
        column.addView(header)
        if (activity.clockBase != null) {
            column.addView(Chronometer(context).apply {
                base = activity.clockBase
                isCountDown = activity.countdown
                typeface = Typeface.MONOSPACE
                textSize = 20f
                setTextColor(Color.WHITE)
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = start()
                    override fun onViewDetachedFromWindow(v: View) = stop()
                })
            })
        }
        activity.segmented?.let {
            column.addView(SegmentedProgressView(context, it), LinearLayout.LayoutParams(-1, dp(20)))
        } ?: activity.progress?.let {
            column.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = activity.progressMax
                progress = it
                contentDescription = "进度"
            }, LinearLayout.LayoutParams(-1, dp(12)))
        }
        if (activity.media != null) {
            if (activity.lyrics != null) column.addView(IslandLyricText(context, activity, compact = false))
            val duration = activity.media.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
            if (duration > 0) column.addView(MediaProgress(context, activity, duration, interacted, failed),
                LinearLayout.LayoutParams(-1, dp(36)))
        }
        fun control(action: IslandAction) = GlassIslandControl(context, action, controlMaterial).apply {
            setOnClickListener { perform(this, interacted, failed, action.run) }
        }
        val media = activity.actions.filter { it.glyph != null }
        if (media.isNotEmpty()) {
            val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
            val central = media.firstOrNull { it.glyph == MediaGlyph.PLAY || it.glyph == MediaGlyph.PAUSE }
            if (central == null) media.forEach { controls.addView(control(it), iconLayout(48)) }
            else {
                for (role in listOf(MediaGlyph.LIKE, MediaGlyph.PREVIOUS, central.glyph, MediaGlyph.NEXT)) {
                    val option = if (role == MediaGlyph.LIKE)
                        media.firstOrNull { it.glyph == MediaGlyph.LIKE || it.glyph == MediaGlyph.UNLIKE }
                    else media.firstOrNull { it.glyph == role }
                    if (role == MediaGlyph.LIKE && option == null) continue
                    val size = if (option === central) 56 else 48
                    controls.addView(option?.let(::control) ?: View(context), iconLayout(size))
                }
            }
            column.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }
        activity.actions.filter { it.glyph == null }.chunked(2).forEach { group ->
            val controls = LinearLayout(context)
            group.forEach { action ->
                controls.addView(control(action), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = dp(8); marginEnd = dp(5)
                })
            }
            column.addView(controls)
        }
        return column
    }

    private fun iconLayout(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun addIcon(line: LinearLayout, activity: IslandActivity, size: Int) {
        val art = activity.artwork
        val icon = activity.icon
        if (art != null || icon != null) {
            line.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xff283342.toInt()) }
                if (art != null) setImageBitmap(art) else setImageDrawable(icon?.constantState?.newDrawable()?.mutate() ?: icon)
            }, LinearLayout.LayoutParams(size, size))
        }
    }

    private fun perform(view: View, interacted: () -> Unit, failed: (String) -> Unit, action: () -> Unit) {
        interacted()
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (runCatching(action).isFailure) failed("此操作已不可用")
    }
}

private class SegmentedProgressView(context: Context, private val progress: IslandProgress) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "进度 ${progress.position} / ${progress.max}"
    }
    override fun onDraw(canvas: Canvas) {
        val pad = context.islandDp(4).toFloat()
        val trackWidth = (width - pad * 2).coerceAtLeast(0f)
        val mid = height / 2f
        val thickness = pad * .82f
        fun point(offset: Int) = pad + trackWidth * offset.toFloat() / progress.max
        paint.color = 0xff475564.toInt()
        canvas.drawRoundRect(pad, mid - thickness, pad + trackWidth, mid + thickness, thickness, thickness, paint)
        var from = 0
        val segments = progress.segments.ifEmpty { listOf(IslandProgressSegment(progress.max, 0xffa2e0cf.toInt())) }
        for (segment in segments) {
            val end = (from.toLong() + segment.length).coerceIn(from.toLong(), progress.max.toLong()).toInt()
            val activeLast = minOf(end, progress.position)
            if (activeLast > from) {
                paint.color = (if (Color.alpha(segment.color) == 0) 0xffa2e0cf.toInt() else segment.color)
                val right = (point(activeLast) - 1f).coerceAtLeast(point(from))
                canvas.drawRoundRect(point(from), mid - thickness, right, mid + thickness, thickness, thickness, paint)
            }
            from = end
        }
        for (marker in progress.points) {
            paint.color = if (Color.alpha(marker.color) == 0) 0xffb8e0f2.toInt() else marker.color
            canvas.drawCircle(point(marker.position), mid, thickness * 1.05f, paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(point(progress.position), mid, thickness * 1.32f, paint)
    }
}

@android.annotation.SuppressLint("AppCompatCustomView") // Inflated under the host SystemUI theme.
private class IslandLyricText(context: Context, activity: IslandActivity, private val compact: Boolean) : TextView(context) {
    private val controller = requireNotNull(activity.media)
    private val lyrics = requireNotNull(activity.lyrics)
    private val track = activity.title
    private var lastLine = Int.MIN_VALUE
    private val refresh = object : Runnable {
        override fun run() {
            val state = controller.playbackState
            val index = state?.let { lyrics.index(IslandLyrics.position(it)) } ?: -1
            if (index != lastLine) {
                lastLine = index
                if (compact) text = lyrics.display(index).ifBlank { track }
                else {
                    val lines = (index - 1..index + 1).map(lyrics::display).filter(String::isNotEmpty)
                    text = lines.joinToString("\n")
                    visibility = if (lines.isEmpty()) GONE else VISIBLE
                }
            }
            postDelayed(this, 350)
        }
    }
    init {
        setTextColor(Color.WHITE)
        textSize = if (compact) 13f else 14f
        maxLines = if (compact) 1 else 5
        ellipsize = if (compact) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        isSelected = compact
        if (!compact) setPadding(0, context.islandDp(6), 0, context.islandDp(4))
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh.run() }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
}

@android.annotation.SuppressLint("AppCompatCustomView") // Inflated under the host SystemUI theme.
private class MediaProgress(
    context: Context, activity: IslandActivity, private val duration: Long,
    interacted: () -> Unit, failed: (String) -> Unit,
) : SeekBar(context) {
    private val controller = requireNotNull(activity.media)
    private val canSeek = (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
    private var dragging = false
    private val refresh = object : Runnable {
        override fun run() {
            if (!dragging) controller.playbackState?.let { state ->
                progress = (IslandLyrics.position(state).coerceAtMost(duration).toDouble() / duration * max).toInt()
            }
            postDelayed(this, 1000)
        }
    }
    init {
        max = 1000
        isEnabled = canSeek
        contentDescription = "媒体进度"
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) interacted()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true; interacted() }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                if (runCatching { controller.transportControls.seekTo((duration * progress) / max) }.isFailure)
                    failed("跳转失败")
                interacted()
            }
        })
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh.run() }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
}

/** Preserve nested controls/vertical card scrolling until a horizontal page gesture is deliberate. */
internal class IslandCardScroll(
    context: Context, private val heightLimit: Int, private val collapse: (() -> Unit)?,
    private val page: (Int) -> Unit,
) :
    ScrollView(context) {
    private var x = 0f
    private var y = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST))
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { x = ev.x; y = ev.y }
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && abs(ev.x - x) > slop * 2 &&
            abs(ev.x - x) > abs(ev.y - y) * 1.5f) return true
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && collapse != null && upward(ev)) return true
        return super.onInterceptTouchEvent(ev)
    }
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP && abs(ev.x - x) > slop * 3 &&
            abs(ev.x - x) > abs(ev.y - y) * 1.5f) {
            page(if (ev.x < x) 1 else -1)
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP && collapse != null && upward(ev)) {
            collapse()
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun upward(event: MotionEvent): Boolean {
        val distance = y - event.y
        return distance > slop * 3 && distance > abs(event.x - x) * 1.5f && !canScrollVertically(1)
    }
}
