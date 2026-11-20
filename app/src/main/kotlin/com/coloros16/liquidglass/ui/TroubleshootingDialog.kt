package com.coloros16.liquidglass.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.coloros16.liquidglass.diagnostics.Troubleshooting
import io.github.libxposed.service.XposedService

internal object TroubleshootingDialog {
    fun show(activity: AppCompatActivity, service: XposedService?, checks: () -> Troubleshooting.Checks) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle("自助故障排查")
            .setMessage("正在检查效果开关、系统组件和模块作用域…")
            .setPositiveButton(android.R.string.ok, null)
            .show()
        val context = activity.applicationContext
        Thread({
            val result = runCatching { Troubleshooting.scan(context, service, checks()) }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing && dialog.isShowing) {
                    dialog.setMessage(result.fold(
                        onSuccess = { format(activity, it) },
                        onFailure = { "暂时无法读取诊断结果，请关闭后重试。" },
                    ))
                }
            }
        }, "support-diagnostics").start()
    }

    private fun format(activity: AppCompatActivity, sections: List<Troubleshooting.Section>): CharSequence {
        val text = SpannableStringBuilder()
        val theme = android.util.TypedValue()
        val color = if (activity.theme.resolveAttribute(android.R.attr.colorError, theme, true)) {
            if (theme.resourceId != 0) activity.getColor(theme.resourceId) else theme.data
        } else 0xffc35449.toInt()
        for ((i, section) in sections.withIndex()) {
            if (i > 0) text.append("\n\n")
            val start = text.length
            text.append(section.title)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (row in section.rows) {
                text.append("\n\n")
                val rowStart = text.length
                val tag = when (row.level) {
                    Troubleshooting.Level.OK -> "已检查"
                    Troubleshooting.Level.CHECK -> "请检查"
                    Troubleshooting.Level.CONFLICT -> "可能冲突"
                    Troubleshooting.Level.INFO -> ""
                }
                if (tag.isNotEmpty()) text.append(tag).append(" · ")
                text.append(row.title)
                if (row.level == Troubleshooting.Level.CONFLICT) {
                    text.setSpan(StyleSpan(Typeface.BOLD), rowStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(ForegroundColorSpan(color), rowStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.append("\n").append(row.detail)
            }
        }
        return text
    }
}
