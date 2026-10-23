/* DreamGlass, 2026-10-23. New offline legal interface; GPL-3.0. */
package com.coloros16.liquidglass.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.coloros16.liquidglass.BuildConfig

/** All license text is bundled with the APK. External source links open only on user action. */
class LicenseActivity : AppCompatActivity() {
    private var document: String? = null
    private val documents = linkedMapOf(
        "项目来源与修改声明" to "THIRD-PARTY-NOTICES.txt",
        "GNU GPL v3 全文" to "GPL-3.0.txt",
        "Apache License 2.0 全文" to "Apache-2.0.txt",
        "版权与 NOTICE" to "NOTICE.txt",
        "依赖组件与版本" to "DEPENDENCIES.txt",
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        document = savedInstanceState?.getString("document")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { up() }
        })
        show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("document", document); super.onSaveInstanceState(outState)
    }
    private fun up() { if (document == null) finish() else { document = null; show() } }
    private fun show() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(DreamUi.canvas) }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        toolbar.addView(DreamUi.label(this, "‹", 32f).apply {
            minWidth = dp(48); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            contentDescription = "返回"; isFocusable = true; setOnClickListener { up() }
        })
        toolbar.addView(DreamUi.label(this, "关于 DreamGlass", 17f, DreamUi.ink, true))
        root.addView(toolbar)
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(body)
        val key = document
        if (key != null && key in documents) {
            body.addView(DreamUi.label(this, key, 23f, DreamUi.ink, true))
            val content = assets.open("legal/${documents.getValue(key)}").bufferedReader().use { it.readText() }
            body.addView(DreamUi.label(this, content, 13f, DreamUi.muted).apply { setTextIsSelectable(true); setPadding(0, dp(18), 0, 0) })
            return
        }
        body.addView(DreamMarkView(this), LinearLayout.LayoutParams(dp(70), dp(70)))
        body.addView(DreamUi.label(this, "DreamGlass", 29f, DreamUi.ink, true))
        body.addView(DreamUi.label(this, "${BuildConfig.VERSION_NAME}  /  For ColorOS 17", 13f, DreamUi.accent))
        body.addView(DreamUi.label(this, "为系统界面调配通透、光线与空间。", 14f, DreamUi.muted).apply { setPadding(0, dp(10), 0, dp(20)) })
        val rights = DreamUi.card(body)
        rights.addView(DreamUi.label(this, "开源与授权", 18f, DreamUi.ink, true))
        rights.addView(DreamUi.label(this,
            "DreamGlass 基于 ColorOS16-LiquidGlass 持续开发，整体按 GNU GPL v3 发布。你可以在相同许可下使用、修改和再分发；本软件不附带任何担保。原作者与贡献者的版权继续保留。完整源码和构建说明随同版本发布包提供。", 13f, DreamUi.muted))
        DreamUi.action(rights, "项目主页与对应源码") {
            DreamUi.openLink(this, "https://github.com/V0idream/DreamGlass")
        }
        val sources = DreamUi.card(body)
        DreamUi.row(sources, "ColorOS16-LiquidGlass", "AYwlilwYA · GPL-3.0 · 渲染与配置代码基础") { open("项目来源与修改声明") }
        DreamUi.row(sources, "AndroidLiquidGlass", "Kyant0 · Apache-2.0 · 部分着色算法的移植来源") { open("版权与 NOTICE") }
        DreamUi.row(sources, "运行时组件", "AndroidX / Material / Kotlin / LibXposed") { open("依赖组件与版本") }
        val legal = DreamUi.card(body)
        legal.addView(DreamUi.label(this, "离线许可文档", 11f, DreamUi.accent, true))
        for (title in documents.keys) DreamUi.row(legal, title, "随安装包提供，可离线阅读与选择复制") { open(title) }
        body.addView(DreamUi.label(this, "Copyright © 2026 DreamGlass contributors.\n第三方组件及派生代码的版权归各自作者。", 11f, DreamUi.muted))
    }
    private fun open(title: String) { document = title; show() }
    private fun dp(value: Int) = DreamUi.dp(this, value)
}
