package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap
import java.util.concurrent.CancellationException

/** Copies only the media plugin's artwork layer. The lyrics, controls and display stay outside the recording. */
internal object LockMediaBackdrop {
    private const val PLUGIN = "com.oplus.systemui.plugins"
    private const val LYRICS = "$PLUGIN.shared.template.component.media.view.LyricsRecyclerView"
    private const val ARTWORK = "$PLUGIN.shared.template.component.media.view.MediaFullScreenContentView"
    private const val MIN_FRAME_INTERVAL_MS = 100L

    data class Frame(val bitmap: Bitmap, val region: Rect)

    private val ui = Handler(Looper.getMainLooper())
    private val worker = RuntimeWorkPolicy.executor("DreamGlass-lock-media")
    private val lyrics = WeakHashMap<View, Watch>()
    private val backgrounds = WeakHashMap<View, Watch>()
    private val visible = Rect()
    private val origin = IntArray(2)
    @Volatile var active = false
        private set
    @Volatile var frame: Frame? = null
        private set
    @Volatile private var generation = 0
    private var loading = false
    private var stamp: List<Int>? = null
    private var retryAt = 0L
    private var lastRequestAt = -MIN_FRAME_INTERVAL_MS
    @Volatile private var installed = false
    private var locked: () -> Boolean = { false }
    private var enabled: () -> Boolean = { false }
    private var invalidate: () -> Unit = {}

    fun install(api: XposedInterface, locked: () -> Boolean, enabled: () -> Boolean, invalidate: () -> Unit) {
        if (installed) return
        this.locked = locked
        this.enabled = enabled
        this.invalidate = invalidate
        api.hook(View::class.java.getDeclaredMethod("onAttachedToWindow"))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                if (installed && Looper.myLooper() == Looper.getMainLooper()) {
                    val view = chain.getThisObject() as? View
                    if (view != null) RuntimeWorkPolicy.guarded(::report) { register(view) }
                }
                result
            }
        installed = true
    }

    fun shutdown() {
        installed = false
        ui.post {
            active = false
            clearFrame()
            lyrics.toMap().forEach { (view, watch) -> watch.unwatch(view) }
            backgrounds.toMap().forEach { (view, watch) -> watch.unwatch(view) }
            invalidate = {}
        }
    }

    private fun register(view: View) {
        val targets = when {
            view.javaClass.name == LYRICS -> lyrics
            view.javaClass == FrameLayout::class.java && view.id != View.NO_ID &&
                runCatching { view.resources.getResourceName(view.id) }.getOrNull() ==
                "$PLUGIN:id/immersive_bg_host" -> backgrounds
            else -> return
        }
        if (targets.containsKey(view)) return
        val watch = Watch(view, targets)
        targets[view] = watch
        view.addOnAttachStateChangeListener(watch)
        view.viewTreeObserver.addOnPreDrawListener(watch)
    }

    private class Watch(view: View, private val targets: WeakHashMap<View, Watch>) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val observer = view.viewTreeObserver
        override fun onPreDraw(): Boolean {
            RuntimeWorkPolicy.guarded(::report, ::update)
            return true
        }
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            unwatch(view)
            RuntimeWorkPolicy.guarded(::report, ::update)
        }
        fun unwatch(view: View) {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            view.removeOnAttachStateChangeListener(this)
            targets.remove(view)
        }
    }

    private fun onScreen(view: View): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
            !view.getGlobalVisibleRect(visible)) return false
        var current: View? = view
        while (current != null) {
            if (current.alpha <= .01f) return false
            current = current.parent as? View
        }
        return true
    }

    private fun artwork(host: ViewGroup): ViewGroup? {
        val id = host.resources.getIdentifier("contentContainer", "id", PLUGIN)
        return if (id == 0) null else host.findViewById<ViewGroup>(id)
            ?.takeIf { it.javaClass.name == ARTWORK && onScreen(it) }
    }

    private fun update() {
        if (!installed) return
        val lyric = if (locked()) lyrics.keys.firstOrNull(::onScreen) else null
        val inMedia = lyric != null
        if (inMedia != active) {
            active = inMedia
            clearFrame()
            RuntimeDiagnostics.update("lock_media_backdrop", if (active) "沉浸歌词可见，等待媒体背景" else "已退出沉浸歌词")
            invalidate()
        }
        if (lyric == null) return
        val display = lyric.display
        if (display?.state != Display.STATE_ON || !enabled()) {
            if (clearFrame()) invalidate()
            return
        }
        val now = SystemClock.uptimeMillis()
        val background = backgrounds.keys.firstNotNullOfOrNull { host ->
            if (host.display?.displayId == display.displayId && onScreen(host)) artwork(host as ViewGroup)
            else null
        }
        if (background == null) {
            if (clearFrame()) invalidate()
            return
        }
        if (loading || now < retryAt || now - lastRequestAt < MIN_FRAME_INTERVAL_MS) return
        val width = background.width
        val height = background.height
        if (!background.isHardwareAccelerated || width <= 0 || height <= 0) return
        background.getLocationOnScreen(origin)
        val region = Rect(origin[0], origin[1], origin[0] + width, origin[1] + height)
        val nextStamp = ArrayList<Int>(5 + background.childCount * 3)
        nextStamp.addAll(listOf(System.identityHashCode(background), region.left, region.top, width, height))
        var hasArtwork = false
        for (index in 0 until background.childCount) {
            val drawable = (background.getChildAt(index) as? ImageView)?.drawable ?: continue
            hasArtwork = true
            nextStamp.add(System.identityHashCode(drawable))
            nextStamp.add(drawable.alpha)
            nextStamp.add((drawable as? BitmapDrawable)?.bitmap?.generationId ?: 0)
        }
        if (!hasArtwork) {
            if (clearFrame()) invalidate()
            return
        }
        if (nextStamp == stamp) return

        val node = RenderNode("DreamGlass lock media artwork")
        node.setPosition(0, 0, width, height)
        try {
            val canvas = node.beginRecording(width, height)
            try { background.draw(canvas) } finally { node.endRecording() }
        } catch (error: Throwable) {
            node.discardDisplayList()
            report(error)
            return
        }
        loading = true
        lastRequestAt = now
        val version = generation
        val scale = minOf(1f, 768f / maxOf(width, height))
        try {
            worker.execute {
                var result: Bitmap? = null
                var failure: Throwable? = null
                try {
                    result = HardwareBackdropBlur().use {
                        it.render(node, width, height, (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1), 0f) { version != generation }
                    }
                } catch (_: CancellationException) {
                    // The owning scene was closed while the GPU was working.
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    node.discardDisplayList()
                    ui.post {
                        loading = false
                        if (version == generation && installed && active) {
                            if (result != null) {
                                frame = Frame(result, region)
                                if (stamp == null) RuntimeDiagnostics.update("lock_media_backdrop", "已使用歌词界面的专辑背景")
                                stamp = nextStamp
                            }
                            failure?.let(::report)
                        }
                        invalidate()
                    }
                }
            }
        } catch (error: Throwable) {
            loading = false
            node.discardDisplayList()
            report(error)
        }
    }

    private fun clearFrame(): Boolean {
        generation++
        val hadFrame = frame != null
        frame = null
        stamp = null
        retryAt = 0L
        return hadFrame
    }

    private fun report(error: Throwable) {
        retryAt = SystemClock.uptimeMillis() + RuntimeWorkPolicy.failureDelayMs(error)
        RuntimeDiagnostics.update("lock_media_backdrop", "保留系统媒体背景：${error.javaClass.simpleName}")
    }
}
