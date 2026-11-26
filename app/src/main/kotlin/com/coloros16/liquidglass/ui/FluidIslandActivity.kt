package com.coloros16.liquidglass.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.diagnostics.RuntimeStore
import com.coloros16.liquidglass.island.IslandActivity
import com.coloros16.liquidglass.island.IslandAction
import com.coloros16.liquidglass.island.IslandCards
import com.coloros16.liquidglass.island.IslandKind
import com.coloros16.liquidglass.island.IslandLyrics
import com.coloros16.liquidglass.island.IslandMaterial
import com.coloros16.liquidglass.island.IslandPrefs
import com.coloros16.liquidglass.island.IslandPolicy
import com.coloros16.liquidglass.island.IslandSurface
import com.coloros16.liquidglass.island.MediaGlyph
import io.github.libxposed.service.XposedService
import org.json.JSONObject

class FluidIslandActivity : AppCompatActivity(), App.ServiceStateListener {
    private val saved by lazy { Prefs.read(this) }
    private val testValues = mutableMapOf<String, Any>()
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var preview: FrameLayout? = null
    private var previewTrigger: TextView? = null
    private var expanded = false
    private val previewHandler = Handler(Looper.getMainLooper())
    private val collapsePreview = Runnable {
        expanded = false
        renderPreview()
    }
    private var sample = 0
    private var samplePlaying = true
    private var sampleSong = 0
    private var group = 0
    private val openLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread({
            val file = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readNBytes(IslandLyrics.MAX_BYTES + 1) }
                    ?.takeIf { it.size <= IslandLyrics.MAX_BYTES }
                    ?.toString(Charsets.UTF_8)?.takeIf { IslandLyrics.parse(it).isNotEmpty() }
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (file == null) toast("请选取 96 KB 以内、包含时间戳的 UTF-8 LRC 文件")
                else trackFields(file)
            }
        }, "island-lyrics-import").start()
    }
    private fun dp(value: Int) = DreamUi.dp(this, value)
    private fun remote() = runCatching { App.serviceRef?.let(Prefs::remote) }.getOrNull()
    private fun store() = remote() ?: saved
    private fun flag(key: String, initial: Boolean) = testValues[key] as? Boolean ?: store().getBoolean(key, initial)
    private fun number(key: String, initial: Int) = testValues[key] as? Int ?: store().getInt(key, initial)
    private val runtimeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == RuntimeDiagnostics.PREFIX + "com.android.systemui") runOnUiThread { refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = savedInstanceState?.getInt("group") ?: 0
        RuntimeStore.local(this).registerOnSharedPreferenceChangeListener(runtimeListener)
        App.addServiceStateListener(this, false)
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("group", group)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewHandler.removeCallbacks(collapsePreview)
        RuntimeStore.local(this).unregisterOnSharedPreferenceChangeListener(runtimeListener)
        App.removeServiceStateListener(this)
        super.onDestroy()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread { if (!isFinishing && !isDestroyed) { testValues.clear(); show() } }
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus() }

    private fun show() {
        preview = null
        previewTrigger = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DreamUi.canvas)
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightNavigationBars = false; isAppearanceLightStatusBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(3), dp(18), dp(8))
        }
        root.addView(heading)
        heading.addView(DreamUi.label(this, if (group == 0) "‹  返回" else "‹  流体云接管", 13f, DreamUi.accent).apply {
            minHeight = dp(42)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setOnClickListener { if (group == 0) finish() else open(0) }
        })
        heading.addView(DreamUi.label(this, titles[group], 23f, bold = true))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        scroll.addView(body)
        status = DreamUi.label(this, "", 13f, DreamUi.muted)
        when (group) {
            1 -> capsuleSettings()
            2 -> cardSettings()
            3 -> sources()
            4 -> rules()
            else -> overview()
        }
        refreshStatus()
    }

    private fun overview() {
        val control = DreamUi.card(body)
        toggle(control, IslandPrefs.ENABLED, "接管原生流体云", false, needsFramework = true)
        toggle(control, IslandPrefs.GLASS, "接管窗口使用液态玻璃", false)
        control.addView(status)
        note(control, "收起时显示黑色胶囊，展开时应用玻璃。亮屏解锁时隐藏系统流体云，锁屏和息屏仍使用系统显示。")
        previewCard()
        val navigation = DreamUi.card(body)
        val entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        navigation.addView(EditText(this).apply {
            hint = "搜索设置"
            setTextColor(DreamUi.ink)
            setHintTextColor(DreamUi.muted)
            setSingleLine()
            doAfterTextChanged { index(entries, it?.toString().orEmpty()) }
        })
        navigation.addView(entries)
        index(entries, "")
    }

    private fun index(parent: LinearLayout, query: String) {
        parent.removeAllViews()
        descriptions.forEachIndexed { i, detail ->
            val title = titles[i + 1]
            if ("$title $detail".contains(query.trim(), ignoreCase = true))
                DreamUi.row(parent, title, detail) { open(i + 1) }
        }
        if (parent.childCount == 0) note(parent, "没有匹配的设置")
    }

    private fun capsuleSettings() {
        previewCard()
        val controls = DreamUi.card(body)
        toggle(controls, IslandPrefs.LEFT, "显示左副岛", true)
        toggle(controls, IslandPrefs.RIGHT, "显示右副岛", true)
        slider(controls, IslandPrefs.WIDTH, "胶囊宽度", 128..250, IslandPrefs.DEFAULT_WIDTH, "dp")
        DreamUi.action(controls, "校准摄像头位置") { cameraDialog() }
        reset(controls, "胶囊、左右副岛和摄像头", listOf(IslandPrefs.LEFT, IslandPrefs.RIGHT,
            IslandPrefs.WIDTH, IslandPrefs.CAMERA_X, IslandPrefs.CAMERA_Y, IslandPrefs.CAMERA_SIZE))
    }

    private fun cardSettings() {
        previewCard()
        val controls = DreamUi.card(body)
        toggle(controls, IslandPrefs.STACK, "OP 式纵向多卡", false)
        note(controls, "关闭后采用苹果式单卡，在卡片上横滑可切换活动。多卡内容可向下滚动。")
        slider(controls, IslandPrefs.CORNER, "卡片圆角", 14..36, 24, "dp")
        slider(controls, IslandPrefs.OPACITY, "玻璃遮罩", 20..88, 62, "/255")
        slider(controls, IslandPrefs.ANIMATION, "展开和收起动画", 0..500, 220, "ms")
        reset(controls, "展开布局、圆角、遮罩和动画", listOf(IslandPrefs.STACK, IslandPrefs.CORNER,
            IslandPrefs.OPACITY, IslandPrefs.ANIMATION))
    }

    private fun sources() {
        val controls = DreamUi.card(body)
        IslandKind.entries.forEach { toggle(controls, IslandPrefs.key(it), it.label, it.enabled) }
        toggle(controls, IslandPrefs.CHARGE, "显示充电", true)
        toggle(controls, IslandPrefs.HEADPHONES, "显示耳机连接", true)
        toggle(controls, IslandPrefs.RECORDING, "显示麦克风使用状态", false)
        DreamUi.action(controls, "选择允许上岛的消息或验证码应用") { chooseApps(IslandPrefs.FILTER) }
        note(controls, "消息和验证码仅显示已选择应用的内容。接管窗口在锁屏时撤除。")
        slider(controls, IslandPrefs.MESSAGE_SECONDS, "消息和验证码停留", 3..60, 9, "秒")
        slider(controls, IslandPrefs.MEDIA_LINGER, "媒体暂停后停留", 0..60, 10, "秒")
        note(controls, "播放应用在前台时隐藏媒体岛，离开后恢复。歌词来源为本地 LRC。")
        reset(controls, "活动开关、消息应用和停留时长", IslandKind.entries.map(IslandPrefs::key) +
            listOf(IslandPrefs.FILTER, IslandPrefs.MESSAGE_SECONDS, IslandPrefs.MEDIA_LINGER,
                IslandPrefs.CHARGE, IslandPrefs.HEADPHONES, IslandPrefs.RECORDING))
        val lyric = DreamUi.card(body)
        lyric.addView(DreamUi.label(this, "歌词", 16f, DreamUi.accent, true))
        toggle(lyric, IslandLyrics.ENABLED, "媒体岛同步显示歌词", false)
        toggle(lyric, IslandLyrics.TRANSLATION, "同时显示同一时间点的译文", true)
        toggle(lyric, IslandLyrics.TRANSLATION_FIRST, "译文显示在原文前", false)
        val count = store().all.keys.count { it.startsWith(IslandLyrics.TRACKS) }
        note(lyric, "本地 LRC · 已导入 $count 首。歌曲没有匹配的 LRC 时显示歌曲信息。")
        DreamUi.action(lyric, "导入本地 LRC") {
            if (remote() == null) toast("请先连接 LSPosed 框架") else openLyrics.launch(arrayOf("*/*"))
        }
        DreamUi.action(lyric, "移除一首歌词") { trackFields(null) }
        reset(lyric, "歌词显示和译文顺序", listOf(IslandLyrics.ENABLED,
            IslandLyrics.TRANSLATION, IslandLyrics.TRANSLATION_FIRST))
    }

    private fun trackFields(file: String?) {
        val linked = remote() ?: run { toast("请先连接 LSPosed 框架"); return }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(6))
        }
        val title = EditText(this).apply { hint = "曲名"; setSingleLine() }
        val artist = EditText(this).apply { hint = "歌手（可留空）"; setSingleLine() }
        layout.addView(title)
        layout.addView(artist)
        dialog().setTitle(if (file == null) "移除歌词" else "关联到播放曲目")
            .setMessage("请输入与系统媒体卡片一致的曲名和歌手。")
            .setView(layout).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                if (title.text.isNullOrBlank()) { toast("请输入曲名"); return@setPositiveButton }
                val key = IslandLyrics.key(title.text.toString(), artist.text.toString())
                val values = mapOf(key to file)
                if (write(linked, values)) { write(saved, values); show() }
            }.showIslandDialog()
    }

    private fun rules() {
        val controls = DreamUi.card(body)
        toggle(controls, IslandPrefs.LANDSCAPE, "横屏时显示接管窗口", false)
        toggle(controls, IslandPrefs.SHOW_SOURCE, "展开时显示应用来源", true)
        slider(controls, IslandPrefs.AUTO_COLLAPSE, "闲置后收起",
            IslandPrefs.MIN_AUTO_COLLAPSE..IslandPrefs.MAX_AUTO_COLLAPSE, IslandPrefs.DEFAULT_AUTO_COLLAPSE, "秒")
        note(controls, "点胶囊才展开。闲置、再次点胶囊或点卡片外部时会收起。")
        DreamUi.action(controls, "选择豁免的活动来源") { chooseApps(IslandPrefs.EXEMPT) }
        note(controls, "豁免应用的活动由系统处理。胶囊横滑暂时隐藏，点击「召回活动」或长按副岛恢复。")
        reset(controls, "横屏、应用来源、自动收起和豁免应用", listOf(IslandPrefs.LANDSCAPE, IslandPrefs.SHOW_SOURCE,
            IslandPrefs.AUTO_COLLAPSE, IslandPrefs.EXEMPT))
    }

    private fun toggle(parent: LinearLayout, key: String, label: String, initial: Boolean,
                       needsFramework: Boolean = false, refresh: () -> Unit = {}) {
        val sw = Switch(this).apply {
            text = label
            minHeight = dp(50)
            setTextColor(DreamUi.ink)
            isChecked = flag(key, initial)
            isEnabled = !needsFramework || remote() != null
        }
        sw.setOnCheckedChangeListener { _, value ->
            save(key, value)
            refresh()
            refreshStatus()
            renderPreview()
        }
        parent.addView(sw)
    }

    private fun slider(parent: LinearLayout, key: String, label: String, range: IntRange, initial: Int, unit: String) {
        val stored = number(key, initial)
        val start = if (key == IslandPrefs.AUTO_COLLAPSE) IslandPrefs.autoCollapseSeconds(stored) else stored
        val control = sliderControl(parent, label, range, start, unit, {
            testValues[key] = it
            renderPreview()
        }) {
            save(key, it)
            renderPreview()
        }
        control.isEnabled = true
    }

    private fun sliderControl(parent: LinearLayout, label: String, range: IntRange, initial: Int, unit: String,
                              changed: (Int) -> Unit, committed: (Int) -> Unit): SeekBar {
        val title = DreamUi.label(this, "", 14f, DreamUi.ink)
        fun update(value: Int) { title.text = "$label · $value $unit" }
        val bar = SeekBar(this).apply {
            max = range.last - range.first; progress = initial.coerceIn(range) - range.first
            contentDescription = label
        }
        update(bar.progress + range.first)
        title.isFocusable = true
        title.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((bar.progress + range.first).toString())
                selectAll()
            }
            dialog().setTitle(label).setMessage("${range.first}–${range.last} $unit")
                .setView(input).setNegativeButton("取消", null).setPositiveButton("应用") { _, _ ->
                    input.text.toString().toIntOrNull()?.coerceIn(range)?.let {
                        bar.progress = it - range.first; update(it); changed(it); committed(it)
                    }
                }.showIslandDialog()
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress + range.first
                update(value)
                if (fromUser) {
                    changed(value)
                    if (!touching) committed(value)
                }
            }
            private var touching = false
            override fun onStartTrackingTouch(view: SeekBar) { touching = true }
            override fun onStopTrackingTouch(view: SeekBar) { touching = false; committed(view.progress + range.first) }
        })
        parent.addView(title)
        parent.addView(bar)
        return bar
    }

    private fun cameraDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        var x = number(IslandPrefs.CAMERA_X, 50)
        var y = number(IslandPrefs.CAMERA_Y, 10)
        var size = number(IslandPrefs.CAMERA_SIZE, 0)
        val sample = object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(0xff263648.toInt())
                val cx = width * x / 100f
                val cy = (dp(y) + dp(7)).toFloat()
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = 0xff3a5265.toInt()
                val l = (cx - dp(78)).coerceIn(0f, (width - dp(156)).coerceAtLeast(0).toFloat())
                canvas.drawRoundRect(l, (cy - dp(18)).coerceAtLeast(0f), l + dp(156), cy + dp(20), dp(24).toFloat(), dp(24).toFloat(), paint)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = 0xff97e5d0.toInt()
                canvas.drawCircle(cx, cy, dp(if (size == 0) 11 else size / 2).toFloat(), paint)
            }
        }
        layout.addView(sample, LinearLayout.LayoutParams(-1, dp(92)))
        sliderControl(layout, "横向位置", 1..99, x, "%", { x = it; sample.invalidate() }) {}
        sliderControl(layout, "顶部偏移", 0..52, y, "dp", { y = it; sample.invalidate() }) {}
        sliderControl(layout, "孔径，0 为自动识别", 0..35, size, "dp", { size = it; sample.invalidate() }) {}
        dialog().setTitle("摄像头校准 · 本页预览").setView(layout)
            .setNegativeButton("取消", null).setNeutralButton("恢复自动") { _, _ ->
                save(mapOf(IslandPrefs.CAMERA_SIZE to 0, IslandPrefs.CAMERA_X to 50, IslandPrefs.CAMERA_Y to 10))
                renderPreview()
            }.setPositiveButton("保存") { _, _ ->
                save(mapOf(IslandPrefs.CAMERA_X to x, IslandPrefs.CAMERA_Y to y, IslandPrefs.CAMERA_SIZE to size))
                renderPreview()
            }.showIslandDialog()
    }

    private fun chooseApps(key: String) {
        val packages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString() }
        val current = IslandPrefs.packages((testValues[key] as? String) ?: store().getString(key, null)).toMutableSet()
        val names = packages.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray()
        val checked = BooleanArray(packages.size) { packages[it].activityInfo.packageName in current }
        dialog().setTitle(if (key == IslandPrefs.FILTER) "允许上岛的应用" else "豁免的活动来源")
            .setMultiChoiceItems(names, checked) { _, index, enabled ->
                val pkg = packages[index].activityInfo.packageName
                if (enabled) current.add(pkg) else current.remove(pkg)
            }.setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                save(key, current.joinToString(","))
            }.showIslandDialog()
    }

    private fun previewCard() {
        val container = DreamUi.card(body)
        container.addView(DreamUi.label(this, "测试岛 · 示例数据", 13f, DreamUi.accent, true))
        preview = FrameLayout(this).apply {
            minimumHeight = dp(118)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff445f75.toInt(), 0xff203342.toInt(), 0xff354f57.toInt())).apply { cornerRadius = dp(16).toFloat() }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }.also(container::addView)
        previewTrigger = DreamUi.action(container, "唤醒测试岛") { togglePreview() }
        DreamUi.action(container, "切换示例活动") { sample = (sample + 1) % examples.size; renderPreview() }
        renderPreview()
    }

    private fun renderPreview() {
        val target = preview ?: return
        previewTrigger?.text = if (expanded) "收起测试岛" else "唤醒测试岛"
        target.removeAllViews()
        val activities = examples.map { event ->
            if (event.kind != IslandKind.MEDIA) event else event.copy(
                title = if (sampleSong == 0) event.title else "海面微光",
                actions = listOf(
                    IslandAction("上一首", MediaGlyph.PREVIOUS) { sampleSong = 1 - sampleSong; renderPreview() },
                    IslandAction(if (samplePlaying) "暂停" else "播放", if (samplePlaying) MediaGlyph.PAUSE else MediaGlyph.PLAY) {
                        samplePlaying = !samplePlaying
                        renderPreview()
                    },
                    IslandAction("下一首", MediaGlyph.NEXT) { sampleSong = 1 - sampleSong; renderPreview() },
                ),
            )
        }
        val chosen = activities[sample]
        val radius = dp(number(IslandPrefs.CORNER, 24)).toFloat()
        val glass = expanded && flag(IslandPrefs.GLASS, false)
        val cameraSize = number(IslandPrefs.CAMERA_SIZE, 0).let { if (it <= 0) 22 else it }
        val width = dp(number(IslandPrefs.WIDTH, IslandPrefs.DEFAULT_WIDTH) + if (expanded) 12 else 0)
        val holeOffset = width * number(IslandPrefs.CAMERA_X, 50) / 100
        val height = IslandPolicy.capsuleVertical(dp(cameraSize), 0, dp(cameraSize), dp(1), dp(4), dp(30)).height
        val hole = Rect(holeOffset - dp(cameraSize / 2), 0, holeOffset + dp(cameraSize / 2), dp(cameraSize))
        val material: IslandMaterial? = if (glass) { canvas, view, round ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x3877c9df }
                canvas.drawRoundRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), round, round, paint)
                true
            } else null
        val opacity = number(IslandPrefs.OPACITY, 62)
        val surface = IslandSurface(this, dp(30).toFloat(), opacity, material, solidBlack = !expanded)
        val cards = IslandCards(this, material)
        cards.capsule(surface, chosen, hole, height)
        surface.getChildAt(0).setOnClickListener { togglePreview() }
        val deck = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deck.addView(surface, LinearLayout.LayoutParams(width, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (expanded) {
            val visible = if (flag(IslandPrefs.STACK, false)) listOf(chosen) + activities.filter { it != chosen } else listOf(chosen)
            visible.forEach { event ->
                val panel = IslandSurface(this, radius, opacity, material)
                panel.addView(cards.card(event, "示例 · ${event.kind.label}", ::armPreviewCollapse, { toast(it) }))
                deck.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        target.addView(deck, FrameLayout.LayoutParams(-1, -2))
    }

    private fun togglePreview() {
        expanded = !expanded
        armPreviewCollapse()
        renderPreview()
    }

    private fun armPreviewCollapse() {
        previewHandler.removeCallbacks(collapsePreview)
        if (!expanded) return
        val delay = IslandPrefs.autoCollapseSeconds(number(IslandPrefs.AUTO_COLLAPSE, IslandPrefs.DEFAULT_AUTO_COLLAPSE))
        previewHandler.postDelayed(collapsePreview, delay * 1000L)
    }

    private fun reset(parent: LinearLayout, range: String, keys: List<String>) {
        DreamUi.action(parent, "恢复本组默认") {
            dialog().setTitle("恢复本组默认").setMessage("将恢复$range。")
                .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                    val p = remote()
                    if (p != null && !write(p, keys.associateWith { null })) return@setPositiveButton
                    write(saved, keys.associateWith { null })
                    keys.forEach(testValues::remove)
                    show()
                }.showIslandDialog()
        }
    }

    private fun save(key: String, value: Any) = save(mapOf(key to value))
    private fun save(values: Map<String, Any>) {
        val linked = remote()
        if (linked == null) {
            testValues.putAll(values)
            if (values.keys.any { it == IslandPrefs.FILTER || it == IslandPrefs.EXEMPT }) toast("框架未连接，仅用于本页预览")
            return
        }
        if (!write(linked, values)) return
        write(saved, values)
        values.keys.forEach(testValues::remove)
    }

    private fun write(store: SharedPreferences, values: Map<String, Any?>): Boolean = runCatching {
        val edit = store.edit()
        for ((key, value) in values) when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
            null -> edit.remove(key)
        }
        edit.commit()
    }.getOrDefault(false).also { if (!it) toast("保存失败，请检查框架连接") }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val linked = remote()
        status.text = when {
            linked == null -> "框架未连接，可试用本页预览"
            !linked.getBoolean(Prefs.KEY_MASTER, false) -> "请先开启 DreamGlass 总开关"
            !linked.getBoolean(IslandPrefs.ENABLED, false) -> "接管关闭"
            else -> runCatching {
                val raw = RuntimeStore.local(this).getString(RuntimeDiagnostics.PREFIX + "com.android.systemui", null)
                val stages = JSONObject(raw.orEmpty()).optJSONObject("stages")
                val current = stages?.optString(IslandPrefs.STATUS).orEmpty()
                val material = stages?.optString(IslandPrefs.MATERIAL_STATUS).orEmpty()
                if (current.startsWith("正在显示") && linked.getBoolean(IslandPrefs.GLASS, false) && material.isNotBlank())
                    "$current\n$material" else current
            }.getOrDefault("").ifBlank { "已保存设置，等待 SystemUI 回传接管状态" }
        }
    }

    private fun note(parent: LinearLayout, value: String) { parent.addView(DreamUi.label(this, value, 13f, DreamUi.muted)) }
    private fun open(section: Int) { group = section; show() }
    private fun dialog() = AlertDialog.Builder(this, R.style.Theme_IslandDialog)
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    companion object {
        private val titles = listOf("流体云接管", "胶囊与副岛", "展开卡片", "内容与通知", "显示规则")
        private val descriptions = listOf("摄像头校准、宽度、左右副岛", "苹果式单卡、OP 多卡、圆角和动画",
            "媒体、歌词、消息、验证码、来电、计时和系统状态", "横屏、来源、自动收起和豁免应用")
        private val examples = listOf(
            IslandActivity("sample-media", IslandKind.MEDIA, "", "漫游傍晚", "媒体播放示例", progress = 42, changedAt = 0),
            IslandActivity("sample-live", IslandKind.LIVE, "", "前方 350 米右转", "导航示例 · 预计 18:26 到达", progress = 72, changedAt = 0),
            IslandActivity("sample-timer", IslandKind.TIMER, "", "计时器 · 04:18", "计时示例", changedAt = 0),
        )
    }
}

internal fun AlertDialog.Builder.showIslandDialog(): AlertDialog = show().apply {
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(DreamUi.accent)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(DreamUi.accent)
    getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(DreamUi.accent)
}
