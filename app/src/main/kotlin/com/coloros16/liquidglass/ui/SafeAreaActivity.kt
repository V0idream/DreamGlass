package com.coloros16.liquidglass.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.config.SafeAreas
import com.coloros16.liquidglass.config.SafeAreaWallpaper
import com.coloros16.liquidglass.root.RootGrant
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SafeAreaActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var editor: Editor
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var pick: Button
    private lateinit var rootRead: Button
    private lateinit var scene: SafeAreas.Scene
    private var loadGeneration = 0
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load(uri)
    }
    private fun prefs() = runCatching { App.serviceRef?.let { Prefs.remote(it) } }.getOrNull()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        scene = if (intent.getBooleanExtra("lock", false)) SafeAreas.Scene.LOCK else SafeAreas.Scene.DESKTOP
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(rows) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(14), bars.top + dp(4), bars.right + dp(14), bars.bottom + dp(4))
            insets
        }
        rows.addView(TextView(this).apply { text = scene.title; textSize = 21f; setPadding(dp(5), dp(6), 0, dp(4)) })
        status = TextView(this).apply { textSize = 13f; setPadding(dp(5), 0, dp(5), dp(5)) }
        rows.addView(status)
        val screen = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds else null
        val dm = resources.displayMetrics
        editor = Editor(this, (screen?.width() ?: dm.widthPixels).toFloat() / (screen?.height() ?: dm.heightPixels))
        val stored = savedInstanceState?.getString("draft") ?: runCatching { prefs()?.getString(scene.key, null) }.getOrNull()
        editor.regions.addAll(SafeAreas.decode(stored))
        rows.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        val commands = LinearLayout(this)
        commands.addView(button("撤销") { if (editor.regions.isNotEmpty()) { editor.regions.removeAt(editor.regions.lastIndex); editor.invalidate() } }, weight())
        commands.addView(button("清空") { editor.regions.clear(); editor.invalidate() }, weight())
        save = button("保存") {
            val value = SafeAreas.encode(editor.regions)
            if (runCatching { prefs()?.edit()?.putString(scene.key, value)?.commit() }.getOrDefault(false) == true) {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            } else Toast.makeText(this, "暂时无法连接 LSPosed，请检查模块状态后重试。", Toast.LENGTH_LONG).show()
        }
        save.isEnabled = false
        commands.addView(save, weight())
        rows.addView(commands)
        val alternatives = LinearLayout(this)
        rootRead = button("申请 Root 权限") { load(requestRoot = true) }.apply { visibility = View.GONE }
        pick = button("选择壁纸图片") { picker.launch("image/*") }.apply { visibility = View.GONE }
        alternatives.addView(rootRead, weight()); alternatives.addView(pick, weight())
        rows.addView(alternatives)
        setContentView(rows)
        load()
    }

    private fun load(uri: Uri? = null, requestRoot: Boolean = false) {
        val token = ++loadGeneration
        status.text = if (requestRoot) "正在检查 Root 授权，请留意管理器提示…" else "正在读取壁纸…"
        rootRead.isEnabled = false
        pick.isEnabled = false
        worker.execute {
            val root = if (uri == null && (requestRoot || RootGrant.canTryAutomatically(this))) {
                runCatching { rootWallpaper(requestRoot) }.getOrElse { RootPreview(false, null) }
                    .also { RootGrant.remember(this, it.granted) }
            } else null
            val image = if (uri != null) runCatching { decode { contentResolver.openInputStream(uri) } }.getOrNull()
                else root?.image ?: runCatching { systemWallpaper() }.getOrNull()
            val authorized = root?.granted ?: RootGrant.previouslyVerified(this)
            val explanation = when {
                requestRoot && !authorized && RootGrant.managerNeedsPregrant() ->
                    "请在 Magisk 的「超级用户」中授权 DreamGlass，然后点此重试。"
                requestRoot && !authorized -> "未取得 Root 权限。请先确认管理器已授权 DreamGlass，再重试。"
                root?.granted == true && root.image == null && image == null ->
                    "已取得 Root 权限，但当前壁纸没有可读取的静态图像。可以选取对应的壁纸画面。"
                else -> null
            }
            runOnUiThread {
                if (isDestroyed || token != loadGeneration) return@runOnUiThread
                if (image != null) editor.image = image
                val ready = editor.image != null
                status.text = explanation ?: if (ready)
                    "沿壁纸圈画要保护的范围，松手闭合。可画多个区域，区域内只关闭扭曲。"
                else "系统未提供此壁纸。可以申请 Root 权限，或选取对应的壁纸画面。"
                rootRead.text = if (authorized) "重新读取系统壁纸" else "申请 Root 权限"
                rootRead.visibility = if (!authorized || !ready) View.VISIBLE else View.GONE
                pick.visibility = if (ready) View.GONE else View.VISIBLE
                rootRead.isEnabled = true
                pick.isEnabled = true
                save.isEnabled = ready || editor.regions.isNotEmpty()
                editor.invalidate()
            }
        }
    }

    private fun systemWallpaper(): Bitmap? {
        val fd = runCatching { SafeAreaWallpaper.read(this, scene) }.getOrNull() ?:
            prefs()?.let { SafeAreaWallpaper.fromHost(it, scene) } ?: return null
        fd.use {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
            val size = max(options.outWidth, options.outHeight)
            if (size <= 0) return null
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize(size)
            return BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
        }
    }

    private data class RootPreview(val granted: Boolean, val image: Bitmap?)
    private fun rootWallpaper(requestRoot: Boolean): RootPreview {
        val wm = WallpaperManager.getInstance(this)
        val user = android.os.Process.myUid() / 100_000
        val dir = "/data/system/users/$user"
        val file = runCatching {
            if (scene == SafeAreas.Scene.LOCK && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0) "$dir/wallpaper_lock"
            else if (wm.wallpaperInfo == null) "$dir/wallpaper" else null
        }.getOrNull()
        // Use one su request for both authorization and the selected scene. The path is
        // derived only from the app's own user ID and fixed system wallpaper filenames.
        val read = if (file == null) ":" else "cat $file 2>/dev/null"
        val command = "[ \"\$(id -u)\" = 0 ] || exit 13; echo DGROOT; $read"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        var granted = false
        val stop = java.util.Timer(true)
        try {
            stop.schedule(object : java.util.TimerTask() {
                override fun run() { process.destroyForcibly() }
            }, if (requestRoot) 40_000L else 2_500L)
            val bytes = process.inputStream.use { input ->
                val marker = "DGROOT\n".toByteArray(Charsets.US_ASCII)
                if (!input.readNBytes(marker.size).contentEquals(marker)) return RootPreview(false, null)
                granted = true
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 24 * 1024 * 1024) return RootPreview(true, null)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            val image = if (process.waitFor() == 0 && bytes.isNotEmpty()) decode { bytes.inputStream() } else null
            return RootPreview(true, image)
        } catch (_: java.io.IOException) {
            return RootPreview(granted, null)
        } finally {
            stop.cancel()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun decode(open: () -> java.io.InputStream?): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = open() ?: return null
        input.use { BitmapFactory.decodeStream(it, null, options) }
        val longest = max(options.outWidth, options.outHeight)
        if (longest <= 0) return null
        return open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize(longest) }) }
    }
    private fun sampleSize(longest: Int): Int {
        var sample = 1
        while (longest / sample > 1800) sample *= 2
        return sample
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; setOnClickListener { click() } }
    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", SafeAreas.encode(editor.regions))
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private class Editor(context: Context, private val aspect: Float) : View(context) {
        var image: Bitmap? = null
        val regions = mutableListOf<List<SafeAreas.Point>>()
        private var draft: MutableList<SafeAreas.Point>? = null
        private val viewport = RectF()
        private val destination = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5553DCC8 }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }
        init { contentDescription = "壁纸预览，手指圈画安全区域"; isClickable = true }
        override fun onDraw(canvas: Canvas) {
            val photo = image ?: return
            val w = min(width.toFloat(), height * aspect)
            val h = w / aspect
            if (w <= 0f || h <= 0f) return
            viewport.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            val scale = max(w / photo.width, h / photo.height)
            val halfW = photo.width * scale / 2; val halfH = photo.height * scale / 2
            destination.set(viewport.centerX() - halfW, viewport.centerY() - halfH, viewport.centerX() + halfW, viewport.centerY() + halfH)
            canvas.save()
            canvas.clipRect(viewport)
            canvas.drawBitmap(photo, null, destination, paint)
            for (region in regions) drawRegion(canvas, region)
            draft?.let { drawRegion(canvas, it) }
            canvas.restore()
        }
        private fun drawRegion(canvas: Canvas, points: List<SafeAreas.Point>) {
            val path = SafeAreas.path(points, viewport.left, viewport.top, viewport.width(), viewport.height())
            canvas.drawPath(path, fill); canvas.drawPath(path, outline)
        }
        override fun performClick(): Boolean = super.performClick()
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (image == null || viewport.isEmpty) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!viewport.contains(event.x, event.y)) return false
                if (regions.size >= SafeAreas.MAX_REGIONS) {
                    Toast.makeText(context, "最多可圈画 ${SafeAreas.MAX_REGIONS} 个区域", Toast.LENGTH_SHORT).show()
                    return true
                }
                draft = mutableListOf()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            val points = draft ?: return false
            fun append(x: Float, y: Float) {
                val p = SafeAreas.Point(((x - viewport.left) / viewport.width()).coerceIn(0f, 1f),
                    ((y - viewport.top) / viewport.height()).coerceIn(0f, 1f))
                val last = points.lastOrNull()
                if (last != null && abs(last.x - p.x) + abs(last.y - p.y) < .004f) return
                if (points.size >= SafeAreas.MAX_POINTS) {
                    // Leave room to finish long strokes, retaining both ends of the contour.
                    val reduced = points.filterIndexed { i, _ -> i % 2 == 0 }
                    points.clear(); points.addAll(reduced)
                }
                points.add(p)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    for (i in 0 until event.historySize) append(event.getHistoricalX(i), event.getHistoricalY(i))
                    append(event.x, event.y)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (points.size >= 3 && points.maxOf { it.x } - points.minOf { it.x } >= .012f &&
                            points.maxOf { it.y } - points.minOf { it.y } >= .012f) {
                            regions.add(points.toList())
                        } else performClick()
                        draft = null
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> draft = null
            }
            invalidate()
            return true
        }
    }
}
