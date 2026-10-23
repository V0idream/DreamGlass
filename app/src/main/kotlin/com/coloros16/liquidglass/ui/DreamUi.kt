/* DreamGlass UI, 2026-10-23. New presentation code; distributed under GPL-3.0. */
package com.coloros16.liquidglass.ui

import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A shared visual system for the dashboard, parameter pages and offline legal reader. */
object DreamUi {
    val canvas = Color.rgb(11, 20, 29)
    val surface = Color.rgb(20, 33, 44)
    val raised = Color.rgb(27, 44, 55)
    val line = Color.rgb(43, 63, 74)
    val ink = Color.rgb(235, 243, 244)
    val muted = Color.rgb(146, 166, 177)
    val accent = Color.rgb(151, 229, 208)
    fun openLink(context: Context, url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("DreamGlass", url))
            Toast.makeText(context, "无法打开浏览器，链接已复制", Toast.LENGTH_LONG).show()
        }
    }
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int = surface, radius: Int = 20, border: Boolean = true) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (border) setStroke(dp(context, 1), line)
    }
    fun label(context: Context, value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        setPadding(0, dp(context, 3), 0, dp(context, 3))
    }
    fun card(parent: LinearLayout): LinearLayout = LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL; background = shape(context)
        setPadding(dp(context, 18), dp(context, 17), dp(context, 18), dp(context, 17))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 14) })
    }
    fun action(parent: LinearLayout, title: String, filled: Boolean = false, click: () -> Unit): TextView =
        label(parent.context, title, 14f, if (filled) canvas else ink, filled).apply {
            gravity = Gravity.CENTER; minHeight = dp(context, 48)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = RippleDrawable(ColorStateList.valueOf(0x227fffff), shape(context, if (filled) accent else raised, 13, !filled), null)
            isClickable = true; isFocusable = true; setOnClickListener { click() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 10) })
        }
    fun row(parent: LinearLayout, title: String, detail: String, click: () -> Unit) {
        val row = LinearLayout(parent.context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(context, 70)
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            background = RippleDrawable(ColorStateList.valueOf(0x167fffff), null, shape(context))
            isClickable = true; isFocusable = true; setOnClickListener { click() }
        }
        val words = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(label(parent.context, title, 15f, ink, true))
        words.addView(label(parent.context, detail, 12f, muted))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(parent.context, "›", 25f, muted).apply { setPadding(dp(context, 12), 0, 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        parent.addView(row)
    }
}

/** An original three-pane mark, drawn as vectors without bundled artwork or icon fonts. */
class DreamMarkView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(width / 48f, height / 48f)
        for (i in 0..2) {
            val y = 8f + i * 9f
            path.reset(); path.moveTo(7f, y + 8f); path.lineTo(24f, y)
            path.lineTo(41f, y + 8f); path.lineTo(24f, y + 16f); path.close()
            paint.style = Paint.Style.FILL; paint.color = if (i == 0) 0x3497e5d0 else 0x181299aa
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
            paint.color = if (i == 0) DreamUi.accent else 0xff5b9eaa.toInt()
            paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(save)
    }
}

/** Parameter groups collapse when the existing binding hides every control in a group. */
class ParameterCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL; background = DreamUi.shape(context)
        setPadding(DreamUi.dp(context, 18), DreamUi.dp(context, 14), DreamUi.dp(context, 18), DreamUi.dp(context, 14))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val empty = childCount > 0 && (0 until childCount).all { getChildAt(it).visibility == GONE }
        (layoutParams as? MarginLayoutParams)?.bottomMargin = if (empty) 0 else DreamUi.dp(context, 12)
        if (empty) setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
        else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
