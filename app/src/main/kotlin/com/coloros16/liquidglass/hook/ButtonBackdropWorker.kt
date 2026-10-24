package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One shared button backdrop, at most one running and one waiting job. No UI-thread pixel reads.
 * Published bitmaps are immutable and never recycled while a shader may still reference them. */
internal class ButtonBackdropWorker(private val invalidate: () -> Unit, private val failed: (Throwable) -> Unit) {
    private data class Key(val sourceId: Int, val generation: Int, val width: Int, val height: Int,
        val left: Float, val top: Float, val screenWidth: Float, val screenHeight: Float, val radius: Float)
    private data class Frame(val key: Key, val bitmap: Bitmap)
    private val worker = ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "DreamGlass-button-blur").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var requested: Key? = null
    @Volatile private var ready: Frame? = null
    @Volatile private var retryAtMs = 0L

    fun resolve(source: Bitmap, screen: RectF, radius: Float): Bitmap? {
        if (source.isRecycled || screen.width() <= 0 || screen.height() <= 0 || radius <= 0) return null
        if (SystemClock.uptimeMillis() < retryAtMs) return null
        val key = Key(System.identityHashCode(source), source.generationId, source.width, source.height,
            screen.left, screen.top, screen.width(), screen.height(), radius)
        if (requested != key) {
            requested = key
            worker.execute {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    var small = source
                    if (source.config == Bitmap.Config.HARDWARE) small = source.copy(Bitmap.Config.ARGB_8888, false)
                    // Successive halves preserve fine detail better than a single large downsample.
                    while (maxOf(small.width, small.height) > 384) {
                        val next = Bitmap.createScaledBitmap(small, (small.width / 2).coerceAtLeast(1),
                            (small.height / 2).coerceAtLeast(1), true)
                        if (small !== source && small !== next) small.recycle()
                        small = next
                    }
                    val pixels = IntArray(small.width * small.height)
                    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                    val sigma = radius * (small.width / screen.width() + small.height / screen.height()) * .5f
                    val result = ButtonBackdropBlur.apply(pixels, small.width, small.height, sigma)
                    val bitmap = Bitmap.createBitmap(result, small.width, small.height, Bitmap.Config.ARGB_8888)
                    if (small !== source) small.recycle()
                    // A newer capture can be queued while this one runs; publish this completed frame
                    // so a continuously moving background cannot starve the buttons of blurred frames.
                    ready = Frame(key, bitmap)
                    invalidate()
                } catch (t: Throwable) {
                    retryAtMs = SystemClock.uptimeMillis() + 1000L
                    if (requested == key) requested = null
                    failed(t)
                }
            }
        }
        val frame = ready ?: return null
        val old = frame.key
        return frame.bitmap.takeIf { old.width == key.width && old.height == key.height &&
            old.left == key.left && old.top == key.top && old.screenWidth == key.screenWidth &&
            old.screenHeight == key.screenHeight && old.radius == key.radius }
    }
}
