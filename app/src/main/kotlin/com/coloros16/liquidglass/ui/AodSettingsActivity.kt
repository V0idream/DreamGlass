package com.coloros16.liquidglass.ui

import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.config.AodComposition
import com.coloros16.liquidglass.config.AodCrop
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.config.SafeAreaWallpaper
import com.coloros16.liquidglass.config.SafeAreas
import com.coloros16.liquidglass.hook.AodBattery
import com.coloros16.liquidglass.hook.LockTransitionLayer
import com.coloros16.liquidglass.hook.LockTransitionStyle
import com.coloros16.liquidglass.root.RootGrant
import com.coloros16.liquidglass.root.RootWallpaper
import kotlin.math.roundToInt

/** Separate from the island. Device features are configured by the system's own settings. */
class AodSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("aod_preview", MODE_PRIVATE) }
    private lateinit var frame: FrameLayout
    private lateinit var info: TextView
    private var generation = 0
    private var bitmap: Bitmap? = null
    private var aodWallpaper: Bitmap? = null
    private var wallpaperGeneration = 0
    private var realAodWallpaper = false
    private var wallpaperFromRoot = false
    private var requestingRootWallpaper = false
    private var useWallpaperWhenReady: (() -> Unit)? = null
    private var battery: AodBattery? = null
    private lateinit var transitionPreview: LockTransitionLayer
    private lateinit var aodWallpaperInfo: TextView
    private lateinit var cropAction: TextView
    private lateinit var importAction: TextView
    private lateinit var rootWallpaperAction: TextView
    private var previewMotion: ValueAnimator? = null
    private val aodWallpaperEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    battery = AodBattery.read(intent)
                    transitionPreview.battery = battery
                }
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                    transitionPreview.updateAmbientClock()
                Intent.ACTION_WALLPAPER_CHANGED ->
                    if (transitionPreview.style != LockTransitionStyle.SYSTEM) loadAodWallpaper()
            }
        }
    }
    private val pickAodReference = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && rememberReadPermission(uri)) {
            prefs.edit().putString("aod_reference", uri.toString()).apply()
            loadAodWallpaper()
        } else useWallpaperWhenReady = null
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess) {
            prefs.edit().putString("image", uri.toString()).putInt("background", 2).apply()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DreamUi.canvas)
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightNavigationBars = false; isAppearanceLightStatusBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val scroll = ScrollView(this)
        root.addView(scroll)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        scroll.addView(body)
        DreamUi.action(body, "‹  返回") { finish() }
        body.addView(DreamUi.label(this, "息屏与壁纸", 23f, bold = true))
        transitionSettings(body)
        val scenes = group(body, "显示场景")
        val ambient = resources.getIdentifier("config_dozeAlwaysOnDisplayAvailable", "bool", "android")
        val available = ambient != 0 && runCatching { resources.getBoolean(ambient) }.getOrDefault(false)
        scenes.addView(DreamUi.label(this, if (available)
            "系统报告支持常亮显示。充电、低光、全景及轻触唤醒选项由设备设置提供。"
        else "无法确认设备的系统常亮能力。请在系统设置中查看可用的息屏选项。", color = DreamUi.muted))
        DreamUi.action(scenes, "打开系统息屏与锁屏设置", true) { openSystem() }
        val style = group(body, "样式与时钟")
        style.addView(DreamUi.label(this, "亮屏预览。实际息屏的亮度、刷新频率与动态壁纸由系统控制。", color = DreamUi.muted))
        frame = FrameLayout(this).apply {
            minimumHeight = dp(220)
            clipToOutline = true
            background = DreamUi.shape(context, Color.BLACK, 18)
        }
        style.addView(frame, LinearLayout.LayoutParams(-1, dp(220)))
        DreamUi.action(style, "时钟预设") {
            choose("时钟预设", arrayOf("数字时钟", "数字时钟与日期"), prefs.getInt("clock", 0)) {
                prefs.edit().putInt("clock", it).apply(); refresh()
            }
        }
        val bright = group(body, "亮度")
        bright.addView(DreamUi.label(this, "系统亮度调节由息屏驱动控制。下方只调节亮屏预览。", color = DreamUi.muted))
        val level = android.widget.SeekBar(this).apply {
            max = 100; progress = prefs.getInt("preview_brightness", 75)
            contentDescription = "预览亮度"
        }
        level.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    frame.getChildAt(frame.childCount - 1)?.alpha = value.coerceAtLeast(8) / 100f
                    prefs.edit().putInt("preview_brightness", value).apply()
                }
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
        })
        bright.addView(level)
        val wall = group(body, "壁纸")
        info = DreamUi.label(this, "", color = DreamUi.muted)
        wall.addView(info)
        DreamUi.action(wall, "选择亮屏预览背景") {
            choose("预览背景", arrayOf("纯黑", "系统锁屏壁纸", "选择静态图片"), prefs.getInt("background", 0)) {
                if (it == 2) pickImage.launch(arrayOf("image/*")) else {
                    prefs.edit().putInt("background", it).apply(); refresh()
                }
            }
        }
        DreamUi.action(wall, "切换图片调暗程度") {
            choose("背景调暗", arrayOf("轻", "中", "强"), prefs.getInt("dim", 1)) {
                prefs.edit().putInt("dim", it).apply(); refresh()
            }
        }
        DreamUi.action(wall, "打开系统壁纸设置") {
            runCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }.onFailure { openSystem() }
        }
        DreamUi.action(body, "恢复预览默认") {
            AlertDialog.Builder(this, R.style.Theme_IslandDialog).setTitle("恢复预览默认")
                .setMessage("恢复时钟样式、背景、调暗和预览亮度。")
                .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                    prefs.edit().clear().apply(); level.progress = 75; refresh()
                }.showIslandDialog()
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(this, aodWallpaperEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        battery = AodBattery.read(sticky) ?: battery
        transitionPreview.battery = battery
        transitionPreview.updateAmbientClock()
        if (transitionPreview.style != LockTransitionStyle.SYSTEM) loadAodWallpaper()
    }

    override fun onStop() {
        unregisterReceiver(aodWallpaperEvents)
        super.onStop()
    }

    override fun onDestroy() {
        previewMotion?.cancel()
        generation++
        wallpaperGeneration++
        bitmap = null
        aodWallpaper = null
        super.onDestroy()
    }

    private fun remote() = App.serviceRef?.let { runCatching { Prefs.remote(it) }.getOrNull() }

    private fun transitionSettings(body: LinearLayout) {
        val card = group(body, "息屏与亮屏动画")
        val labels = LockTransitionStyle.entries.map { it.title }.toTypedArray()
        val key = Prefs.KEY_AOD_TRANSITION_STYLE
        var selected = LockTransitionStyle.fromKey(remote()?.getString(key, null) ?: prefs.getString(key, null))
        fun compositionKey() = AodComposition.KEY_PREFIX + selected.name
        fun readComposition(): AodComposition {
            val composeKey = compositionKey()
            return AodComposition.decode(prefs.getString(composeKey, null) ?: remote()?.getString(composeKey, null))
        }
        var composition = readComposition()
        val instruction = DreamUi.label(this, "系统开启息屏显示后生效。亮屏时展开为锁屏壁纸，熄屏时反向收起。", color = DreamUi.muted)
        card.addView(instruction)
        transitionPreview = LockTransitionLayer(this, preview = true).apply {
            background = DreamUi.shape(context, Color.BLACK, 18)
            clipToOutline = true
        }
        val metrics = resources.displayMetrics
        val ratio = (minOf(metrics.widthPixels, metrics.heightPixels).toFloat() /
            maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)).coerceIn(.40f, .70f)
        val previewHeight = dp(310)
        val previewSize = LinearLayout.LayoutParams((previewHeight * ratio).toInt(), previewHeight).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
        }
        lateinit var select: TextView
        lateinit var wake: TextView
        lateinit var sleep: TextView
        lateinit var display: TextView
        fun updatePreview() {
            previewMotion?.cancel()
            transitionPreview.style = selected
            transitionPreview.wallpaper = aodWallpaper
            transitionPreview.composition = composition
            transitionPreview.battery = battery
            transitionPreview.progress = 0f
            transitionPreview.updateAmbientClock()
            val custom = selected != LockTransitionStyle.SYSTEM
            val visibility = if (custom) View.VISIBLE else View.GONE
            for (view in listOf(transitionPreview, display, wake, sleep, aodWallpaperInfo, importAction, rootWallpaperAction)) {
                view.visibility = visibility
            }
            updateWallpaperControls()
            select.text = selected.title
            val visible = buildList {
                if (composition.clock) add("时间")
                if (composition.date) add("日期")
                if (composition.battery) add("电量")
            }
            display.text = if (visible.isEmpty()) "显示内容 · 仅图片" else "显示内容 · " + visible.joinToString("、")
        }
        fun saveComposition(value: AodComposition) {
            composition = value
            val encoded = value.encode()
            val composeKey = compositionKey()
            val framework = remote()
            framework?.edit()?.putString(composeKey, encoded)?.apply()
            prefs.edit().putString(composeKey, encoded).apply()
            if (framework == null) savedForPreview()
            updatePreview()
        }
        fun previewTransition(waking: Boolean) {
            val target = if (waking) 1f else 0f
            val from = if (previewMotion?.isRunning == true) transitionPreview.progress else 1f - target
            previewMotion?.cancel()
            transitionPreview.progress = from
            previewMotion = ValueAnimator.ofFloat(from, target).apply {
                duration = selected.durationBetween(from, target)
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { transitionPreview.progress = it.animatedValue as Float }
                start()
            }
        }
        fun selectStyle(index: Int, notify: Boolean = true) {
            selected = LockTransitionStyle.entries[index]
            composition = readComposition()
            val framework = remote()
            framework?.edit()?.putString(key, selected.name)
                ?.putString(compositionKey(), composition.encode())?.apply()
            prefs.edit().putString(key, selected.name).apply()
            if (notify && framework == null) savedForPreview()
            updatePreview()
        }
        fun openCrop() {
            val image = aodWallpaper
            if (image != null) {
                showCropEditor(image, selected, composition, ratio, ::saveComposition)
            } else if (!requestingRootWallpaper) {
                val options = arrayOf("通过 Root 读取锁屏壁纸", "重新读取系统锁屏壁纸", "选取同一张壁纸供预览")
                AlertDialog.Builder(this, R.style.Theme_IslandDialog)
                    .setTitle("读取锁屏壁纸")
                    .setItems(options) { _, choice ->
                        useWallpaperWhenReady = {
                            aodWallpaper?.let { showCropEditor(it, selected, composition, ratio, ::saveComposition) }
                        }
                        when (choice) {
                            0 -> loadAodWallpaper(requestRoot = true)
                            1 -> loadAodWallpaper()
                            2 -> pickAodReference.launch(arrayOf("image/*"))
                        }
                    }.setNegativeButton("取消", null).showIslandDialog()
            }
        }
        select = DreamUi.action(card, selected.title, true) {
            choose("息屏样式", labels, selected.ordinal) { index ->
                selectStyle(index)
                if (selected != LockTransitionStyle.SYSTEM && aodWallpaper == null) loadAodWallpaper()
            }
        }
        cropAction = DreamUi.action(card, "选择息屏壁纸显示区域") {
            if (selected == LockTransitionStyle.SYSTEM) {
                choose("为哪种息屏样式设置显示区域", labels.drop(1).toTypedArray(), -1) { index ->
                    selectStyle(index + 1, notify = false)
                    openCrop()
                }
            } else openCrop()
        }
        val motionActions = LinearLayout(this)
        card.addView(motionActions, LinearLayout.LayoutParams(-1, -2))
        wake = DreamUi.action(motionActions, "预览亮屏") { previewTransition(true) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); marginEnd = dp(6) }
        }
        sleep = DreamUi.action(motionActions, "预览熄屏") { previewTransition(false) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); marginStart = dp(6) }
        }
        card.addView(transitionPreview, previewSize)
        display = DreamUi.action(card, "显示内容") {
            val checked = booleanArrayOf(composition.clock, composition.date, composition.battery)
            AlertDialog.Builder(this, R.style.Theme_IslandDialog)
                .setTitle("息屏显示内容")
                .setMultiChoiceItems(arrayOf("时间", "日期与星期", "电量与充电状态"), checked) { _, index, value ->
                    checked[index] = value
                }
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    saveComposition(composition.copy(clock = checked[0], date = checked[1], battery = checked[2]))
                }.showIslandDialog()
        }
        aodWallpaperInfo = DreamUi.label(this, "", color = DreamUi.muted)
        card.addView(aodWallpaperInfo)
        rootWallpaperAction = DreamUi.action(card, "通过 Root 读取锁屏壁纸") { loadAodWallpaper(requestRoot = true) }
        importAction = DreamUi.action(card, "选取同一张壁纸供预览") { pickAodReference.launch(arrayOf("image/*")) }
        updatePreview()
    }

    private fun savedForPreview() {
        Toast.makeText(this, "已保存预览设置。连接框架后重新选择样式即可应用。", Toast.LENGTH_SHORT).show()
    }

    private fun rememberReadPermission(uri: Uri) = runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess

    private fun loadAodWallpaper(requestRoot: Boolean = false) {
        if (requestingRootWallpaper) return
        requestingRootWallpaper = requestRoot
        val ticket = ++wallpaperGeneration
        val scene = SafeAreas.Scene.LOCK
        aodWallpaperInfo.text = if (requestRoot) "正在通过 Root 读取锁屏壁纸，请留意授权提示…" else "正在读取系统锁屏壁纸…"
        val source = prefs.getString("aod_reference", null)
        Thread({
            val root = if (requestRoot || RootGrant.canTryAutomatically(this)) {
                runCatching { RootWallpaper.read(this, scene, requestRoot) }.getOrElse { RootWallpaper.Result(false, null) }
                    .also { RootGrant.remember(this, it.granted) }
            } else null
            val system = if (root?.image != null) null else runCatching { SafeAreaWallpaper.read(this, scene) }.getOrNull()
                ?: runCatching { remote()?.let { SafeAreaWallpaper.fromHost(it, scene) } }.getOrNull()
            val actual = root?.image ?: runCatching {
                system?.use {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    it.dup().use { copy -> BitmapFactory.decodeFileDescriptor(copy.fileDescriptor, null, bounds) }
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    if (longest <= 0) return@use null
                    var sample = 1
                    while (longest / sample > 1600) sample *= 2
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, BitmapFactory.Options().apply {
                        inSampleSize = sample
                    })
                }
            }.getOrNull()
            val image = actual ?: source?.let { runCatching { decode(Uri.parse(it)) }.getOrNull() }
            runOnUiThread {
                if (isDestroyed || isFinishing || ticket != wallpaperGeneration) {
                    image?.recycle()
                    return@runOnUiThread
                }
                requestingRootWallpaper = false
                aodWallpaper = image
                realAodWallpaper = actual != null
                wallpaperFromRoot = root?.image != null
                transitionPreview.wallpaper = image
                updateWallpaperControls()
                val open = useWallpaperWhenReady
                useWallpaperWhenReady = null
                if (transitionPreview.style != LockTransitionStyle.SYSTEM) {
                    if (image != null) open?.invoke()
                    if (requestRoot && root?.image == null && actual == null) {
                        aodWallpaperInfo.text = when {
                            root?.granted == true -> "已取得 Root，但当前锁屏没有可读的静态壁纸。可以选取对应图片。"
                            RootGrant.managerNeedsPregrant() -> "请在 Magisk 的超级用户列表中授权 DreamGlass，然后重试。"
                            else -> "未取得 Root 权限，请在 Root 管理器中授权后重试。"
                        }
                    }
                }
            }
        }, "aod-lock-wallpaper-preview").start()
    }

    private fun updateWallpaperControls() {
        if (transitionPreview.style == LockTransitionStyle.SYSTEM) return
        importAction.visibility = if (realAodWallpaper) View.GONE else View.VISIBLE
        rootWallpaperAction.visibility = importAction.visibility
        aodWallpaperInfo.text = when {
            wallpaperFromRoot -> "已通过 Root 读取锁屏壁纸。相片和信封分别保存选区。"
            realAodWallpaper -> "正在预览当前锁屏壁纸。两种样式各自保存选区。"
            aodWallpaper != null -> "正在使用手动选取的预览图。请使用与当前锁屏相同的图片，选区才会对应。"
            else -> "尚未读到系统锁屏壁纸。可以选取相同的图片预览并框选。"
        }
    }

    private fun showCropEditor(
        image: Bitmap,
        style: LockTransitionStyle,
        composition: AodComposition,
        ratio: Float,
        apply: (AodComposition) -> Unit,
    ) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            addView(DreamUi.label(context, "拖动左侧图片调整位置，双指捏合或拖动滑杆缩放。右侧实时预览。", color = DreamUi.muted))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        panel.addView(row, LinearLayout.LayoutParams(-1, dp(222)))
        val result = LockTransitionLayer(this, preview = true).apply {
            this.style = style
            this.composition = composition
            wallpaper = image
            battery = this@AodSettingsActivity.battery
            background = DreamUi.shape(context, Color.BLACK, 12)
            clipToOutline = true
            updateAmbientClock()
        }
        val zoomLabel = DreamUi.label(this, "", color = DreamUi.muted)
        val zoomBar = SeekBar(this).apply {
            max = ((AodCrop.MAX_ZOOM - 1f) * 100).roundToInt()
            contentDescription = "壁纸缩放"
        }
        fun showZoom(zoom: Float) {
            zoomLabel.text = "缩放 ${(zoom * 100).roundToInt()}%"
            zoomBar.progress = ((zoom - 1f) * 100).roundToInt()
        }
        val source = AodCropView(this, image, style.imageAspect, composition.crop) { crop, zoom ->
            result.composition = composition.copy(crop = crop)
            showZoom(zoom)
        }
        row.addView(source, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        row.addView(result, LinearLayout.LayoutParams((dp(202) * ratio).toInt(), dp(202)))
        panel.addView(zoomLabel)
        panel.addView(zoomBar, LinearLayout.LayoutParams(-1, dp(44)))
        showZoom(source.zoom)
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) source.setZoom(1f + value / 100f)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        DreamUi.action(panel, "重置构图") { source.reset() }
        AlertDialog.Builder(this, R.style.Theme_IslandDialog)
            .setTitle("选择息屏壁纸显示区域")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ -> apply(composition.copy(crop = source.crop)) }
            .showIslandDialog()
    }

    private fun refresh() {
        val ticket = ++generation
        val mode = prefs.getInt("background", 0)
        bitmap = null
        info.text = if (mode == 0) "当前预览为纯黑背景" else "正在读取预览图片…"
        draw()
        if (mode == 0) return
        val path = prefs.getString("image", null)
        Thread({
            val image = runCatching {
                if (mode == 1) SafeAreaWallpaper.read(this, SafeAreas.Scene.LOCK)?.use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                } else path?.let { decode(Uri.parse(it)) }
            }.getOrNull()
            runOnUiThread {
                if (ticket != generation || isDestroyed) { image?.recycle(); return@runOnUiThread }
                bitmap = image
                info.text = if (image == null) "背景不可读取，预览使用纯黑。可使用已选择的静态图片。"
                    else if (mode == 1) "正在预览系统锁屏壁纸" else "正在预览所选静态图片"
                draw()
            }
        }, "aod-preview").start()
    }

    private fun decode(uri: Uri) = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) {
            decoder, image, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val largest = maxOf(image.size.width, image.size.height)
        if (largest > 1080) decoder.setTargetSize((image.size.width * (1080.0 / largest)).toInt().coerceAtLeast(1),
            (image.size.height * (1080.0 / largest)).toInt().coerceAtLeast(1))
    }

    private fun draw() {
        frame.removeAllViews()
        bitmap?.let { image ->
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(image)
                imageAlpha = intArrayOf(180, 108, 56)[prefs.getInt("dim", 1).coerceIn(0, 2)]
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val style = prefs.getInt("clock", 0)
        frame.addView(TextClock(this).apply {
            format12Hour = if (style == 0) "h:mm" else "h:mm\nM月d日"
            format24Hour = if (style == 0) "HH:mm" else "HH:mm\nM月d日"
            textSize = if (style == 0) 42f else 31f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            alpha = prefs.getInt("preview_brightness", 75).coerceIn(8, 100) / 100f
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun choose(title: String, options: Array<String>, value: Int, selected: (Int) -> Unit) {
        AlertDialog.Builder(this, R.style.Theme_IslandDialog).setTitle(title).setSingleChoiceItems(options, value) { dialog, choice ->
            dialog.dismiss()
            selected(choice)
        }.setNegativeButton("取消", null).showIslandDialog()
    }

    private fun openSystem() {
        for (intent in listOf(Intent("android.settings.LOCK_SCREEN_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS))) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun group(body: LinearLayout, title: String) = DreamUi.card(body).apply {
        addView(DreamUi.label(this@AodSettingsActivity, title, 16f, DreamUi.accent, true))
    }
    private fun dp(value: Int) = DreamUi.dp(this, value)
}
