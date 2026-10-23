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
    private val ink = Color.rgb(239, 241, 251)
    private val muted = Color.rgb(168, 177, 203)
    private val accent = Color.rgb(186, 170, 255)
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
    private fun bg(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card(parent: LinearLayout = body) = column().apply {
        background = bg(Color.rgb(26, 33, 55)); setPadding(dp(19), dp(16), dp(19), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun action(parent: LinearLayout, title: String, secondary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setTextColor(if (secondary) ink else Color.rgb(27, 24, 49))
        background = bg(if (secondary) Color.rgb(48, 57, 82) else accent, 16)
        setOnClickListener { click() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
    }
    private fun advanced(category: String) = startActivity(Intent(this, SettingsActivity::class.java).apply {
        putExtra("category", category)
    })

    private fun showPage() {
        preview = null
        root = column().apply { setBackgroundColor(Color.rgb(12, 18, 34)) }
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
        head.addView(text("DREAMGLASS  /  COS 17", 12f, accent, true))
        head.addView(text(listOf("让光，有形。", "调出你的玻璃。", "每一步，都看得见。", "你的玻璃，你做主。")[selected], 29f, ink, true))
        frameworkLabel = text("正在读取框架状态…", 12f, muted)
        head.addView(frameworkLabel)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(12)) }
        scroll.addView(body)
        when (selected) { 0 -> overview(); 1 -> material(); 2 -> diagnostics(); else -> settings() }
        val nav = LinearLayout(this).apply { setPadding(dp(12), dp(7), dp(12), dp(7)) }
        listOf("概览", "材质", "诊断", "设置").forEachIndexed { i, name ->
            nav.addView(Button(this).apply {
                text = name; isAllCaps = false; minHeight = dp(48)
                setTextColor(if (i == selected) Color.rgb(27, 24, 49) else muted)
                background = bg(if (i == selected) accent else Color.TRANSPARENT, 16)
                setOnClickListener { selected = i; showPage() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        root.addView(nav)
        refreshState()
    }

    private fun overview() {
        val hero = card()
        hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff333252.toInt(), 0xff163b50.toInt())).apply { cornerRadius = dp(26).toFloat() }
        hero.addView(text("DREAM MATERIAL  01", 11f, accent, true))
        hero.addView(text("通透 · 流光 · 折射", 22f, ink, true))
        attachPreview(hero)
        hero.addView(text("应用内材质示意 · 系统效果请在诊断页验证", 11f, muted))
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
        control.addView(text("总开关在目标进程启动时读取。首次启用后，先测试桌面，再测试 SystemUI。", 12f, muted))
        val features = card()
        features.addView(text("把效果用在合适的地方", 17f, ink, true))
        action(features, "通知与控制中心  ›", true) { advanced("master") }
        action(features, "桌面文件夹与 Dock  ›", true) { advanced("desktop") }
        action(features, "动画与空间感  ›", true) { advanced("tilt") }
        features.addView(text("桌面 17.3.12 的旧版弹簧与 Zuyqa 倾斜入口不适用；通知卡倾斜保留实验设置。", 12f, muted))
        action(features, "完整高级设置  ›", true) { selected = 3; showPage() }
        rootControls()
        val safe = card()
        safe.addView(text("回到系统原有质感", 17f, ink, true))
        safe.addView(text("暂停玻璃会还原系统材质。彻底停用请关闭总开关，并在 LSPosed 撤销作用域后手动重启。", 12f, muted))
        action(safe, "暂停玻璃效果", true) {
            write { it.putBoolean(Prefs.KEY_GLASS_ENABLE, false) }
        }
        action(safe, "恢复玻璃效果", true) { write { it.putBoolean(Prefs.KEY_GLASS_ENABLE, true) } }
        body.addView(text("DreamGlassforCOS17  ·  0.1.1 Refine 3\n基于 ColorOS16-LiquidGlass 的渲染能力继续开发", 11f, muted))
    }

    private fun attachPreview(parent: LinearLayout) {
        val p = LiquidGlassPreviewView(this)
        preview = p
        parent.addView(p, LinearLayout.LayoutParams(-1, dp(155)).apply { topMargin = dp(12); bottomMargin = dp(8) })
        updatePreview()
    }

    private fun material() {
        val canvas = card()
        canvas.addView(text("光的实验室", 22f, ink, true))
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
        tuning.addView(text("细调每一束光", 18f, ink, true))
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
        val intro = card()
        intro.addView(text("完整控制台", 22f, ink, true))
        intro.addView(text("进入子菜单调整参数，返回即回到 DreamGlass。", 13f, muted))
        val entry = card()
        entry.addView(text("启动入口", 18f, ink, true))
        val alias = android.content.ComponentName(this, "$packageName.LauncherAlias")
        val icon = Switch(this).apply {
            text = "显示桌面图标"; setTextColor(ink); minHeight = dp(48)
            isChecked = packageManager.getComponentEnabledSetting(alias) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            setOnCheckedChangeListener { control, visible ->
                try {
                    packageManager.setComponentEnabledSetting(alias,
                        if (visible) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP)
                    notice(if (visible) "桌面入口已显示" else "桌面入口已隐藏，可从 LSPosed 模块设置中恢复")
                } catch (_: Exception) {
                    control.setOnCheckedChangeListener(null)
                    control.isChecked = !visible
                    notice("切换失败，请重新打开设置重试")
                }
            }
        }
        entry.addView(icon)
        entry.addView(text("隐藏仅影响桌面入口；LSPosed 模块设置入口始终保留。", 12f, muted))
        for ((key, label) in listOf(
            "master" to "总开关与渲染", "material" to "液态玻璃材质", "background" to "背景采样与性能",
            "text" to "文字与可读性", "mask" to "遮罩", "desktop" to "桌面文件夹与 Dock",
            "seedling" to "流体云", "anim" to "桌面动画参数", "tilt" to "倾斜与透视",
            "log" to "日志与导出", "config" to "配置管理", "about" to "开源参考信息"
        )) {
            val c = card()
            action(c, "$label  ›", true) { advanced(key) }
        }
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
        d.addView(text("连接 ≠ 已绘制", 21f, ink, true))
        d.addView(text("这里显示目标进程上次回传的实际阶段。首次绘制记录只证明绘制调用完成，视觉与稳定性仍需真机检查。", 13f, muted))
        action(d, "刷新运行记录", true) { showPage() }
        for ((pkg, label) in listOf("com.android.launcher" to "系统桌面", "com.android.systemui" to "SystemUI")) {
            val c = card(); c.addView(text(label, 18f, ink, true)); c.addView(text(pkg, 11f, muted))
            val report = readReport(pkg)
            c.addView(text(report, 13f, muted))
        }
        rootControls()
        val tools = card()
        tools.addView(text("验证与恢复", 18f, ink, true))
        tools.addView(text("1. 安装后先打开设置检查框架通道。\n2. 在 LSPosed 启用桌面作用域，保存总开关，手动重启桌面后检查文件夹。\n3. 确认可用后，再启用 SystemUI 并手动重启。\n4. 如遇异常，通过 LSPosed 禁用本模块并重启；不要清除桌面数据。", 13f, muted))
        action(tools, "复制诊断摘要", true) {
            val report = "DreamGlassforCOS17 0.1.1 Refine3\n${frameworkLabel.text}\n" + listOf("com.android.launcher", "com.android.systemui").joinToString("\n") { "$it\n${readReport(it)}" }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DreamGlass", report))
            notice("已复制；摘要不含截图或应用内容")
        }
        action(tools, "详细日志与导出  ›", true) { advanced("log") }
        action(tools, "许可证与开源来源", true) {
            android.app.AlertDialog.Builder(this).setTitle("开源与授权").setMessage("DreamGlassforCOS17\n\n基于 AYwlilwYA/ColorOS16-LiquidGlass\nGPL-3.0，保留原作者版权。\n\n液态玻璃着色器包含 Kyant0/AndroidLiquidGlass 的 Apache-2.0 派生实现。完整声明见源码 THIRD-PARTY-NOTICES.md。\n\nElbowHitColor 仅作参考，未复制其代码或素材。").setPositiveButton("知道了", null).show()
        }
    }

    private fun readReport(pkg: String): String = try {
        val raw = RuntimeStore.local(this).getString(RuntimeDiagnostics.PREFIX + pkg, null)
        if (raw == null) "尚无运行记录。请检查 API 102 框架支持、作用域和目标进程是否已重启。"
        else {
            val o = JSONObject(raw); val s = o.getJSONObject("stages")
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(o.getLong("at")))
            val fields = listOf("loaded" to "入口", "configured" to "配置", "mounted" to "Hook", "invoked" to "调用", "source" to "背景", "render" to "绘制", "extensions" to "扩展") +
                if (pkg == "com.android.systemui") listOf("startup" to "初始化", "content_render" to "内容绘制", "notification_glass" to "通知玻璃路径", "notification_blur" to "通知回退记录", "qs_glass" to "按钮玻璃路径", "qs_blur" to "按钮回退记录", "qs_seekbar_hook" to "进度接口", "qs_seekbar_active" to "进度对象", "qs_seekbar_tint" to "进度填充", "qs_tile_mask" to "按钮遮罩", "qs_slider_hook" to "滑杆轮廓接口", "qs_slider_clip" to "滑杆填充裁剪", "qs_icon_hook" to "图标底层接口", "qs_icon_background" to "横向图标底层", "volume_native_hook" to "独立音量归属接口", "volume_native" to "独立音量原生保护") else emptyList()
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
