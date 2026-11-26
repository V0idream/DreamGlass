package com.coloros16.liquidglass.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.diagnostics.ComponentApkCatalog
import com.coloros16.liquidglass.diagnostics.Troubleshooting
import com.coloros16.liquidglass.root.RootGrant
import io.github.libxposed.service.XposedService

internal object TroubleshootingDialog {
    fun show(activity: AppCompatActivity, service: XposedService?, checks: () -> Troubleshooting.Checks) {
        val builder = AlertDialog.Builder(activity, R.style.Theme_Troubleshooting)
        val theme = builder.context
        val ink = theme.getColor(R.color.troubleshooting_ink)
        val surface = theme.getColor(R.color.troubleshooting_surface)
        val warning = theme.getColor(R.color.troubleshooting_warning)
        val conflict = theme.getColor(R.color.troubleshooting_conflict)
        fun dp(value: Int) = (value * theme.resources.displayMetrics.density + .5f).toInt()
        val body = TextView(theme).apply {
            setTextColor(ink)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(24), dp(12), dp(24), dp(10))
            setTextIsSelectable(true)
        }
        val content = ScrollView(theme).apply {
            setBackgroundColor(surface)
            addView(body)
        }
        val dialog = builder.setTitle("自助故障排查")
            .setView(content)
            .setNeutralButton("授予 Root 并重查", null)
            .setPositiveButton("关闭", null)
            .show()
        val background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(18).toFloat()
        }
        dialog.window?.setBackgroundDrawable(InsetDrawable(background, dp(14), dp(8), dp(14), dp(8)))
        val rootButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        rootButton.setTextColor(ink)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ink)

        val context = activity.applicationContext
        fun scan(requestRoot: Boolean) {
            rootButton.isEnabled = false
            body.text = if (requestRoot) "正在等待 Root 授权…" else "正在核对效果开关、APK 指纹和模块作用域…"
            content.scrollTo(0, 0)
            Thread({
                val granted = if (requestRoot) ComponentApkCatalog.requestRoot(context) else null
                val notice = when (granted) {
                    true -> "Root 已授权。普通权限读不到的 APK 将通过 Root 核对。"
                    false -> if (RootGrant.managerNeedsPregrant())
                        "请在 Magisk 的「超级用户」页面授权本应用，再点「授予 Root 并重查」。"
                        else "Root 未授权或不可用，已继续使用普通权限核对。"
                    null -> null
                }
                val result = runCatching { Troubleshooting.scan(context, service, checks(), allowRoot = granted) }
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing && dialog.isShowing) {
                        val report = result.fold(
                            onSuccess = { format(it, warning, conflict) },
                            onFailure = { "暂时无法读取诊断结果，请关闭后重试。" },
                        )
                        body.text = if (notice == null) report else SpannableStringBuilder(notice).append("\n\n").append(report)
                        content.scrollTo(0, 0)
                        rootButton.isEnabled = true
                    }
                }
            }, "support-diagnostics").start()
        }
        rootButton.setOnClickListener { scan(true) }
        scan(false)
    }

    private fun format(sections: List<Troubleshooting.Section>, warning: Int, conflict: Int): CharSequence {
        val text = SpannableStringBuilder()
        for ((i, section) in sections.withIndex()) {
            if (i > 0) text.append("\n\n")
            var start = text.length
            text.append(section.title)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (row in section.rows) {
                text.append("\n\n")
                start = text.length
                val tag = when (row.level) {
                    Troubleshooting.Level.OK -> "已检查"
                    Troubleshooting.Level.CHECK -> "请检查"
                    Troubleshooting.Level.CONFLICT -> "可能冲突"
                    Troubleshooting.Level.INFO -> ""
                }
                if (tag.isNotEmpty()) text.append(tag).append(" · ")
                text.append(row.title)
                val color = when (row.level) {
                    Troubleshooting.Level.CHECK -> warning
                    Troubleshooting.Level.CONFLICT -> conflict
                    else -> null
                }
                if (color != null) {
                    text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.append("\n").append(row.detail)
            }
        }
        return text
    }
}
