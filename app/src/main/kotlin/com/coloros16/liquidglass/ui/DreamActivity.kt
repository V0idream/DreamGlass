package com.coloros16.liquidglass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.coloros16.liquidglass.BuildConfig
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.diagnostics.RuntimeStore
import com.coloros16.liquidglass.root.ScopeRestarter
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Product dashboard. All host configuration uses the same framework store as the hooks. */
class DreamActivity : AppCompatActivity(), App.ServiceStateListener {
    private val ink = DreamUi.ink
    private val muted = DreamUi.muted
    private val accent = DreamUi.accent
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var frameworkLabel: TextView
    private lateinit var master: Switch
    private var selected = 0
    private var reflectingMaster = false
    private var preview: LiquidGlassPreviewView? = null
    private var previewStyle = Styles.all.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getInt("tab", 0) ?: 0
        App.addServiceStateListener(this, false)
        showPage()
    }

    override fun onResume() { super.onResume(); if (::frameworkLabel.isInitialized) refreshState() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", selected); super.onSaveInstanceState(outState) }
    override fun onDestroy() { App.removeServiceStateListener(this); preview = null; super.onDestroy() }
    override fun onServiceStateChanged(service: XposedService?) { runOnUiThread { if (!isDestroyed) refreshState() } }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 20) = DreamUi.shape(this, color, radius)
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = DreamUi.label(this, value, size, color, bold)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card(parent: LinearLayout = body) = DreamUi.card(parent)
    private fun action(parent: LinearLayout, title: String, secondary: Boolean = false, click: () -> Unit) =
        DreamUi.action(parent, title, !secondary, click)
    private fun advanced(category: String) = startActivity(Intent(this, SettingsActivity::class.java).apply {
        putExtra("category", category)
    })

    private fun showPage() {
        preview = null
        root = column().apply { setBackgroundColor(DreamUi.canvas) }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        val head = column().apply { setPadding(dp(23), dp(14), dp(23), dp(10)) }
        root.addView(head)
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(DreamMarkView(this), LinearLayout.LayoutParams(dp(37), dp(37)).apply { marginEnd = dp(10) })
        brand.addView(text("DreamGlass", 20f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(text(BuildConfig.VERSION_NAME, 11f, accent).apply {
            background = bg(DreamUi.raised, 9); setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        head.addView(brand)
        head.addView(text(listOf("概览", "材质工作室", "运行状态", "设置")[selected], 20f, ink, true).apply { setPadding(0, dp(10), 0, dp(2)) })
        frameworkLabel = text("正在读取框架状态…", 12f, muted)
        head.addView(frameworkLabel)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(12)) }
        scroll.addView(body)
        when (selected) { 0 -> overview(); 1 -> material(); 2 -> diagnostics(); else -> settings() }
        val nav = LinearLayout(this).apply { setPadding(dp(12), dp(7), dp(12), dp(7)) }
        listOf("主页", "材质", "状态", "设置").forEachIndexed { i, name ->
            nav.addView(TextView(this).apply {
                gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 13f; isClickable = true; isFocusable = true; setPadding(0, dp(14), 0, dp(14))
                text = name; minHeight = dp(48)
                setTextColor(if (i == selected) DreamUi.canvas else muted)
                background = bg(if (i == selected) accent else Color.TRANSPARENT, 16)
                setOnClickListener { selected = i; showPage() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        root.addView(nav)
        refreshState()
    }

    private fun overview() {
        val hero = card()
        hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff1b3b42.toInt(), 0xff132a35.toInt())).apply { cornerRadius = dp(26).toFloat() }
        hero.addView(text("FOR COLOROS 17", 11f, accent, true))
        hero.addView(text("让系统界面，轻一点", 22f, ink, true))
        hero.addView(text("细腻的光影，安静地融入每一天。", 13f, muted))
        val control = card()
        master = Switch(this).apply {
            text = "启用 DreamGlass"; textSize = 18f; setTextColor(ink); minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled ->
                if (reflectingMaster) return@setOnCheckedChangeListener
                val p = remote()
                if (p == null) { refreshState(); return@setOnCheckedChangeListener }
                try {
                    if (!p.edit().putBoolean(Prefs.KEY_MASTER, enabled).commit()) {
                        notice("保存失败，请检查框架连接"); refreshState(); return@setOnCheckedChangeListener
                    }
                    Prefs.read(this@DreamActivity).edit().putBoolean(Prefs.KEY_MASTER, enabled).apply()
                    notice(if (enabled) "已保存；请在 LSPosed 选择作用域并手动重启目标进程" else "已关闭入口；请手动重启目标进程以完全撤销 Hook")
                } catch (_: Throwable) { notice("保存失败，请检查框架连接"); refreshState() }
            }
        }
        control.addView(master)
        control.addView(text("启用后重启所选作用域，即可应用新的系统材质。", 12f, muted))
        val features = card()
        features.addView(text("界面与交互", 17f, ink, true))
        action(features, "通知与控制中心  ›", true) { advanced("master") }
        action(features, "桌面文件夹与 Dock  ›", true) { advanced("desktop") }
        action(features, "动画与空间感  ›", true) { advanced("tilt") }
        action(features, "组件与圆角  ›", true) { advanced("corner") }
        action(features, "全部设置  ›", true) { selected = 3; showPage() }
        val safe = card()
        safe.addView(text("回到系统原有质感", 17f, ink, true))
        safe.addView(text("暂停玻璃会还原系统材质。彻底停用请关闭总开关，并在 LSPosed 撤销作用域后手动重启。", 12f, muted))
        action(safe, "暂停玻璃效果", true) {
            write { it.putBoolean(Prefs.KEY_GLASS_ENABLE, false) }
        }
        action(safe, "恢复玻璃效果", true) { write { it.putBoolean(Prefs.KEY_GLASS_ENABLE, true) } }
        body.addView(text("DreamGlass  ${BuildConfig.VERSION_NAME}  ·  For ColorOS 17", 11f, muted).apply { setOnClickListener { startActivity(Intent(this@DreamActivity, LicenseActivity::class.java)) } })
    }

    private fun attachPreview(parent: LinearLayout) {
        val p = LiquidGlassPreviewView(this)
        preview = p
        parent.addView(p, LinearLayout.LayoutParams(-1, dp(155)).apply { topMargin = dp(12); bottomMargin = dp(8) })
        updatePreview()
    }

    private fun material() {
        val canvas = card()
        canvas.addView(text("材质预览", 22f, ink, true))
        canvas.addView(text("先预览，再将材质写入系统配置。", 13f, muted))
        attachPreview(canvas)
        for (style in Styles.all) {
            val c = card()
            c.addView(text(style.name, 19f, ink, true))
            c.addView(text(style.desc, 13f, muted))
            action(c, "预览「${style.name}」", true) { previewStyle = style; updatePreview() }
            action(c, "应用「${style.name}」") {
                previewStyle = style; updatePreview()
                write { style.apply(it).putBoolean(Prefs.KEY_GLASS_ENABLE, true) }
            }
        }
        val tuning = card()
        tuning.addView(text("自定义参数", 18f, ink, true))
        action(tuning, "折射 / 色散 / 高光 / 模糊  ›", true) { advanced("material") }
        action(tuning, "背景采样与性能  ›", true) { advanced("background") }
        tuning.addView(text("预设只调整材质，不会自动启用总开关、Dock、锁屏或实验性动画。", 12f, muted))
    }

    private fun updatePreview() {
        preview?.apply {
            blurRadius = previewStyle.blur; refractionAmount = previewStyle.refract
            dispersion = previewStyle.dispersion; highlight = previewStyle.highlight
            refresh()
        }
    }

    private fun settings() {
        val entry = card()
        entry.addView(text("应用入口", 17f, ink, true))
        val alias = android.content.ComponentName(this, "$packageName.LauncherAlias")
        val icon = Switch(this).apply {
            text = "显示桌面图标"; setTextColor(ink); minHeight = dp(48)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isChecked = packageManager.getComponentEnabledSetting(alias) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            setOnCheckedChangeListener { control, visible ->
                try {
                    packageManager.setComponentEnabledSetting(alias,
                        if (visible) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP)
                    notice(if (visible) "桌面入口已显示" else "桌面入口已隐藏，可从 LSPosed 模块设置中恢复")
                } catch (_: Exception) { control.setOnCheckedChangeListener(null); control.isChecked = !visible; notice("切换失败，请稍后重试") }
            }
        }
        entry.addView(icon)
        entry.addView(text("隐藏后仍可通过 LSPosed 的模块设置打开 DreamGlass。", 12f, muted))
        val groups = listOf(
            "系统外观" to listOf(Triple("master", "渲染开关", "管理模块入口与玻璃效果"), Triple("material", "材质参数", "折射、鲜艳度与边缘高光"), Triple("text", "文字与可读性", "颜色跟随与对比度"), Triple("mask", "背景压暗", "平衡通透感与文字可读性")),
            "场景与交互" to listOf(Triple("desktop", "桌面与 Dock", "文件夹、搜索栏和小组件"), Triple("corner", "独立圆角", "按组件单独设置形状"), Triple("components", "其他应用与组件", "相册、计算器和智能侧边栏"), Triple("seedling", "流体云卡片", "展开卡片的材质与形状"), Triple("tilt", "空间效果", "通知倾斜与透视"), Triple("anim", "桌面动画", "打开、关闭与打断参数")),
            "维护与数据" to listOf(Triple("background", "采样与性能", "背景刷新、质量及省电策略"), Triple("log", "运行日志", "查看和导出排查记录"), Triple("config", "配置备份", "导入、导出与恢复参数"))
        )
        for ((title, rows) in groups) {
            val c = card(); c.addView(text(title, 11f, accent, true))
            for ((id, label, detail) in rows) DreamUi.row(c, label, detail) { advanced(id) }
        }
        performanceControls()
        rootControls()
        val about = card()
        DreamUi.row(about, "关于 DreamGlass", "${BuildConfig.VERSION_NAME} · 开源许可与项目来源") {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
    }

    private fun performanceControls() {
        val c=card()
        c.addView(text("渲染方式",18f,ink,true))
        c.addView(text("硬件优先减少 CPU 计算；高精度保留更细色阶，但耗电较高。",12f,muted))
        val p=remote()
        val group=RadioGroup(this)
        val current=p?.getInt(Prefs.KEY_BLUR_ENGINE,Prefs.DEFAULT_BLUR_ENGINE) ?: Prefs.DEFAULT_BLUR_ENGINE
        for((value,label) in listOf(0 to "硬件优先 · 日常推荐",1 to "高精度 · CPU F16")) {
            group.addView(RadioButton(this).apply { id=View.generateViewId();text=label;setTextColor(ink);tag=value;isChecked=current==value;isEnabled=p!=null })
        }
        group.setOnCheckedChangeListener { g,id ->
            val value=g.findViewById<RadioButton>(id)?.tag as? Int ?: return@setOnCheckedChangeListener
            write { it.putInt(Prefs.KEY_BLUR_ENGINE,value) }
        }
        c.addView(group)
        action(c,"背景刷新与模糊强度  ›",true) { advanced("background") }
    }

    private fun rootControls() {
        val c = card()
        c.addView(text("快速重启作用域", 18f, ink, true))
        c.addView(text("首次使用由手机请求本应用的 Root 授权。仅重启选定进程；SystemUI 重启时状态栏会短暂消失。运行记录读取无需 Root。", 12f, muted))
        val state = text(RuntimeStore.local(this).getString("root_last_status", "尚未申请本应用 Root 授权").orEmpty(), 12f, accent)
        c.addView(state)
        action(c, "申请本应用 Root 授权", true) { rootAction(null, state) }
        for (scope in ScopeRestarter.Scope.entries) {
            action(c, "重启${scope.label}", true) {
                android.app.AlertDialog.Builder(this).setTitle("重启${scope.label}")
                    .setMessage("保存好当前设置后，重启 ${scope.pkg} 以重新加载模块。只处理此包的进程，并核对重启前后 PID。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认重启") { _, _ -> rootAction(scope, state) }.show()
            }
        }
    }

    private fun rootAction(scope: ScopeRestarter.Scope?, state: TextView) {
        state.text = "等待手机 Root 授权或重启结果…"
        val appContext = applicationContext
        Thread({
            val result = if (scope == null) ScopeRestarter.requestAccess() else ScopeRestarter.restart(appContext, scope)
            val report = result.message
            RuntimeStore.local(appContext).edit().putString("root_last_status", "上次操作：$report").apply()
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    if (state.isAttachedToWindow) state.text = report
                    notice(report)
                }
            }
        }, "DreamGlass-root-action").start()
    }

    private fun diagnostics() {
        val d = card()
        d.addView(text("目标进程记录", 21f, ink, true))
        d.addView(text("查看最近接收的作用域状态与运行记录。", 13f, muted))
        action(d, "刷新运行记录", true) { showPage() }
        for ((pkg, label) in listOf("com.android.launcher" to "系统桌面", "com.android.systemui" to "SystemUI")) {
            val c = card(); c.addView(text(label, 18f, ink, true)); c.addView(text(pkg, 11f, muted))
            val report = readReport(pkg)
            val details=text(report,13f,muted).apply { visibility=View.GONE }
            val parsed=try { JSONObject(RuntimeStore.local(this).getString(RuntimeDiagnostics.PREFIX+pkg,"{}").orEmpty()) } catch(_:Throwable) { JSONObject() }
            c.addView(text(if(parsed.has("stages")) "已收到进程记录 · 模块 ${parsed.optString("version", "未知")}" else "等待目标进程回传",13f,accent))
            action(c,"查看详细阶段",true) { details.visibility=if(details.visibility==View.GONE)View.VISIBLE else View.GONE }
            c.addView(details)
        }
        val tools = card()
        tools.addView(text("支持与恢复", 18f, ink, true))
        tools.addView(text("记录用于排查连接和渲染问题。若需停用，可关闭模块并重启作用域，无需清除系统桌面数据。",13f,muted))
        action(tools, "复制诊断摘要", true) {
            val report = "DreamGlass ${BuildConfig.VERSION_NAME}\n${frameworkLabel.text}\n" + listOf("com.android.launcher", "com.android.systemui").joinToString("\n") { "$it\n${readReport(it)}" }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DreamGlass", report))
            notice("已复制；摘要不含截图或应用内容")
        }
        action(tools, "详细日志与导出  ›", true) { advanced("log") }
        action(tools, "开源许可与项目来源", true) { startActivity(Intent(this, LicenseActivity::class.java)) }
    }

    private fun readReport(pkg: String): String = try {
        val raw = RuntimeStore.local(this).getString(RuntimeDiagnostics.PREFIX + pkg, null)
        if (raw == null) "尚无运行记录。请检查 API 102 框架支持、作用域和目标进程是否已重启。"
        else {
            val o = JSONObject(raw); val s = o.getJSONObject("stages")
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(o.getLong("at")))
            val fields = listOf("loaded" to "入口", "configured" to "配置", "mounted" to "Hook", "invoked" to "调用", "source" to "背景", "render" to "绘制", "extensions" to "扩展") +
                if (pkg == "com.android.systemui") listOf("startup" to "初始化", "content_render" to "内容绘制", "notification_glass" to "通知玻璃路径", "notification_blur" to "通知回退记录", "qs_glass" to "按钮玻璃路径", "qs_blur" to "按钮回退记录", "qs_seekbar_hook" to "进度接口", "qs_seekbar_active" to "进度对象", "qs_seekbar_tint" to "进度填充", "qs_tile_mask" to "按钮遮罩", "qs_slider_hook" to "滑杆轮廓接口", "qs_slider_clip" to "滑杆填充裁剪", "qs_icon_hook" to "图标底层接口", "qs_icon_background" to "横向图标底层", "volume_native_hook" to "独立音量归属接口", "volume_native" to "独立音量原生保护", "volume_window" to "音量窗口隔离", "blur_hardware" to "硬件背景", "blur_cpu" to "高精度背景") else emptyList()
            "宿主模块版本：${o.optString("version", "未知")}\n上次记录 $stamp  ·  PID ${o.optInt("pid")}\n" + fields.joinToString("\n") { (k, n) -> "$n：${s.optString(k, "尚未收到")}" } + "\n历史记录不代表此刻仍在运行。"
        }
    } catch (_: Throwable) { "诊断通道暂不可读，请刷新框架连接。" }

    private fun remote(): SharedPreferences? = try { App.serviceRef?.let { Prefs.remote(it) } } catch (_: Throwable) { null }
    private fun write(block: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
        val p = remote() ?: run { notice("框架未连接，未写入配置"); return }
        try {
            if (block(p.edit()).commit()) notice("已保存到框架；已加载的玻璃参数将在缓存刷新后更新")
            else notice("保存失败，请刷新框架连接")
        } catch (_: Throwable) { notice("框架写入失败，请重新打开应用") }
    }
    private fun refreshState() {
        val p = remote()
        frameworkLabel.text = if (p == null) "框架未连接 · 需要支持 LibXposed API 102 的 LSPosed" else "框架配置通道已连接 · 系统效果请查看诊断"
        if (selected == 0 && ::master.isInitialized) {
            val enabled = try { p?.getBoolean(Prefs.KEY_MASTER, false) ?: false } catch (_: Throwable) { false }
            reflectingMaster = true
            master.isChecked = enabled
            reflectingMaster = false
            master.isEnabled = p != null
        }
    }
    private fun notice(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

internal object Styles {
    data class Style(val name: String, val desc: String, val blur: Float, val refract: Float, val dispersion: Float, val highlight: Float) {
        fun apply(e: SharedPreferences.Editor): SharedPreferences.Editor = e
            .putFloat(Prefs.KEY_BLUR_RADIUS, blur).putFloat(Prefs.KEY_REFRACTION_AMOUNT, refract)
            .putFloat(Prefs.KEY_DISPERSION, dispersion).putFloat(Prefs.KEY_HIGHLIGHT, highlight)
    }
    val all = listOf(
        Style("清透", "克制的折射与轻微色散，保留背景细节。", 5f, 36f, 0.08f, 0.42f),
        Style("柔雾", "更柔和的背景，适合文字较多的通知卡。", 18f, 24f, 0.04f, 0.35f),
        Style("棱光", "更清晰的轮廓与彩色光边，适合突出玻璃质感。", 8f, 64f, 0.22f, 0.60f)
    )
}
